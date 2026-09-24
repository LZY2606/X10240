package org.gsb.carbon.model

import kotlinx.serialization.Serializable

/** 校准后日历概率密度网格点。不压缩为单一平均年。 */
@Serializable
data class CalDensityPoint(
    val calBp: Double,
    /** 归一化后的密度（对 cal BP 积分 = 1） */
    val density: Double,
    /** 归一化前权重 w_i = (sigma_t^2 + sigma_curve^2 + sigma_R^2)^-1/2 exp(...)；积分证据使用 */
    val rawWeight: Double,
)

/** 一个日历区间（多峰时每个 HPD 区间都是一等候选）。 */
@Serializable
data class CalInterval(
    val intervalId: String,
    val loBp: Double,
    val hiBp: Double,
    val probability: Double,
    val containsPeakBp: Double,
    val peakDensity: Double,
    val crossesBceCe: Boolean,
)

/** 一次校准计算（派生值）。带算法版本、参数指纹与直接来源。 */
@Serializable
data class CalibrationRun(
    val runId: String,
    val measurementIdVersion: String,
    val curveVersionId: String,
    val offsetId: String,
    val algorithmVersion: String,
    val paramFingerprint: String,
    val gridStepYears: Double,
    val gridPoints: List<CalDensityPoint>,
    val intervals: List<CalInterval>,
    /** 归一化常数（积分证据可复算） */
    val normalization: Double,
    val createdAtEventSeq: Int,
) {
    val densityByBp: Map<Double, CalDensityPoint> = gridPoints.associateBy { it.calBp }
}

/** 层序评估结果。 */
@Serializable
data class SequenceEvaluation(
    val evaluationId: String,
    val edgeId: String,
    val olderRunId: String,
    val youngerRunId: String,
    /** P(older 比 younger 更老)，[0,1] */
    val orderProbability: Double,
    val consistent: Boolean,
    val conflict: Boolean,
    val algorithmVersion: String,
    val paramFingerprint: String,
    val createdAtEventSeq: Int,
)

/** 层序冲突时产生的候选调整（只建议，不改写测量；无法唯一决定时保留全部合法候选）。 */
@Serializable
data class AdjustmentCandidate(
    val candidateId: String,
    val evaluationId: String,
    val kind: Kind,
    val targetOffsetId: String?,
    val suggestedDeltaR: Double?,
    val orderProbabilityIfApplied: Double,
    val basis: String,
) {
    @Serializable
    enum class Kind { RESERVOIR_REASSIGN, RESERVOIR_TUNE, REVERSE_EDGE_REVIEW, CURVE_RECALC }
}

/** 人工裁定状态机：候选 -> 接受 / 否定 / 参数调整；可撤销。 */
@Serializable
enum class DecisionKind { ACCEPT_INTERVAL, REJECT_INTERVAL, PARAMETER_CHANGE, RESOLVE_SEQUENCE, REVOCATION }

@Serializable
enum class DecisionStatus { PROPOSED, ACCEPTED, REJECTED, SUPERSEDED, REVOKED }

@Serializable
data class Decision(
    val decisionId: String,
    val kind: DecisionKind,
    val status: DecisionStatus,
    val targetRunId: String?,
    val targetIntervalId: String?,
    val targetEvaluationId: String?,
    val targetCandidateId: String?,
    val chosenCurveVersionId: String?,
    val chosenOffsetId: String?,
    val rationale: String,
    val createdBy: String,
    val createdAtEventSeq: Int,
    val parentDecisionId: String? = null,
)
