package org.chronobench.events

import kotlinx.serialization.Serializable
import org.chronobench.domain.CalibrationCurve
import org.chronobench.domain.CalibrationResult
import org.chronobench.domain.EraSystem
import org.chronobench.domain.ReservoirHypothesis
import org.chronobench.domain.SequenceConstraint
import org.chronobench.domain.SequenceEvaluation

@Serializable
data class MeasurementRecord(
    val measurementId: String,
    val parentMeasurementId: String?,
    val revisionOf: String?,
    val labCode: String,
    val material: String,
    val c14AgeBP: Double,
    val sigmaBP: Double,
    val ageRawText: String,
    val sigmaRawText: String,
    val source: SourceRef,
    val submittedAtSeq: Int,
    val excluded: Boolean = false,
    val excludeReason: String? = null
)

@Serializable
data class ReservoirRecord(
    val hypothesisId: String,
    val parentHypothesisId: String?,
    val label: String,
    val deltaRBP: Double,
    val deltaRSigmaBP: Double,
    val source: SourceRef,
    val submittedAtSeq: Int
) {
    fun hypothesis(): ReservoirHypothesis = ReservoirHypothesis(hypothesisId, label, deltaRBP, deltaRSigmaBP)
}

@Serializable
data class ConstraintRecord(
    val constraintId: String,
    val parentConstraintId: String?,
    val submittedAtSeq: Int,
    val constraint: SequenceConstraint,
    val source: SourceRef
)

@Serializable
data class RunView(
    val runId: String,
    val eventId: String,
    val measurementId: String,
    val hypothesisId: String,
    val curveId: String,
    val curveVersion: String,
    val scenarioTags: List<String>,
    val result: CalibrationResult,
    val recordedAtSeq: Int,
    val staleReasons: List<String>
) {
    val current get() = staleReasons.isEmpty()
}

@Serializable
data class EvaluationView(
    val evaluationId: String,
    val eventId: String,
    val constraintId: String,
    val runIds: List<String>,
    val evaluation: SequenceEvaluation,
    val recordedAtSeq: Int,
    val staleReasons: List<String>
) {
    val current get() = staleReasons.isEmpty()
}

@Serializable
data class RulingView(
    val rulingId: String,
    val eventId: String,
    val candidateKind: String,
    val candidateStableId: String,
    val targetRunId: String?,
    val targetIntervalId: String?,
    val decision: String,
    val reason: String,
    val atSeq: Int,
    val reversed: Boolean
)

@Serializable
data class ConflictView(
    val conflictId: String,
    val eventId: String,
    val betweenEventIds: List<String>,
    val subject: String,
    var status: String,
    val note: String,
    val atSeq: Int
)

@Serializable
data class JudgmentView(
    val judgmentId: String,
    val eventId: String,
    val code: String,
    val subject: String,
    val holds: Boolean,
    val basis: String,
    val evidenceRefs: List<String>,
    val atSeq: Int
)

@Serializable
data class ProjectMeta(
    val projectId: String,
    val name: String,
    val eraSystem: EraSystem,
    val source: SourceRef,
    val seed: Long,
    val createdAtSeq: Int
)

/**
 * Pure left-fold of the immutable event log. Nothing here mutates source measurements;
 * revisions are expressed as new records with parent links. Derived runs carry staleness
 * reasons computed from their direct dependencies only.
 */
@Serializable
data class ProjectState(
    val meta: ProjectMeta? = null,
    val curves: Map<String, CalibrationCurve> = emptyMap(),
    val preferredCurveId: String? = null,
    val preferredCurveVersion: String? = null,
    val measurements: Map<String, MeasurementRecord> = emptyMap(),
    val measurementFamily: Map<String, String> = emptyMap(),
    val reservoirs: Map<String, ReservoirRecord> = emptyMap(),
    val constraints: Map<String, ConstraintRecord> = emptyMap(),
    val runs: Map<String, RunView> = emptyMap(),
    val evaluations: Map<String, EvaluationView> = emptyMap(),
    val rulings: List<RulingView> = emptyList(),
    val judgments: List<JudgmentView> = emptyList(),
    val conflicts: List<ConflictView> = emptyList(),
    val events: List<Event> = emptyList()
) {
    val currentRuns: List<RunView> get() = runs.values.filter { it.current }

    fun run(id: String): RunView? = runs[id]
    fun measurement(id: String): MeasurementRecord? = measurements[id]
    fun reservoir(id: String): ReservoirRecord? = reservoirs[id]
    fun curve(id: String, version: String): CalibrationCurve? = curves["$id@$version"]
    fun preferredCurve(): CalibrationCurve? =
        preferredCurveId?.let { id -> curves.values.firstOrNull { it.id == id && it.version == preferredCurveVersion } }

    fun acceptedIntervalIds(): Set<String> =
        rulings.filter { !it.reversed && it.decision == "ACCEPT" }.mapNotNull { it.targetIntervalId }.toSet()

    fun rejectedIntervalIds(): Set<String> =
        rulings.filter { !it.reversed && it.decision == "REJECT" }.mapNotNull { it.targetIntervalId }.toSet()
}

