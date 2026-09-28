package org.chronobench

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * 领域模型。所有领域量都携带单位/坐标基准；原始表示(raw*)在导入时原样保留，
 * 只有派生层做规范化；无法转换的值保留在原位置并附诊断。
 */

interface Clock {
    fun nowMillis(): Long
}

class SystemClock : Clock {
    override fun nowMillis(): Long = System.currentTimeMillis()
}

/** 测试用虚拟时钟：固定起点，按固定步长推进，保证记录可重放。 */
class VirtualClock(
    @Volatile private var current: Long = 1_577_836_800_000L // 2020-01-01T00:00:00Z 固定种子时间
) : Clock {
    private val stepMs = 1000L
    override fun nowMillis(): Long {
        val t = current
        current += stepMs
        return t
    }
    fun advance(millis: Long) { current += millis }
    fun set(millis: Long) { current = millis }
}

@Serializable
data class Provenance(
    val origin: String,                 // 例如 casepack/manual/import-bundle
    val originRef: String? = null,      // 例如 样例文件相对路径/导入批次号
    val rawRepresentation: String? = null, // 导入时的原始字符串表示
    val note: String? = null
)

@Serializable
data class Quantity(
    val value: Double? = null,          // 规范化后的数值；无法转换时为 null
    val unit: String,                  // 规范化单位（BP-14C / calBP-years / BP-14C-years）
    val rawValue: String,              // 原始表示
    val rawUnit: String,               // 原始单位
    val diagnostics: List<String> = emptyList() // 无法转换时的诊断
) {
    val convertible: Boolean get() = value != null && diagnostics.isEmpty()
}

@Serializable
data class CurveKnot(
    val calBP: Int,
    val radioCarbonBP: Double,
    val sigma: Double
)

enum class SourceKind { CURVE, MEASUREMENT, OFFSET_HYPOTHESIS, ERA_SYSTEM, SEQUENCE_LINK, EXCLUSION }

@Serializable
data class CurvePayload(
    val knots: List<CurveKnot>,
    val xUnit: String = "calBP-years",
    val yUnit: String = "BP-14C-years"
)

@Serializable
data class MeasurementPayload(
    val labCode: String,
    val sampleCode: String,
    val age: Quantity,                 // ^14C age (BP)
    val sigma: Quantity,               // 实验室 1σ
    val curveId: String,               // 选定校准曲线（逻辑 id）
    val defaultOffsetId: String? = null
)

@Serializable
data class OffsetHypothesisPayload(
    val label: String,                 // 例如 H0 / H-marine
    val targetSampleCode: String?,     // null = 全局假设
    val mean: Quantity,                // 储库偏移均值（^14C 年，加到曲线端）
    val sigma: Quantity,               // 偏移分布标准差（高斯）
    val distribution: String = "gaussian"
)

@Serializable
data class EraSystemPayload(
    val label: String,                 // 例如 天文年/Gregorian BCE-CE
    val zeroConvention: String,        // astronomical_year_zero
    val coordinates: String            // calBP -> astronomical year: astro = 1950 - calBP
)

@Serializable
data class SequenceLinkPayload(
    val label: String,
    val olderSampleCode: String,
    val youngerSampleCode: String,
    val relation: String               // older_than | same_layer
)

@Serializable
data class ExclusionPayload(
    val label: String,
    val targetSampleCode: String,
    val targetIntervalId: String? = null, // null = 排除整样本候选
    val reason: String
)

@Serializable
data class SourceVersion(
    val id: String,                    // 稳定逻辑身份，如 M01
    val projectId: String,
    val kind: SourceKind,
    val version: Int,                  // 1 起，同一 id 递增
    val parentId: String? = null,      // 父版本（稳定 id；链回旧版本）
    val payload: JsonElement,
    val submittedAt: Long,
    val submittedBy: String,
    val superseded: Boolean = false,
    val provenance: Provenance
)

