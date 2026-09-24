package org.gsb.carbon.model

import kotlinx.serialization.Serializable

/**
 * 原始来源记录层。
 *
 * 原则（需求「导入层」）：
 *  - 原始输入一经提交不可覆盖；修订以新版本 + 父版本关系表达；
 *  - 所有领域量携带单位/坐标基准；
 *  - 导入时保留原始表示（rawText），派生层才做规范化；
 *  - 无法转换的值留在原位置并附诊断，不能为了画图而丢掉。
 */

/** 带原始表示与单位的量值。converted 为 null 表示无法转换，必须携带诊断。 */
@Serializable
data class Measure(
    val rawText: String,
    val unit: String,
    val value: Double? = null,
    val conversionNote: String? = null,
) {
    val convertible: Boolean get() = value != null
}

@Serializable
data class CurvePoint(
    val calBp: Double,
    val c14Age: Double,
    val sigma: Double,
)

/** 校准曲线（一个不可变版本）。cal BP 坐标基准、年龄单位 14C yr BP。 */
@Serializable
data class CalibrationCurve(
    val curveId: String,
    val version: String,
    val parentVersion: String? = null,
    val points: List<CurvePoint>,
    val source: String,
    val notes: String = "",
) {
    val versionId: String get() = "$curveId@$version"

    init {
        require(points.zipWithNext().all { (a, b) -> a.calBp < b.calBp }) {
            "曲线点必须按 calBp 升序排列: $versionId"
        }
        require(points.all { it.sigma > 0.0 }) { "曲线 sigma 必须为正: $versionId" }
    }

    fun ageAt(calBp: Double): Double = Interpolation.linear(points, calBp, CurvePoint::calBp, CurvePoint::c14Age)
    fun sigmaAt(calBp: Double): Double = Interpolation.linear(points, calBp, CurvePoint::calBp, CurvePoint::sigma)

    val minBp: Double get() = points.first().calBp
    val maxBp: Double get() = points.last().calBp
}

/** 一条曲线点上的线性插值（边界插值检验对象）。 */
object Interpolation {
    fun <T> linear(items: List<T>, x: Double, xOf: (T) -> Double, yOf: (T) -> Double): Double {
        require(items.isNotEmpty()) { "空曲线不可插值" }
        if (x <= xOf(items.first())) return yOf(items.first())
        if (x >= xOf(items.last())) return yOf(items.last())
        var lo = 0
        var hi = items.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (xOf(items[mid]) <= x) lo = mid else hi = mid
        }
        val a = items[lo]
        val b = items[hi]
        val xa = xOf(a); val xb = xOf(b)
        val t = (x - xa) / (xb - xa)
        return yOf(a) + t * (yOf(b) - yOf(a))
    }
}

/** 实验室放射性碳测量（一个不可变版本；修订时 parentVersion 指向旧版本）。 */
@Serializable
data class Measurement(
    val measurementId: String,
    val version: Int,
    val parentVersion: Int? = null,
    val labCode: String,
    val sampleLabel: String,
    val age: Measure,
    val sigma: Measure,
    val material: String,
    val source: String,
) {
    val idVersion: String get() = "$measurementId@v$version"
}

/** 储库偏移（reservoir offset）分布分量；deltaR 为偏移均值，sigma 为不确定度。 */
@Serializable
data class OffsetComponent(
    val deltaR: Double,
    val sigma: Double,
    val weight: Double,
    val basis: String,
) {
    init {
        require(sigma >= 0.0 && weight >= 0.0) { "偏移分量 sigma/权重不可为负" }
    }
}

/** 储库偏移假设：单峰或多峰混合分布，单位 14C yr BP。 */
@Serializable
data class OffsetDistribution(
    val offsetId: String,
    val label: String,
    val components: List<OffsetComponent>,
    val source: String,
) {
    init {
        val w = components.sumOf { it.weight }
        require(components.isNotEmpty() && w > 0.0) { "偏移分布至少一个正权重分量: $offsetId" }
    }
    val normalizedWeights: List<Double> = components.map { it.weight / components.sumOf { c -> c.weight } }
}

/** 年代系统（纪年法）。 */
@Serializable
data class EraSystem(
    val eraSystemId: String,
    val name: String,
    val datum: String,
    val source: String,
)

/** 层序关系：olderSample 应早于 youngerSample（older 的 cal BP 更大）。 */
@Serializable
data class SequenceEdge(
    val edgeId: String,
    val olderMeasurementId: String,
    val youngerMeasurementId: String,
    val source: String,
)

/** 排除决定：某测量的某个日历区间被人工标记为排除候选（排除本身仍是带来源的一等数据）。 */
@Serializable
data class Exclusion(
    val exclusionId: String,
    val measurementId: String,
    val loBp: Double,
    val hiBp: Double,
    val reason: String,
    val source: String,
)