object Reducer {
    fun apply(state: ProjectState, event: Event, curves: Map<String, CalibrationCurve>): ProjectState {
        val appended = state.copy(events = state.events + event)
        return when (event) {
            is ProjectCreated -> appended.copy(
                meta = ProjectMeta(event.projectId, event.name, event.eraSystem, event.source, event.seed, event.seq)
            )

            is CurveRegistered -> {
                val curve = curves["${event.curveId}@${event.version}"]
                    ?: error("curve ${event.curveId}@${event.version} not available when applying CurveRegistered")
                appended.copy(
                    curves = state.curves + ("${curve.id}@${curve.version}" to curve),
                    preferredCurveId = state.preferredCurveId ?: curve.id,
                    preferredCurveVersion = state.preferredCurveVersion ?: curve.version
                )
            }

            is CurveSuperseded -> appended.copy(
                preferredCurveId = event.newCurveId,
                preferredCurveVersion = event.newVersion
            )

            is MeasurementSubmitted -> {
                val familyRoot = event.parentMeasurementId
                    ?.let { pid -> state.measurementFamily[pid] ?: pid }
                    ?: event.measurementId
                val rec = MeasurementRecord(
                    measurementId = event.measurementId,
                    parentMeasurementId = event.parentMeasurementId,
                    revisionOf = event.parentMeasurementId,
                    labCode = event.labCode,
                    material = event.material,
                    c14AgeBP = event.age.value,
                    sigmaBP = event.sigma.value,
                    ageRawText = event.age.rawText,
                    sigmaRawText = event.sigma.rawText,
                    source = event.source,
                    submittedAtSeq = event.seq
                )
                appended.copy(
                    measurements = state.measurements + (event.measurementId to rec),
                    measurementFamily = state.measurementFamily + (event.measurementId to familyRoot)
                )
            }

            is ReservoirHypothesisSubmitted -> {
                val rec = ReservoirRecord(
                    event.hypothesisId, event.parentHypothesisId, event.label,
                    event.deltaR.value, event.deltaRSigma.value, event.source, event.seq
                )
                appended.copy(reservoirs = state.reservoirs + (event.hypothesisId to rec))
            }

            is SequenceConstraintSubmitted -> {
                val c = SequenceConstraint(
                    event.constraintId, event.label, event.measurementIdsOrdered, event.kind, event.minGapYears
                )
                appended.copy(
                    constraints = state.constraints +
                        (event.constraintId to ConstraintRecord(event.constraintId, event.parentConstraintId, event.seq, c, event.source))
                )
            }

            is ExclusionDecisionSubmitted -> {
                val existing = state.measurements[event.targetMeasurementId]
                appended.copy(
                    measurements = if (existing == null) state.measurements else
                        state.measurements + (event.targetMeasurementId to
                            existing.copy(excluded = event.excluded, excludeReason = if (event.excluded) event.reason else null))
                )
            }

            is CalibrationRunRecorded -> appended.copy(
                runs = state.runs + (event.runId to RunView(
                    runId = event.runId,
                    eventId = event.eventId,
                    measurementId = event.measurementId,
                    hypothesisId = event.hypothesisId,
                    curveId = event.curveId,
                    curveVersion = event.curveVersion,
                    scenarioTags = event.scenarioTags,
                    result = event.result,
                    recordedAtSeq = event.seq,
                    staleReasons = emptyList()
                ))
            )

            is SequenceEvaluationRecorded -> appended.copy(
                evaluations = state.evaluations + (event.evaluationId to EvaluationView(
                    event.evaluationId, event.eventId, event.constraintId, event.runIds,
                    event.evaluation, event.seq, emptyList()
                ))
            )

            is CandidateRuled -> appended.copy(
                rulings = state.rulings + RulingView(
                    event.rulingId, event.eventId, event.candidateKind, event.candidateStableId,
                    event.targetRunId, event.targetIntervalId, event.decision, event.reason, event.seq, reversed = false
                )
            )

            is DecisionReversed -> {
                when {
                    state.rulings.any { it.eventId == event.reversedEventId } -> appended.copy(
                        rulings = state.rulings.map { if (it.eventId == event.reversedEventId) it.copy(reversed = true) else it }
                    )
                    state.events.any { it is ExclusionDecisionSubmitted && it.eventId == event.reversedEventId } -> {
                        val ex = state.events.first { it.eventId == event.reversedEventId } as ExclusionDecisionSubmitted
                        val m = state.measurements[ex.targetMeasurementId]
                        appended.copy(
                            measurements = if (m == null) state.measurements
                            else state.measurements + (m.measurementId to m.copy(excluded = false, excludeReason = null))
                        )
                    }
                    else -> appended
                }
            }

            is JudgmentRecorded -> appended.copy(
                judgments = state.judgments + JudgmentView(
                    event.judgmentId, event.eventId, event.code, event.subject,
                    event.holds, event.basis, event.evidenceRefs, event.seq
                )
            )

            is ConflictDetected -> appended.copy(
                conflicts = state.conflicts + ConflictView(
                    event.conflictId, event.eventId, event.betweenEventIds, event.subject, event.status, event.note, event.seq
                )
            )
        }
    }

