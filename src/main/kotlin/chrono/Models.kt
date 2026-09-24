package chrono

import kotlinx.serialization.Serializable

/** 稳定身份：实体一旦提交即拥有全局稳定 id；修订通过 parentId/version 表达。 */

@Serializable
enum class SourceType {
    MEASUREMENT, CALIBRATION_CURVE, OFFSET_DISTRIBUTION,
    ERA_SYSTEM, SEQUENCE_CONSTRAINT, EXCLUSION_DECISION
}

/** 年代系统：内部统一使用 cal BP（1950=0），显示层负责 BCE/CE 跨界呈现。 */
@Serializable
data class EraSystem(
    val id: String,
    val name: String,
    val zeroReference: String = "cal BP reference year 1950 CE",
    val yearAstronomicalZero: Int = 0
)

@Serializable
data class Provenance(
    val origin: String,                 // 例如 case-package / manual / import-bundle
    val importedFrom: String? = null,   // 原始表示所在文件/片段标识
    val rawRepresentation: String,      // 原始表示逐字保留
    val note: String = ""
)

@Serializable
data class SourceRecord(
    val id: String,
    val type: SourceType,
    val version: Int,
    val parentId: String? = null,
    val createdAtTick: Long,
    val provenance: Provenance,
    val payloadJson: String,
    val supersededBy: String? = null
)

/** 实验室 radiocarbon 测量，年龄与标准差均携带单位。 */
@Serializable
data class Measurement(
    val id: String,
    val label: String,
    val ageValue: Double,
    val ageUnit: String = "14C BP",
    val sigmaValue: Double,
    val sigmaUnit: String = "14C BP",
    val curveId: String,
    val offsetId: String,
    val eraSystemId: String,
    val diagnostics: List<String> = emptyList()
)

/** 校准曲线点。calbp 为 cal BP；c14age/c14sigma 单位 14C BP。 */
@Serializable
data class CurvePoint(val calbp: Double, val c14age: Double, val c14sigma: Double)

@Serializable
data class CalibrationCurve(
    val id: String,
    val name: String,
    val version: String,
    val points: List<CurvePoint>,
    val ageUnit: String = "14C BP",
    val calUnit: String = "cal BP"
)

/** 储库偏移分布。NORMAL 解析处理；DISCRETE/MIXTURE 数值卷积；INVALID 保留原值附诊断。 */
@Serializable
data class OffsetAtom(val delta: Double, val sigma: Double, val weight: Double = 1.0)

@Serializable
data class OffsetDistribution(
    val id: String,
    val label: String,
    val kind: String,                 // NORMAL | DISCRETE | MIXTURE | INVALID
    val mean: Double? = null,
    val sigma: Double? = null,
    val atoms: List<OffsetAtom> = emptyList(),
    val unit: String = "14C BP",
    val rawExpression: String = "",
    val diagnostics: List<String> = emptyList()
)

@Serializable
data class SequenceConstraint(
    val id: String,
    val label: String,
    val youngerSampleId: String,
    val olderSampleId: String,
    val relation: String,             // BEFORE（younger 早于 older 的 cal BP 更大）| COEVAL
    val sourceNote: String = ""
)

@Serializable
data class ExclusionDecision(
    val id: String,
    val targetMeasurementId: String,
    val targetKind: String,           // PEAK | WHOLE_RESULT | OFFSET
    val targetPeakIndex: Int? = null,
    val targetCalbpFrom: Double? = null,
    val targetCalbpTo: Double? = null,
    val reason: String,
    val accepted: Boolean,
    val basis: String
)

@Serializable
data class Peak(
    val index: Int,
    val modeCalbp: Double,
    val mass: Double,
    val hpd68: List<CalbpInterval>,
    val hpd95: List<CalbpInterval>,
    val label: String
)

@Serializable
data class CalbpInterval(val fromCalbp: Double, val toCalbp: Double, val probability: Double)

@Serializable
data class IntervalIntegral(
    val fromCalbp: Double,
    val toCalbp: Double,
    val probability: Double,
    val gridStep: Double,
    val trapezoidTerms: Int,
    val algorithmVersion: String
)

@Serializable
data class CalibrationResult(
    val calibrationId: String,
    val measurementId: String,
    val curveVersion: String,
    val offsetId: String,
    val offsetFingerprint: String,
    val algorithmVersion: String,
    val gridStep: Double,
    val grid: List<Double>,
    val density: List<Double>,
    val normalization: Double,
    val peaks: List<Peak>,
    val full95: List<CalbpInterval>,
    val full68: List<CalbpInterval>,
    val status: String,              // CURRENT | STALE | UNKNOWN
    val diagnostics: List<String>,
    val weightedMeanCalbp: Double? = null
)

@Serializable
data class ConstraintVerdict(
    val constraintId: String,
    val feasible: Boolean,
    val status: String,              // FEASIBLE | CONFLICT | UNKNOWN
    val reason: String,
    val candidateAdjustments: List<CandidateAdjustment>,
    val legalPairs: List<String>
)

@Serializable
data class CandidateAdjustment(
    val id: String,
    val kind: String,                // EXCLUDE_PEAK | REVISE_OFFSET
    val targetMeasurementId: String,
    val peakIndex: Int? = null,
    val suggestedOffsetMean: Double? = null,
    val rationale: String,
    val rewritesMeasurement: Boolean = false
)

@Serializable
data class CounterfactualProbe(
    val id: String,
    val name: String,
    val scenario: String,            // 多峰/边界插值/...
    val changedDirectSource: String,
    val affectedScope: List<String>,
    val unaffectedScope: List<String>,
    val judgment: String,
    val adopted: Boolean,
    val basisEventIds: List<String>,
    val expected: String,
    val observed: String
)

@Serializable
data class ProjectInfo(val id: String, val name: String, val createdAtTick: Long, val seed: Long)
