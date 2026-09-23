package gsb.domain

import kotlinx.serialization.Serializable

/**
 * 事件溯源：原始输入一经提交不可覆盖；
 * 修订走 Superseded* 事件并带 parentId；撤销不删除事件，而是追加补偿事件。
 * 派生结果（校准/层序分析）也作为带来源的事件落库，输入变化时仅被标记 STALE。
 */
@Serializable
sealed interface ProjectEvent {
    val seq: Int
    val at: String
    val actor: String
}

@Serializable
data class ProjectCreated(
    override val seq: Int,
    val projectId: String,
    val name: String,
    val casePackId: String?,
    val importedSnapshotFp: String?,
    override val at: String,
    override val actor: String
) : ProjectEvent

@Serializable
data class MeasurementSubmitted(
    override val seq: Int, val measurement: Measurement,
    override val at: String, override val actor: String
) : ProjectEvent

@Serializable
data class CurveRegistered(
    override val seq: Int, val curve: CalibrationCurve,
    override val at: String, override val actor: String
) : ProjectEvent

@Serializable
data class CurveSuperseded(
    override val seq: Int, val oldCurveId: String, val newCurve: CalibrationCurve,
    override val at: String, override val actor: String
) : ProjectEvent

@Serializable
data class OffsetSubmitted(
    override val seq: Int, val offset: OffsetAssumption,
    override val at: String, override val actor: String
) : ProjectEvent

@Serializable
data class EraSelected(
    override val seq: Int, val era: EraSystem,
    override val at: String, override val actor: String
) : ProjectEvent

@Serializable
data class SequenceSubmitted(
    override val seq: Int, val constraint: SequenceConstraint,
    override val at: String, override val actor: String
) : ProjectEvent

@Serializable
data class SequenceRetracted(
    override val seq: Int, val constraintId: String,
    override val at: String, override val actor: String
) : ProjectEvent

@Serializable
data class ExclusionRecorded(
    override val seq: Int, val exclusion: ExclusionDecision,
    override val at: String, override val actor: String
) : ProjectEvent

@Serializable
data class ExclusionReverted(
    override val seq: Int, val exclusionId: String,
    override val at: String, override val actor: String
) : ProjectEvent

@Serializable
data class CalibrationDerived(
    override val seq: Int, val run: CalibrationRun,
    override val at: String, override val actor: String
) : ProjectEvent

@Serializable
data class RunMarkedStale(
    override val seq: Int, val runId: String, val reason: String,
    override val at: String, override val actor: String
) : ProjectEvent

@Serializable
data class SequenceAnalyzed(
    override val seq: Int, val analysis: SequenceAnalysis,
    override val at: String, override val actor: String
) : ProjectEvent

@Serializable
data class SequenceAnalysisStale(
    override val seq: Int, val analysisId: String, val reason: String,
    override val at: String, override val actor: String
) : ProjectEvent

@Serializable
data class DecisionRecorded(
    override val seq: Int, val decision: Decision,
    override val at: String, override val actor: String
) : ProjectEvent

@Serializable
data class DecisionReverted(
    override val seq: Int, val decisionId: String,
    override val at: String, override val actor: String
) : ProjectEvent

@Serializable
data class ConflictFlagged(
    override val seq: Int, val decisionId: String, val conflictsWith: List<String>, val reason: String,
    override val at: String, override val actor: String
) : ProjectEvent
