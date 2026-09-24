package chrono.state

import chrono.domain.AdjustmentProposal
import chrono.domain.CalibrationCurve
import chrono.domain.CalibrationRun
import chrono.domain.Decision
import chrono.domain.Exclusion
import chrono.domain.Hypothesis
import chrono.domain.Measurement
import chrono.domain.Project
import chrono.domain.Reservoir
import chrono.domain.SequenceConstraint
import chrono.domain.SequenceEvaluation
import chrono.domain.canonicalJson
import chrono.domain.decodeJson
import java.time.Instant

/**
 * Immutable folded view of one project at some point in its event history.
 * Time travel = fold prefix [0..uptoSeq]. Current view = fold everything.
 */
data class ProjectState(
    val project: Project? = null,
    val curves: Map<String, CalibrationCurve> = emptyMap(),
    val curveVersions: List<String> = emptyList(),
    val activeCurveId: String? = null,
    val measurements: List<Measurement> = emptyList(),
    val reservoirs: List<Reservoir> = emptyList(),
    val hypotheses: List<Hypothesis> = emptyList(),
    val constraints: List<SequenceConstraint> = emptyList(),
    val exclusions: List<Exclusion> = emptyList(),
    val runs: List<CalibrationRun> = emptyList(),
    val evaluations: List<SequenceEvaluation> = emptyList(),
    val decisions: List<Decision> = emptyList(),
    val notes: List<ReviewNote> = emptyList(),
    val lastSeq: Long = 0L
) {
    val latestMeasurements: List<Measurement>
        get() = measurements.filter { !it.superseded && !it.unconvertible }

    val latestReservoirs: List<Reservoir>
        get() = reservoirs.filter { !it.superseded }

    fun measurement(id: String): Measurement? =
        measurements.lastOrNull { it.id == id }

    fun reservoir(id: String?): Reservoir? =
        if (id == null) null else reservoirs.lastOrNull { it.id == id }

    fun hypothesis(id: String?): Hypothesis? =
        if (id == null) null else hypotheses.firstOrNull { it.id == id }

    fun constraint(id: String) = constraints.firstOrNull { it.id == id }

    fun runsOf(hypothesisId: String): List<CalibrationRun> =
        runs.filter { it.hypothesisId == hypothesisId }

    fun activeExclusion(targetType: String, targetId: String): Exclusion? =
        exclusions.lastOrNull { it.targetType == targetType && it.targetId == targetId && !it.revoked }

    fun isExcluded(run: CalibrationRun): Boolean =
        activeExclusion("measurement", run.measurementId) != null ||
            activeExclusion("hypothesis", run.hypothesisId) != null

    /**
     * A run is current only while all direct sources are the live versions and the
     * matching hypothesis still exists. Stale runs remain fully viewable, flagged.
     */
    fun isCurrent(run: CalibrationRun): Boolean {
        val curve = curves[run.curveId] ?: return false
        if (run.curveVersion != curve.version) return false
        if (activeCurveId != null && run.curveId != activeCurveId) return false
        val m = measurement(run.measurementId) ?: return false
        if (m.superseded || m.unconvertible) return false
        if (run.reservoirId != null) {
            val r = reservoir(run.reservoirId) ?: return false
            if (r.superseded) return false
        }
        if (hypothesis(run.hypothesisId) == null) return false
        return true
    }

    fun isCurrent(ev: SequenceEvaluation): Boolean {
        val a = runs.firstOrNull { it.runId == ev.runAId } ?: return false
        val b = runs.firstOrNull { it.runId == ev.runBId } ?: return false
        return isCurrent(a) && isCurrent(b)
    }

    fun currentRuns(): List<CalibrationRun> = runs.filter { isCurrent(it) && !isExcluded(it) }

    fun decisionsOfInterval(intervalId: String): List<Decision> =
        decisions.filter { it.intervalId == intervalId }

    fun liveDecisions(): List<Decision> = decisions.filter { !it.revoked && it.supersededBy == null }
}

object Reducer {

    fun fold(events: List<Event>, uptoSeq: Long = Long.MAX_VALUE): ProjectState {
        var s = ProjectState()
        for (e in events) {
            if (e.seq > uptoSeq) break
            s = apply(s, e)
        }
        return s
    }

