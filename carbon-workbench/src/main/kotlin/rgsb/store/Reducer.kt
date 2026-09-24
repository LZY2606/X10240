package rgsb.store

import rgsb.model.CalibrationResult
import rgsb.model.CalibrationCurve
import rgsb.model.DecisionRecord
import rgsb.model.EventEnvelope
import rgsb.model.ExclusionSpec
import rgsb.model.OffsetSpec
import rgsb.model.RawMeasurement
import rgsb.model.SequenceSpec
import rgsb.model.SequenceVerdict
import rgsb.model.ViewSnapshot
import rgsb.model.EraSystem

/**
 * Fold an event stream into the state visible at a point in history.
 *
 * Input revisions accumulate with parent links; consumers prefer the newest visible
 * version (the immutable history stays available through its own events).
 * Results are never deleted: when a direct source changes, a MarkStale event flags them
 * so stale values stay viewable but can never masquerade as current.
 */
object Reducer {

    data class State(
        var projectId: String = "",
        var name: String = "",
        var seed: Long = 0L,
        var era: EraSystem? = null,
        val curves: MutableMap<String, CalibrationCurve> = linkedMapOf(),
        val measurements: MutableMap<String, RawMeasurement> = linkedMapOf(),
        val offsets: MutableMap<String, OffsetSpec> = linkedMapOf(),
        val sequences: MutableMap<String, SequenceSpec> = linkedMapOf(),
        val exclusions: MutableMap<String, ExclusionSpec> = linkedMapOf(),
        val results: MutableMap<String, CalibrationResult> = linkedMapOf(),
        val verdicts: MutableList<SequenceVerdict> = mutableListOf(),
        val decisions: MutableMap<String, DecisionRecord> = linkedMapOf(),
        val tips: MutableMap<String, String> = mutableMapOf(),
    )

    fun fold(events: List<EventEnvelope>, tips: Map<String, String>, viewEventId: String): State {
        val s = State(tips = tips.toMutableMap())
        for (e in events) {
            when (val p = e.payload) {
                is rgsb.model.EventPayload.ProjectCreated -> {
                    s.projectId = p.projectId; s.name = p.name; s.seed = p.seed
                }
                is rgsb.model.EventPayload.EraDefined -> s.era = p.era
                is rgsb.model.EventPayload.CurveInstalled -> s.curves[p.curve.id] = latestVersion(s.curves[p.curve.id], p.curve)
                is rgsb.model.EventPayload.MeasurementSubmitted -> s.measurements[p.measurement.id] = newestRevision(s.measurements[p.measurement.id], p.measurement)
                is rgsb.model.EventPayload.OffsetSubmitted -> s.offsets[p.offset.id] = newestOffset(s.offsets[p.offset.id], p.offset)
                is rgsb.model.EventPayload.SequenceSubmitted -> s.sequences[p.sequence.id] = p.sequence
                is rgsb.model.EventPayload.ExclusionSubmitted -> s.exclusions[p.exclusion.id] = p.exclusion
                is rgsb.model.EventPayload.ComputationProduced -> s.results[p.result.resultId] = p.result
                is rgsb.model.EventPayload.SequenceCheckProduced -> {
                    s.verdicts.clear(); s.verdicts.addAll(p.verdicts)
                }
                is rgsb.model.EventPayload.DecisionMade -> s.decisions[p.decision.decisionId] = p.decision
                is rgsb.model.EventPayload.DecisionRevoked -> {
                    s.decisions[p.decisionId]?.let { s.decisions[p.decisionId] = it.copy(supersededBy = e.eventId) }
                }
                is rgsb.model.EventPayload.MarkStale -> {
                    for (id in p.resultIds) s.results[id]?.let { s.results[id] = it.copy(status = "STALE") }
                }
                is rgsb.model.EventPayload.Annotation -> Unit
            }
        }
        return s
    }

    private fun newestRevision(existing: RawMeasurement?, incoming: RawMeasurement): RawMeasurement {
        if (existing == null) return incoming
        return if (incoming.rev > existing.rev) incoming else existing
    }

    private fun newestOffset(existing: OffsetSpec?, incoming: OffsetSpec): OffsetSpec {
        if (existing == null) return incoming
        return if (incoming.rev > existing.rev) incoming else existing
    }

    private fun latestVersion(existing: CalibrationCurve?, incoming: CalibrationCurve): CalibrationCurve {
        if (existing == null) return incoming
        return if (incoming.version > existing.version || incoming.parentVersion != null) incoming else existing
    }

    fun snapshot(events: List<EventEnvelope>, tips: Map<String, String>, viewEventId: String, branch: String): ViewSnapshot {
        val s = fold(events, tips, viewEventId)
        return ViewSnapshot(
            projectId = s.projectId,
            viewEventId = viewEventId,
            asOfVirtualTime = events.lastOrNull { it.eventId == viewEventId }?.virtualTime
                ?: events.lastOrNull()?.virtualTime ?: "",
            measurements = s.measurements.values.toList(),
            curves = s.curves.values.toList(),
            offsets = s.offsets.values.toList(),
            era = s.era,
            sequences = s.sequences.values.toList(),
            exclusions = s.exclusions.values.toList(),
            results = s.results.values.toList(),
            sequenceVerdicts = s.verdicts.toList(),
            decisions = s.decisions.values.toList(),
            events = events,
            tips = s.tips,
        )
    }
}
