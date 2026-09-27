package gsb.c14.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 日历坐标：采用天文学编号（ISO 8601 / 天文学年）。
 * year 0 = 1 BCE，-1 = 2 BCE；正数即 CE 年。
 * 网格单位：year，步长 1 年（端点之间线性插值）。
 */
@Serializable
@JvmInline
value class AstronomicalYear(val value: Int) : Comparable<AstronomicalYear> {
    override fun compareTo(other: AstronomicalYear): Int = value.compareTo(other.value)
    fun eraLabel(): String =
        if (value >= 1) "${value} CE"
        else if (value == 0) "1 BCE"
        else "${1 - value} BCE"
    companion object {
        /** 把 "120 BCE" / "50 CE" / "1 BCE" 转成天文学年；无法转换返回 null 并保留原文。 */
        fun parseEraLabel(raw: String): Int? {
            val m = Regex("""^\s*(\d+)\s*(BCE|BC|CE|AD)\s*$""", RegexOption.IGNORE_CASE).matchEntire(raw) ?: return null
            val n = m.groupValues[1].toInt()
            return when (m.groupValues[2].uppercase()) {
                "CE", "AD" -> n
                else -> if (n >= 1) 1 - n else null
            }
        }
    }
}

/** 带单位/基准的数量；导入时保留原始表示，规范化失败时 raw 保留、value=null 并附诊断。 */
@Serializable
data class Quantified(
    val raw: String,
    val value: Double?,
    val unit: String,
    val basis: String? = null,
    val diagnostics: List<String> = emptyList()
) {
    companion object {
        fun radiocarbonYears(raw: String): Quantified {
            val v = raw.trim().toDoubleOrNull()
            return Quantified(raw = raw, value = v, unit = "14C yr BP", basis = "radiocarbon_years_before_present_1950",
                diagnostics = if (v == null) listOf("unparseable_radiocarbon_years:$raw") else emptyList())
        }
        fun years(raw: String): Quantified {
            val v = raw.trim().toDoubleOrNull()
            return Quantified(raw = raw, value = v, unit = "cal yr",
                diagnostics = if (v == null) listOf("unparseable_years:$raw") else emptyList())
        }
    }
}

/** 校准曲线点（按天文学年排序）。curveAge / curveSigma 单位 14C yr BP。 */
@Serializable
data class CurvePoint(val year: Int, val curveAge: Double, val curveSigma: Double)

/**
 * 校准曲线：固定版本、固定插值规则（年份之间线性）。
 * id/version 是稳定身份；多峰来自曲线本身的摆动，引擎不做任何平滑。
 */
@Serializable
data class CalibrationCurve(
    val id: String,
    val version: String,
    val label: String,
    val points: List<CurvePoint>,
    val interpolation: String = "linear",
    val source: String = "casepack"
) {
    val minYear: Int get() = points.first().year
    val maxYear: Int get() = points.last().year

    /** 在天文学年 t 处线性插值（边界：超出端点抛 OutOfCurveRange，由调用方决定诊断/截断）。 */
    fun at(t: Int): CurvePoint {
        if (t < minYear || t > maxYear) throw OutOfCurveRange(id, version, t, minYear, maxYear)
        val i = points.binarySearchBy(t) { it.year }
        if (i >= 0) return points[i]
        val after = -i - 1
        val p0 = points[after - 1]; val p1 = points[after]
        val f = (t - p0.year).toDouble() / (p1.year - p0.year)
        return CurvePoint(t, p0.curveAge + f * (p1.curveAge - p0.curveAge),
            p0.curveSigma + f * (p1.curveSigma - p0.curveSigma))
    }
}

class OutOfCurveRange(val curveId: String, val version: String, val t: Int, val min: Int, val max: Int) :
    RuntimeException("year $t outside curve $curveId@$version range [$min,$max]")

/**
 * 储库偏移分布：以离散网格（offsetYears, weight）给出，权重不需归一化（引擎会归一化）。
 * 单位：年。允许多峰 offset（例如海洋混合食物的两个来源）。
 */
@Serializable
data class ReservoirOffset(
    val id: String,
    val version: Int,
    val label: String,
    val meanYears: Double?,
    val sigmaYears: Double?,
    val grid: List<OffsetAtom>,
    val source: String,
    val rawRepresentation: String
) {
    /** 单个偏移分量；weight 相对权重。 */
    @Serializable
    data class OffsetAtom(val offsetYears: Double, val sigmaYears: Double, val weight: Double)
}

@Serializable
data class Sample(
    val id: String,
    val version: Int,
    val parentVersion: Int? = null,
    val code: String,
    val age14cRaw: String,
    val sigma14cRaw: String,
    val age14c: Quantified,
    val sigma14c: Quantified,
    val curveId: String,
    val curveVersion: String,
    val offsetId: String?,
    val eraSystem: String,
    val source: String,
    val submittedAtVirtual: Long,
    val diagnostics: List<String> = emptyList()
) {
    val ready: Boolean get() = age14c.value != null && sigma14c.value != null
}

/** olderBefore：olderSampleId 的日历年代早于 youngerSampleId。 */
@Serializable
data class SequenceConstraint(
    val id: String,
    val olderSampleId: String,
    val youngerSampleId: String,
    val source: String,
    val note: String = ""
)

/** 排除决定（人工）：对某样本排除一段日历区间，保留理由。 */
@Serializable
data class Exclusion(
    val id: String,
    val sampleId: String,
    val fromYear: Int?,
    val toYear: Int?,
    val rawRange: String,
    val reason: String,
    val source: String
)

@Serializable
data class OffsetHypothesis(
    val offsetId: String?,
    val label: String
)

/** 人工决定状态机。 */
@Serializable
enum class DecisionKind { @SerialName("accept") ACCEPT, @SerialName("reject") REJECT, @SerialName("adjust") ADJUST, @SerialName("conflict") CONFLICT }

@Serializable
data class Decision(
    val id: String,
    val runId: String?,
    val sampleId: String,
    val kind: DecisionKind,
    /** ADJUST 时指定的新储库偏移 id；CONFLICT 时记录互相冲突的决定。 */
    val targetOffsetId: String? = null,
    val acceptedIntervalIds: List<String> = emptyList(),
    val reason: String = "",
    val conflictsWith: List<String> = emptyList(),
    val parentDecisionId: String? = null,
    val virtualTime: Long,
    val undone: Boolean = false,
    val undoOf: String? = null
)