/** 校准网格上的一点积分证据。 */
@Serializable
data class DensityPoint(
    val calBP: Int,
    val astronomicalYear: Int,         // 1950 - calBP；0 = 1 BCE
    val eraLabel: String,
    val interpolatedCurveBP: Double,
    val likelihoodRaw: Double,
    val density: Double,               // 归一化后密度（概率/年）
    val boundary: Boolean,             // 是否落在曲线/网格边界，走边界插值分支
    val segment: String? = null        // 所在区间 id（>floor 的部分）
)

@Serializable
data class CalibratedInterval(
    val intervalId: String,            // 内容指纹派生的稳定身份
    val rank: Int,
    val startCalBP: Int,
    val endCalBP: Int,
    val startEraLabel: String,
    val endEraLabel: String,
    val mass: Double,                  // 区间积分概率
    val peakCalBP: Int,
    val peakDensity: Double,
    val tiedWith: List<String> = emptyList(),
    val tieGroup: String? = null,
    val excluded: Boolean = false,
    val exclusionReason: String? = null
)

@Serializable
data class CalibrationResult(
    val derivationId: String,
    val projectId: String,
    val measurementId: String,
    val measurementVersion: Int,
    val offsetId: String?,
    val offsetVersion: Int?,
    val curveId: String,
    val curveVersion: Int,
    val eraSystemId: String,
    val eraSystemVersion: Int,
    val algorithmVersion: String,
    val paramFingerprint: String,
    val directSources: List<String>,   // 直接来源稳定身份(含版本)
    val computedAt: Long,
    val gridStepYears: Int,
    val curveMinCalBP: Int,
    val curveMaxCalBP: Int,
    val normalizationZ: Double,
    val multimodal: Boolean,
    val modeCount: Int,
    val points: List<DensityPoint>,
    val intervals: List<CalibratedInterval>,
    val status: String = CURRENT,      // current | stale
    val diagnostics: List<String> = emptyList()
) {
    companion object {
        const val CURRENT = "current"
        const val STALE = "stale"
        const val ALGORITHM_VERSION = "cal-grid-gaussian-v1"
    }
}

@Serializable
data class LinkAssessment(
    val linkId: String,
    val linkVersion: Int,
    val olderSampleCode: String,
    val youngerSampleCode: String,
    val relation: String,
    val satisfied: Boolean,
    val probability: Double,           // 存在合法组合的联合概率质量
    val legalCombinations: List<IntervalPair>,
    val suggestedAdjustments: List<String>,
    val status: String                 // satisfied | violated | unknown
) {
    companion object {
        const val SATISFIED = "satisfied"
        const val VIOLATED = "violated"
        const val UNKNOWN = "unknown"
    }
}

@Serializable
data class IntervalPair(
    val olderIntervalId: String,
    val youngerIntervalId: String,
    val jointProbability: Double
)

@Serializable
data class SequenceResult(
    val derivationId: String,
    val projectId: String,
    val algorithmVersion: String,
    val paramFingerprint: String,
    val directSources: List<String>,
    val computedAt: Long,
    val links: List<LinkAssessment>,
    val overallStatus: String,         // consistent | conflict
    val status: String = CalibrationResult.CURRENT
) {
    companion object {
        const val CONSISTENT = "consistent"
        const val CONFLICT = "conflict"
        const val ALGORITHM_VERSION = "sequence-pair-enumeration-v1"
    }
}

@Serializable
data class EventRecord(
    val seq: Long,
    val eventId: String,
    val projectId: String,
    val type: String,
    val actor: String,
    val at: Long,
    val targets: List<String> = emptyList(),
    val inputs: List<String> = emptyList(),
    val outputs: List<String> = emptyList(),
    val detail: JsonElement? = null,
    val summary: String
)

@Serializable
data class Project(
    val id: String,
    val name: String,
    val createdAt: Long,
    val description: String? = null
)
