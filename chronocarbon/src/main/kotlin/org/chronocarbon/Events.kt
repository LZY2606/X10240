package org.chronocarbon

import kotlinx.serialization.Serializable

@Serializable
data class EventEnvelope(
    val seq: Long,
    val eventId: String,
    val type: String,
    val at: String,
    val actor: String,
    val payloadJson: String,
    val undone: Boolean = false,
    val undoOfEventId: String? = null
)

@Serializable
sealed class DomainEvent {
    abstract val eventId: String
    abstract val actor: String
    abstract val note: String

    @Serializable
    data class ProjectCreated(
        override val eventId: String,
        override val actor: String,
        override val note: String,
        val projectId: String,
        val name: String
    ) : DomainEvent()

    @Serializable
    data class CurveRegistered(
        override val eventId: String,
        override val actor: String,
        override val note: String,
        val curve: CalibrationCurve,
        val setActive: Boolean
    ) : DomainEvent()

    @Serializable
    data class CurveUpgraded(
        override val eventId: String,
        override val actor: String,
        override val note: String,
        val newCurve: CalibrationCurve,
        val previousVersion: String
    ) : DomainEvent()

    @Serializable
    data class MeasurementSubmitted(
        override val eventId: String,
        override val actor: String,
        override val note: String,
        val measurement: Measurement
    ) : DomainEvent()

    @Serializable
    data class OffsetHypothesisSubmitted(
        override val eventId: String,
        override val actor: String,
        override val note: String,
        val hypothesis: OffsetHypothesis
    ) : DomainEvent()

    @Serializable
    data class ConstraintSubmitted(
        override val eventId: String,
        override val actor: String,
        override val note: String,
        val constraint: SequenceConstraint
    ) : DomainEvent()

    @Serializable
    data class DecisionRecorded(
        override val eventId: String,
        override val actor: String,
        override val note: String,
        val decision: Decision
    ) : DomainEvent()

    @Serializable
    data class CalibrationRequested(
        override val eventId: String,
        override val actor: String,
        override val note: String,
        val request: CalibrationRequest
    ) : DomainEvent()

    @Serializable
    data class SequenceAnalyzed(
        override val eventId: String,
        override val actor: String,
        override val note: String,
        val analysis: SequenceAnalysis
    ) : DomainEvent()

    @Serializable
    data class CandidateResolved(
        override val eventId: String,
        override val actor: String,
        override val note: String,
        val candidateId: String,
        val accept: Boolean,
        val rejection: Rejection? = null,
        val followUpEventIds: List<String> = emptyList()
    ) : DomainEvent()

    @Serializable
    data class EventUndone(
        override val eventId: String,
        override val actor: String,
        override val note: String,
        val targetEventId: String
    ) : DomainEvent()
}
