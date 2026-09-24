package workbench

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Append-only domain events. Every derived value carries algorithm version,
 *  parameter fingerprint and direct sources; history is never overwritten. */
@Serializable
sealed class WorkEvent {
    abstract val eventId: String
    abstract val projectId: String
    abstract val at: String

    @Serializable
    @SerialName("project-created")
    data class ProjectCreated(
        override val eventId: String, override val projectId: String, override val at: String,
        val name: String, val narrative: String
    ) : WorkEvent()

    @Serializable
    @SerialName("curve-imported")
    data class CurveImported(
        override val eventId: String, override val projectId: String, override val at: String,
        val curve: CalibrationCurve, val rawBlobId: String
    ) : WorkEvent()

    @Serializable
    @SerialName("sample-imported")
    data class SampleImported(
        override val eventId: String, override val projectId: String, override val at: String,
        val sample: Sample, val rawBlobId: String
    ) : WorkEvent()

    @Serializable
    @SerialName("sample-revised")
    data class SampleRevised(
        override val eventId: String, override val projectId: String, override val at: String,
        val sample: Sample, val rawBlobId: String, val reason: String, val sourceRef: String
    ) : WorkEvent()

    @Serializable
    @SerialName("constraint-imported")
    data class ConstraintImported(
        override val eventId: String, override val projectId: String, override val at: String,
        val constraint: SequenceConstraint
    ) : WorkEvent()

    @Serializable
    @SerialName("calibration-completed")
    data class CalibrationCompleted(
        override val eventId: String, override val projectId: String, override val at: String,
        val run: CalibrationRun
    ) : WorkEvent()

    @Serializable
    @SerialName("sequence-evaluated")
    data class SequenceEvaluated(
        override val eventId: String, override val projectId: String, override val at: String,
        val run: SequenceRun
    ) : WorkEvent()

    @Serializable
    @SerialName("decision-recorded")
    data class DecisionRecorded(
        override val eventId: String, override val projectId: String, override val at: String,
        val decision: Decision
    ) : WorkEvent()

    /** mode: RESOLVE (conflict resolution) or UNDO (undo a previous supersede). */
    @Serializable
    @SerialName("decision-superseded")
    data class DecisionSuperseded(
        override val eventId: String, override val projectId: String, override val at: String,
        val decisionId: String, val reason: String, val mode: String,
        val reactivates: String? = null
    ) : WorkEvent()
}
