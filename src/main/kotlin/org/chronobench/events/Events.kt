package org.chronobench.events

import kotlinx.serialization.Serializable
import org.chronobench.domain.CalibrationResult
import org.chronobench.domain.EraSystem
import org.chronobench.domain.ReservoirHypothesis
import org.chronobench.domain.SequenceConstraint
import org.chronobench.domain.SequenceEvaluation

const val EVENT_SCHEMA_VERSION = "event-log-1.0.0"

sealed interface Event {
    val eventId: String
    val projectId: String
    val seq: Int
    val atEpochSecond: Long
    val causationId: String?
    val type: String
}

@Serializable
data class SourceRef(val kind: String, val locator: String, val citation: String)

@Serializable
data class SubmittedValue(val rawText: String, val value: Double, val unit: String, val coordinateDatum: String) {
    init { require(unit.isNotBlank()) { "every domain quantity must carry a unit" } }
}

@Serializable
data class ProjectCreated(
    override val eventId: String,
    override val projectId: String,
    override val seq: Int,
    override val atEpochSecond: Long,
    override val causationId: String? = null,
    val name: String,
    val eraSystem: EraSystem,
    val source: SourceRef,
    val seed: Long
) : Event { override val type get() = "ProjectCreated" }

@Serializable
data class CurveRegistered(
    override val eventId: String,
    override val projectId: String,
    override val seq: Int,
    override val atEpochSecond: Long,
    override val causationId: String? = null,
    val curveId: String,
    val version: String,
    val fingerprint: String,
    val knotCount: Int,
    val minCalBP: Double,
    val maxCalBP: Double,
    val source: SourceRef,
    val supersedes: String?
) : Event { override val type get() = "CurveRegistered" }

@Serializable
data class MeasurementSubmitted(
    override val eventId: String,
    override val projectId: String,
    override val seq: Int,
    override val atEpochSecond: Long,
    override val causationId: String? = null,
    val measurementId: String,
    val parentMeasurementId: String? = null,
    val labCode: String,
    val material: String,
    val age: SubmittedValue,
    val sigma: SubmittedValue,
    val source: SourceRef,
    val note: String = ""
) : Event { override val type get() = "MeasurementSubmitted" }

@Serializable
data class ReservoirHypothesisSubmitted(
    override val eventId: String,
    override val projectId: String,
    override val seq: Int,
    override val atEpochSecond: Long,
    override val causationId: String? = null,
    val hypothesisId: String,
    val parentHypothesisId: String? = null,
    val deltaR: SubmittedValue,
    val deltaRSigma: SubmittedValue,
    val source: SourceRef,
    val label: String
) : Event { override val type get() = "ReservoirHypothesisSubmitted" }

@Serializable
data class SequenceConstraintSubmitted(
    override val eventId: String,
    override val projectId: String,
    override val seq: Int,
    override val atEpochSecond: Long,
    override val causationId: String? = null,
    val constraintId: String,
    val parentConstraintId: String? = null,
    val label: String,
    val measurementIdsOrdered: List<String>,
    val kind: org.chronobench.domain.SequenceKind,
    val minGapYears: Double,
    val source: SourceRef
) : Event { override val type get() = "SequenceConstraintSubmitted" }

@Serializable
data class ExclusionDecisionSubmitted(
    override val eventId: String,
    override val projectId: String,
    override val seq: Int,
    override val atEpochSecond: Long,
    override val causationId: String? = null,
    val decisionId: String,
    val targetMeasurementId: String,
    val excluded: Boolean,
    val reason: String,
    val source: SourceRef
) : Event { override val type get() = "ExclusionDecisionSubmitted" }

@Serializable
data class CalibrationRunRecorded(
    override val eventId: String,
    override val projectId: String,
    override val seq: Int,
    override val atEpochSecond: Long,
    override val causationId: String? = null,
    val runId: String,
    val measurementId: String,
    val hypothesisId: String,
    val curveId: String,
    val curveVersion: String,
    val scenarioTags: List<String>,
    val result: CalibrationResult
) : Event { override val type get() = "CalibrationRunRecorded" }

@Serializable
data class SequenceEvaluationRecorded(
    override val eventId: String,
    override val projectId: String,
    override val seq: Int,
    override val atEpochSecond: Long,
    override val causationId: String? = null,
    val evaluationId: String,
    val constraintId: String,
    val runIds: List<String>,
    val evaluation: SequenceEvaluation
) : Event { override val type get() = "SequenceEvaluationRecorded" }

@Serializable
data class CandidateRuled(
    override val eventId: String,
    override val projectId: String,
    override val seq: Int,
    override val atEpochSecond: Long,
    override val causationId: String? = null,
    val rulingId: String,
    val candidateKind: String,
    val candidateStableId: String,
    val targetRunId: String?,
    val targetIntervalId: String?,
    val decision: String,
    val reason: String,
    val parameterRevision: Map<String, String> = emptyMap()
) : Event { override val type get() = "CandidateRuled" }

@Serializable
data class JudgmentRecorded(
    override val eventId: String,
    override val projectId: String,
    override val seq: Int,
    override val atEpochSecond: Long,
    override val causationId: String? = null,
    val judgmentId: String,
    val code: String,
    val subject: String,
    val holds: Boolean,
    val basis: String,
    val evidenceRefs: List<String>
) : Event { override val type get() = "JudgmentRecorded" }

@Serializable
data class ConflictDetected(
    override val eventId: String,
    override val projectId: String,
    override val seq: Int,
    override val atEpochSecond: Long,
    override val causationId: String? = null,
    val conflictId: String,
    val betweenEventIds: List<String>,
    val subject: String,
    val status: String,
    val note: String
) : Event { override val type get() = "ConflictDetected" }

@Serializable
data class DecisionReversed(
    override val eventId: String,
    override val projectId: String,
    override val seq: Int,
    override val atEpochSecond: Long,
    override val causationId: String? = null,
    val reversalId: String,
    val reversedEventId: String,
    val reason: String
) : Event { override val type get() = "DecisionReversed" }

@Serializable
data class CurveSuperseded(
    override val eventId: String,
    override val projectId: String,
    override val seq: Int,
    override val atEpochSecond: Long,
    override val causationId: String? = null,
    val oldCurveId: String,
    val oldVersion: String,
    val newCurveId: String,
    val newVersion: String
) : Event { override val type get() = "CurveSuperseded" }
