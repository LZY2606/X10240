package org.chronocarbon

import kotlinx.serialization.Serializable

class ProjectState {
    var projectId: String = ""
    var name: String = ""
    val curves = LinkedHashMap<String, CalibrationCurve>() // version -> curve
    var activeCurveVersion: String? = null
    val measurements = LinkedHashMap<String, Measurement>()
    val hypotheses = LinkedHashMap<String, OffsetHypothesis>()
    val constraints = LinkedHashMap<String, SequenceConstraint>()
    val decisions = LinkedHashMap<String, Decision>()
    val analyses = LinkedHashMap<String, SequenceAnalysis>()
    val candidates = LinkedHashMap<String, AdjustmentCandidate>()
    val eventLog = ArrayList<EventEnvelope>()
    val undoneEventIds = HashSet<String>()

    fun activeCurve(): CalibrationCurve? = activeCurveVersion?.let { curves[it] }
    fun measurement(id: String): Measurement? = measurements[id]
    fun activeHypothesis(measurementId: String): OffsetHypothesis? =
        hypotheses.values.lastOrNull { it.measurementId == measurementId && it.active && !it.superseded }

    fun isExcluded(measurementId: String): Boolean =
        decisions.values.any { it.targetMeasurementId == measurementId && it.exclude && it.active && !it.undone }

    fun activeConstraints(): List<SequenceConstraint> = constraints.values.filter { it.active }

    /** Latest member of a revision family by parent chain. */
    fun currentMeasurement(id: String): Measurement? {
        val direct = measurements[id]
        val children = measurements.values.filter { it.parentId == id }
        if (children.isEmpty()) return direct
        return children.maxByOrNull { child -> eventSeqOf(child.id) } ?: direct
    }

    private fun eventSeqOf(measurementId: String): Long =
        eventLog.indexOfLast { envelope ->
            if (envelope.eventId in undoneEventIds) false
            else (runCatching { JsonCodec.decode<DomainEvent>(envelope.payloadJson) }.getOrNull()
                as? DomainEvent.MeasurementSubmitted)?.measurement?.id == measurementId
        }.toLong()
}

class Project(val state: ProjectState = ProjectState()) {

    companion object {
        fun reduce(events: List<EventEnvelope>): Project {
            val project = Project()
            for (envelope in events) project.applyEnvelope(envelope)
            return project
        }
    }

    fun applyEnvelope(envelope: EventEnvelope) {
        state.eventLog += envelope
        if (envelope.undone) envelope.undoOfEventId?.let { state.undoneEventIds += it }
        if (envelope.eventId in state.undoneEventIds) return
        val payload = JsonCodec.decode<DomainEvent>(envelope.payloadJson)
        when (payload) {
            is DomainEvent.ProjectCreated -> {
                state.projectId = payload.projectId
                state.name = payload.name
            }
            is DomainEvent.CurveRegistered -> {
                state.curves[payload.curve.version] = payload.curve
                if (payload.setActive) state.activeCurveVersion = payload.curve.version
            }
            is DomainEvent.CurveUpgraded -> {
                state.curves[payload.newCurve.version] = payload.newCurve
                state.activeCurveVersion = payload.newCurve.version
            }
            is DomainEvent.MeasurementSubmitted -> {
                payload.measurement.parentId?.let { parentId ->
                    state.measurements[parentId]?.let { state.measurements[parentId] = it.copy(superseded = true) }
                }
                state.measurements[payload.measurement.id] = payload.measurement
            }
            is DomainEvent.OffsetHypothesisSubmitted -> {
                val h = payload.hypothesis
                if (h.parentId != null) state.hypotheses[h.parentId]?.let { state.hypotheses[it.id] = it.copy(superseded = true, active = false) }
                state.hypotheses[h.id] = h
            }
            is DomainEvent.ConstraintSubmitted -> state.constraints[payload.constraint.id] = payload.constraint
            is DomainEvent.DecisionRecorded -> {
                val d = payload.decision
                if (d.parentDecisionId != null) state.decisions[d.parentDecisionId]?.let {
                    state.decisions[it.id] = it.copy(active = false)
                }
                state.decisions[d.id] = d
            }
            is DomainEvent.SequenceAnalyzed -> {
                state.analyses[payload.analysis.id] = payload.analysis
                payload.analysis.candidateIds.forEach { /* candidates stored elsewhere */ }
            }
            is DomainEvent.CandidateResolved -> state.candidates[payload.candidateId]?.let { existing ->
                state.candidates[existing.id] = existing.copy(
                    status = if (payload.accept) "accepted" else "rejected",
                    decidedByEventId = payload.eventId,
                    rejection = payload.rejection
                )
            }
            is DomainEvent.CalibrationRequested -> Unit
            is DomainEvent.EventUndone -> {
                state.undoneEventIds += payload.targetEventId
                reapplyAfterUndo(payload.targetEventId)
            }
        }
    }

    /** Reverse the visible effect of an undone event without deleting history. */
    private fun reapplyAfterUndo(targetEventId: String) {
        val targetEnvelope = state.eventLog.firstOrNull { it.eventId == targetEventId } ?: return
        val payload = JsonCodec.decode<DomainEvent>(targetEnvelope.payloadJson)
        when (payload) {
            is DomainEvent.ConstraintSubmitted -> state.constraints[payload.constraint.id]?.let {
                state.constraints[it.id] = it.copy(active = false)
            }
            is DomainEvent.DecisionRecorded -> state.decisions[payload.decision.id]?.let {
                state.decisions[it.id] = it.copy(active = false, undone = true)
            }
            is DomainEvent.OffsetHypothesisSubmitted -> state.hypotheses[payload.hypothesis.id]?.let {
                state.hypotheses[it.id] = it.copy(active = false)
            }
            is DomainEvent.MeasurementSubmitted -> state.measurements[payload.measurement.id]?.let {
                state.measurements[it.id] = it.copy(superseded = true)
            }
            else -> Unit
        }
    }

    /**
     * Pairwise probability P(bp(older) > bp(younger)) from two normalized calibration results.
     * Deterministic grid integration; no Monte Carlo, so it is fully reproducible.
     */
    fun orderingProbability(older: CalibrationResult, younger: CalibrationResult): Double {
        val o = older.cells.associateBy { it.calBp }
        val y = younger.cells.associateBy { it.calBp }
        val common = o.keys.intersect(y.keys).sorted()
        var probability = 0.0
        for (ob in common) {
            for (yb in common) {
                if (ob > yb) probability += o.getValue(ob).mass * y.getValue(yb).mass
            }
        }
        return probability
    }
}