    fun apply(s: ProjectState, e: Event): ProjectState = when (e.type) {
        "project.created" -> {
            val p = e.payload<ProjectCreated>()
            s.copy(
                project = Project(
                    id = e.projectId, name = p.name, eraSystem = p.eraSystem,
                    eraSource = p.eraSource, createdAt = p.createdAt
                )
            )
        }

        "curve.registered" -> {
            val p = e.payload<CurveRegistered>()
            val curve = CalibrationCurve(
                id = p.id, label = p.label, version = p.version,
                supersedes = p.supersedes, points = p.points, source = p.source
            )
            s.copy(
                curves = s.curves + (p.id to curve),
                curveVersions = (s.curveVersions + p.version).distinct(),
                activeCurveId = s.activeCurveId ?: p.id
            )
        }

        "curve.activated" -> {
            val p = e.payload<CurveActivated>()
            require(s.curves.containsKey(p.curveId)) { "unknown curve ${p.curveId}" }
            s.copy(activeCurveId = p.curveId)
        }

        "measurement.submitted" -> {
            val p = e.payload<MeasurementSubmitted>()
            val marked = if (p.parentId != null)
                s.measurements.map { if (it.id == p.parentId) it.copy(superseded = true) else it }
            else s.measurements
            s.copy(
                measurements = marked + Measurement(
                    id = p.id, sampleCode = p.sampleCode, labCode = p.labCode,
                    rawAge = p.rawAge, rawSigma = p.rawSigma,
                    ageBP = p.ageBP, sigmaBP = p.sigmaBP, material = p.material,
                    unit = p.unit, source = p.source, parentId = p.parentId,
                    superseded = false, unconvertible = p.unconvertible,
                    diagnostic = p.diagnostic
                )
            )
        }

        "reservoir.submitted" -> {
            val p = e.payload<ReservoirSubmitted>()
            val marked = if (p.parentId != null)
                s.reservoirs.map { if (it.id == p.parentId) it.copy(superseded = true) else it }
            else s.reservoirs
            s.copy(
                reservoirs = marked + Reservoir(
                    id = p.id, label = p.label, offset = p.offset,
                    source = p.source, parentId = p.parentId, superseded = false
                )
            )
        }

        "hypothesis.created" -> {
            val p = e.payload<HypothesisCreated>()
            s.copy(hypotheses = s.hypotheses + Hypothesis(p.id, p.measurementId, p.reservoirId))
        }

        "constraint.submitted" -> {
            val p = e.payload<ConstraintSubmitted>()
            s.copy(
                constraints = s.constraints + SequenceConstraint(
                    id = p.id,
                    olderHypothesisId = p.olderHypothesisId,
                    youngerHypothesisId = p.youngerHypothesisId,
                    coevalA = p.coevalA, coevalB = p.coevalB,
                    note = p.note, source = p.source
                )
            )
        }

        "era.changed" -> {
            val p = e.payload<EraSystemChanged>()
            s.copy(project = s.project?.copy(eraSystem = p.eraSystem, eraSource = p.source))
        }

        "exclusion.recorded" -> {
            val p = e.payload<ExclusionRecorded>()
            s.copy(
                exclusions = s.exclusions + Exclusion(
                    p.id, p.targetType, p.targetId, p.reason, p.source
                )
            )
        }

        "exclusion.revoked" -> {
            val p = e.payload<ExclusionRevoked>()
            s.copy(
                exclusions = s.exclusions.map {
                    if (it.id == p.id) it.copy(revoked = true) else it
                }
            )
        }

        "run.recorded" -> {
            val p = e.payload<RunRecorded>()
            // idempotent: replay of identical run keeps first occurrence
            if (s.runs.any { it.runId == p.run.runId }) s
            else s.copy(runs = s.runs + p.run)
        }

        "evaluation.recorded" -> {
            val p = e.payload<EvaluationRecorded>()
            if (s.evaluations.any { it.evaluationId == p.evaluation.evaluationId }) s
            else s.copy(evaluations = s.evaluations + p.evaluation)
        }

        "decision.recorded" -> {
            val p = e.payload<DecisionRecorded>()
            s.copy(
                decisions = s.decisions + Decision(
                    p.id, p.intervalId, p.runId, p.verdict, p.rationale, p.reviewer
                )
            )
        }

        "decision.superseded" -> {
            val p = e.payload<DecisionSuperseded>()
            s.copy(
                decisions = s.decisions.map {
                    if (it.id == p.oldDecisionId) it.copy(supersededBy = p.newDecisionId) else it
                }
            )
        }

        "decision.revoked" -> {
            val p = e.payload<DecisionRevoked>()
            s.copy(
                decisions = s.decisions.map {
                    if (it.id == p.decisionId) it.copy(revoked = true) else it
                }
            )
        }

        "review.note" -> {
            s.copy(notes = s.notes + e.payload())
        }

        else -> throw IllegalArgumentException("unknown event type ${e.type}")
    }

    private inline fun <reified T> Event.payload(): T = payloadJson.decodeJson()
}

@Suppress("unused")
private val instantKeep: Instant? = null
@Suppress("unused")
private val proposalKeep: AdjustmentProposal? = null
