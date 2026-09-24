package org.chrono.carbonbench.domain

import kotlinx.serialization.Serializable

/**
 * 领域量都携带单位或坐标基准。
 *
 * 日历坐标统一使用 [CalBpYear]（cal BP，基准年 1950 CE）做内部规范化计算；
 * 展示层通过 [Era] 在 BCE/CE 与 cal BP 之间换算。BCE/CE 采用天文年整数约定：
 * 1 BCE 对应天文年 0，2 BCE 对应天文年 -1。
 */
@JvmInline
value class CalBpYear(val value: Int) : Comparable<CalBpYear> {
    override fun compareTo(other: CalBpYear): Int = value.compareTo(other.value)
    operator fun minus(other: CalBpYear): Int = value - other.value
    operator fun plus(delta: Int): CalBpYear = CalBpYear(value + delta)
    operator fun minus(delta: Int): CalBpYear = CalBpYear(value - delta)
}

/** 放射性碳年龄（实验室结果），单位 14C yr BP（BP 基准同为 1950 CE）。 */
@Serializable
data class RadiocarbonAge(val bpYears: Double, val sigma: Double) {
    init {
        require(sigma > 0.0) { "sigma must be positive, got $sigma" }
        require(bpYears > 0.0) { "radiocarbon age must be positive, got $bpYears" }
    }
}

@Serializable
enum class EraSystem { BCE_CE, CAL_BP }

/**
 * 年代系统（BCE/CE 或 cal BP）的展示换算。BCE/CE 与 cal BP 的基准换算：
 * 天文年 y = 1950 - calBP；y >= 1 为 CE，y <= 0 为 BCE（0 = 1 BCE）。
 */
data class EraLabel(val astronomicalYear: Int, val eraSystem: EraSystem) {
    val isBce: Boolean get() = astronomicalYear <= 0
    val commonLabel: String
        get() = when {
            astronomicalYear >= 1 -> "${astronomicalYear} CE"
            astronomicalYear == 0 -> "1 BCE"
            else -> "${1 - astronomicalYear} BCE"
        }

    companion object {
        fun fromCalBp(calBp: Int): EraLabel = EraLabel(1950 - calBp, EraSystem.BCE_CE)
        fun toCalBp(astronomicalYear: Int): Int = 1950 - astronomicalYear
    }
}

/**
 * 储库偏移分布（Delta-R）。所有偏移以 14C 年为单位，正值表示样本显得更老。
 * 允许正态或离散加权混合；混合分布用于检验“偏移分布”而非单一常数偏移。
 */
@Serializable
data class ReservoirOffset(
    val distributionId: String,
    val kind: OffsetKind,
    val mean: Double? = null,
    val sigma: Double? = null,
    val components: List<MixtureComponent> = emptyList(),
    val unit: String = "14C yr",
) {
    init {
        when (kind) {
            OffsetKind.NORMAL -> require(mean != null && sigma != null && sigma > 0.0) {
                "NORMAL offset requires positive sigma"
            }
            OffsetKind.MIXTURE -> {
                require(components.size >= 2) { "MIXTURE offset requires >= 2 components" }
                val total = components.sumOf { it.weight }
                require(total > 0.0) { "mixture weights must sum > 0" }
                components.forEach { require(it.weight > 0.0 && it.sigma > 0.0) { "bad component" } }
            }
        }
    }

    /** 在偏移分布上取固定数值求积分点（离散化），返回 (offsetValue, 概率权重)。 */
    fun discretize(steps: Int = 7): List<Pair<Double, Double>> = when (kind) {
        OffsetKind.NORMAL -> {
            val m = mean!!; val s = sigma!!
            val lo = m - 3.0 * s; val hi = m + 3.0 * s
            gaussWeights(steps, lo, hi, m, s)
        }
        OffsetKind.MIXTURE -> {
            val norm = components.sumOf { it.weight }
            components.flatMap { c ->
                val w = c.weight / norm
                gaussWeights(5, c.mean - 3.0 * c.sigma, c.mean + 3.0 * c.sigma, c.mean, c.sigma)
                    .map { (v, p) -> v to p * w }
            }
        }
    }
}

@Serializable
enum class OffsetKind { NORMAL, MIXTURE }

@Serializable
data class MixtureComponent(val mean: Double, val sigma: Double, val weight: Double)

private fun gaussWeights(n: Int, lo: Double, hi: Double, mean: Double, sigma: Double): List<Pair<Double, Double>> {
    if (hi <= lo) return listOf(mean to 1.0)
    val out = ArrayList<Pair<Double, Double>>(n)
    val step = (hi - lo) / n
    var total = 0.0
    for (i in 0 until n) {
        val v = lo + (i + 0.5) * step
        val p = kotlin.math.exp(-0.5 * ((v - mean) / sigma) * ((v - mean) / sigma))
        out.add(v to p); total += p
    }
    return out.map { (v, p) -> v to (if (total > 0) p / total else 1.0 / n) }
}

/**
 * 校准曲线节点。calBP 处的 14C 年龄与其误差，均以年为单位。
 * 曲线在固定版本下使用线性插值（[org.chrono.carbonbench.core.CalibrationCurve]）。
 */
@Serializable
data class CurvePoint(
    val calBp: Int,
    val c14Age: Double,
    val c14Sigma: Double,
)

@Serializable
data class CalibrationCurveDef(
    val curveId: String,
    val version: String,
    val description: String,
    val points: List<CurvePoint>,
    val unit: String = "cal BP years / 14C yr",
) {
    init {
        require(points.size >= 2) { "curve needs >= 2 points" }
        val sorted = points.zipWithNext().all { (a, b) -> a.calBp < b.calBp }
        require(sorted) { "curve points must be strictly increasing in calBp" }
        require(points.all { it.c14Sigma > 0.0 }) { "curve sigma must be positive" }
    }
}

/** 原始测量提交（不可变）。保留原始表示与来源。 */
@Serializable
data class MeasurementSource(
    val measurementId: String,
    val labCode: String,
    val rawAgeText: String,
    val rawSigmaText: String,
    val age: RadiocarbonAge,
    val materialNote: String,
    val sourceRef: String,
    val eraSystem: EraSystem = EraSystem.CAL_BP,
    val unit: String = "14C yr BP",
)

/** 样本把测量与偏移分布、年代系统绑定。 */
@Serializable
data class SampleSpec(
    val sampleId: String,
    val label: String,
    val measurementId: String,
    val offsetDistributionId: String?,
)

/** 层序约束：[youngerId] 的日历年龄必须严格老于（calBP 大于）[olderId]。 */
@Serializable
data class SequenceConstraint(
    val constraintId: String,
    val youngerId: String,
    val olderId: String,
    val sourceRef: String,
    val note: String = "",
)

/** 排除决定：人工把某样本某日历区间排除，带原因与来源。 */
@Serializable
data class ExclusionDecision(
    val decisionId: String,
    val sampleId: String,
    val intervalStartCalBp: Int,
    val intervalEndCalBp: Int,
    val reason: String,
    val sourceRef: String,
)

/** 导入时无法转换/规范化的片段保留原位置并附诊断，绝不丢弃。 */
@Serializable
data class ImportDiagnostic(
    val path: String,
    val severity: String,
    val code: String,
    val message: String,
    val rawFragment: String,
)