    /** Recompute staleness from direct dependencies; must be run after the fold finishes. */
    fun withStaleness(state: ProjectState): ProjectState {
        fun newestMember(familyId: String): Int? =
            state.measurements.values
                .filter { state.measurementFamily[it.measurementId] == familyId }
                .maxOfOrNull { it.submittedAtSeq }

        fun newestReservoirParent(hypothesisId: String): Int {
            var cur = hypothesisId
            var seq = state.reservoirs[hypothesisId]?.submittedAtSeq ?: 0
            while (true) {
                val parent = state.reservoirs[cur]?.parentHypothesisId ?: break
                cur = parent
                seq = maxOf(seq, state.reservoirs[cur]?.submittedAtSeq ?: 0)
            }
            return seq
        }

        val runs = state.runs.mapValues { (_, run) ->
            val reasons = mutableListOf<String>()
            val m = state.measurements[run.measurementId]
            if (m == null) reasons += "MEASUREMENT_MISSING"
            else {
                val family = state.measurementFamily[run.measurementId] ?: run.measurementId
                val newest = newestMember(family)
                if (newest != null && newest > run.recordedAtSeq)
                    reasons += "MEASUREMENT_REVISED@seq$newest"
                if (m.excluded) reasons += "MEASUREMENT_EXCLUDED"
            }
            if (state.reservoirs[run.hypothesisId] == null) reasons += "RESERVOIR_MISSING"
            else if (newestReservoirParent(run.hypothesisId) > run.recordedAtSeq)
                reasons += "RESERVOIR_REVISED"
            val preferredKey = "${state.preferredCurveId}@${state.preferredCurveVersion}"
            val runKey = "${run.curveId}@${run.curveVersion}"
            if (preferredKey != runKey) reasons += "CURVE_PREFERRED_$preferredKey"
            run.copy(staleReasons = reasons)
        }

        val evals = state.evaluations.mapValues { (_, ev) ->
            val memberStale = ev.runIds.flatMap { runs[it]?.staleReasons ?: listOf("RUN_MISSING") }.distinct()
            val constraintNewer = state.constraints[ev.constraintId]?.submittedAtSeq?.let { it > ev.recordedAtSeq } == true
            ev.copy(staleReasons = memberStale + if (constraintNewer) listOf("CONSTRAINT_REVISED") else emptyList())
        }
        return state.copy(runs = runs, evaluations = evals)
    }
}

fun foldEvents(events: List<Event>, curves: Map<String, CalibrationCurve>): ProjectState {
    val raw = events.fold(ProjectState()) { s, e -> Reducer.apply(s, e, curves) }
    return Reducer.withStaleness(raw)
}
