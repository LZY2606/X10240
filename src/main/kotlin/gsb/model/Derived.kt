package gsb.model

import kotlinx.serialization.Serializable

/** 算法版本 + 参数指纹 + 直接来源：每个派生值都带这三件套。 */
@Serializable
data class Provenance(
    val algorithmVersion: String,
    val paramFingerprint: String,
    val seed: Long,
    val sources: List<String>,          // 直接来源（InputVersion/Derived id）
    val curveVersion: String,
    val offsetSignature: String,
    val computedAt: String,
    val eventId: String
)

/** 校准密度网格。bin 中心用天文纪年表示，bin 宽 1 年。 */
@Serializable
data class Density(
    val gridMinYear: Int,
    val gridMaxYear: Int,
    val binWidth: Double,
    /** 密度（已归一化，sum*binWidth=1），索引对应 gridMinYear+i。 */
    val p: List<Double>,
    val normalized: Boolean,
    val normalizationSum: Double
) {
    fun indexOfYear(year: Int): Int = year - gridMinYear
    fun yearAt(index: Int): Int = gridMinYear + index
}

/** 一个日历区间（通常是 HPD/众峰），保留并列多区间。 */
@Serializable
data class DateInterval(
    val id: String,
    val loYear: Int,
    val hiYear: Int,
    val probability: Double,
    val peakYear: Int,
    val modeIndex: Int,
    /** 该区间的积分证据：逐项 trapezoid 加总，可展开复核。 */
    val integralTerms: List<IntegralTerm>,
    val label: String
)

@Serializable
data class IntegralTerm(val binCenterYear: Int, val density: Double, val width: Double, val contribution: Double)

/** 校准结果（单个测量在某曲线/偏移/年代系统下）。 */
@Serializable
data class CalibrationResult(
    val id: String,
    val measurementNodeId: String,
    val density: Density,
    val intervals: List<DateInterval>,
    val provenance: Provenance,
    /** 状态：CURRENT 可当当前结论；STALE 仅可查看。 */
    val status: ArtifactStatus,
    val staleReason: String? = null,
    /** 边界诊断：是否贴到曲线端点（边界插值）、是否 BCE/CE 跨界。 */
    val boundary: BoundaryInfo
)

@Serializable
data class BoundaryInfo(
    val touchesCurveLower: Boolean,
    val touchesCurveUpper: Boolean,
    val crossesBceCe: Boolean,
    val withinCurve: Boolean,
    val messages: List<String>
)

enum class ArtifactStatus { CURRENT, STALE }

/** 层序求解后的一个候选（全部合法候选，非唯一时都保留）。 */
@Serializable
data class SequenceCandidate(
    val id: String,
    val constraintId: String,
    /** 调整建议；绝不改写测量，只移动/缩放区间概率。 */
    val kind: String,                 // TRIM_OLDER / TRIM_YOUNGER / REJECT_MEASUREMENT / NO_CHANGE / CONFLICT
    val targetMeasurementId: String,
    val loYear: Int?,
    val hiYear: Int?,
    val feasibility: Double,          // 0..1；低于阈值判冲突
    val rationale: String,
    val sources: List<String>,
    val status: CandidateStatus
)

enum class CandidateStatus { PROPOSED, ACCEPTED, REJECTED, SUPERSEDED }

/** 层序建模结果。 */
@Serializable
data class SequenceResult(
    val id: String,
    val constraintNodeId: String,
    val candidates: List<SequenceCandidate>,
    val hasConflict: Boolean,
    val conflictDetails: List<String>,
    val provenance: Provenance,
    val status: ArtifactStatus,
    val staleReason: String? = null
)
