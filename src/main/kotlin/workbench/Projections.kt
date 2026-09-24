package workbench

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

data class ProjectState(
    val measurements: Map<String, Measurement> = emptyMap(),
    val measurementHistory: Map<String, List<Measurement>> = emptyMap(),
    val curves: Map<String, CurveData> = emptyMap(),
    val offsets: Map<String, OffsetAssumption> = emptyMap(),
    val constraints: List<SequenceConstraint> = emptyList(),
    val calibrations: Map<String, CalibrationResult> = emptyMap(),
    val latestCalibrationBySample: Map<String, String> = emptyMap(),
    val conflicts: Map<String, Conflict> = emptyMap(),
    val candidates: Map<String, Candidate> = emptyMap(),
    val decisions: List<Decision> = emptyList(),
    val intervalDecisions: Map<String, Decision> = emptyMap(),
    val staleCalibrations: Set<String> = emptySet(),
    val events: List<StoredEvent> = emptyList()
) {
    fun latestCurve(curveId: String): CurveData? =
        curves.values.filter { it.curveId == curveId }.maxByOrNull { it.version }

    fun latestCalibration(sampleId: String): CalibrationResult? =
        latestCalibrationBySample[sampleId]?.let { calibrations[it] }

    fun isStale(cal: CalibrationResult): Boolean {
        val m = measurements[cal.sampleId] ?: return true
        if (m.version != cal.measurementVersion) return true
        val latestCurve = latestCurve(cal.curveId) ?: return true
        if (latestCurve.version != cal.curveVersion) return true
        if (cal.offsetId != null) {
            val o = offsets[cal.offsetId] ?: return true
            if (o.version != cal.offsetVersion) return true
        }
        return false
    }

}

object Projections {
    fun replay(events: List<StoredEvent>): ProjectState {
        val measurements = linkedMapOf<String, Measurement>()
        val measurementHistory = linkedMapOf<String, MutableList<Measurement>>()
        val curves = linkedMapOf<String, CurveData>()
        val offsets = linkedMapOf<String, OffsetAssumption>()
        val constraints = mutableListOf<SequenceConstraint>()
        val calibrations = linkedMapOf<String, CalibrationResult>()
        val latestCalBySample = linkedMapOf<String, String>()
        val conflicts = linkedMapOf<String, Conflict>()
        val candidates = linkedMapOf<String, Candidate>()
        val decisions = mutableListOf<Decision>()
        val decisionEventSeq = mutableMapOf<Long, String>()
        val revokedDecisionIds = mutableSetOf<String>()

        for (e in events) {
            val p = e.payload
            when (e.type) {
                "measurement.imported", "measurement.revised" -> {
                    val m = json.decodeFromJsonElement<Measurement>(p["measurement"]!!)
                    measurements[m.sampleId] = m
                    measurementHistory.getOrPut(m.sampleId) { mutableListOf() }.add(m)
                }
                "curve.registered" -> {
                    val c = json.decodeFromJsonElement<CurveData>(p["curve"]!!)
                    curves[c.key()] = c
                }
                "offset.assumed", "offset.revised" -> {
                    val o = json.decodeFromJsonElement<OffsetAssumption>(p["offset"]!!)
                    offsets[o.offsetId] = o
                }
                "sequence.constraint_added" -> {
                    constraints.add(json.decodeFromJsonElement<SequenceConstraint>(p["constraint"]!!))
                }
                "calibration.computed" -> {
                    val cal = json.decodeFromJsonElement<CalibrationResult>(p["calibration"]!!)
                    calibrations[cal.calibrationId] = cal
                    latestCalBySample[cal.sampleId] = cal.calibrationId
                }
                "conflict.detected" -> {
                    val c = json.decodeFromJsonElement<Conflict>(p["conflict"]!!)
                    conflicts[c.conflictId] = c
                }
                "candidate.generated" -> {
                    val c = json.decodeFromJsonElement<Candidate>(p["candidate"]!!)
                    candidates[c.candidateId] = c
                }
                "decision.made" -> {
                    val d = json.decodeFromJsonElement<Decision>(p["decision"]!!)
                    decisions.add(d)
                    decisionEventSeq[e.seq] = d.decisionId
                }
                "decision.undone" -> {
                    val targetSeq = p["targetEventSeq"]!!.jsonPrimitive.long
                    decisionEventSeq[targetSeq]?.let { revokedDecisionIds.add(it) }
                }
            }
        }

        val effectiveDecisions = decisions.map { if (it.decisionId in revokedDecisionIds) it.copy(revoked = true) else it }
        val intervalDecisions = linkedMapOf<String, Decision>()
        val candidateStatus = linkedMapOf<String, String>()
        for (d in effectiveDecisions) {
            if (d.revoked) continue
            when (d.kind) {
                "interval-accept", "interval-reject" -> intervalDecisions[d.targetId] = d
                "candidate-accept" -> candidateStatus[d.targetId] = "accepted"
                "candidate-reject" -> candidateStatus[d.targetId] = "rejected"
            }
        }
        val finalCandidates = candidates.mapValues { (id, c) ->
            c.copy(status = candidateStatus[id] ?: c.status)
        }

        val partial = ProjectState(
            measurements = measurements,
            measurementHistory = measurementHistory,
            curves = curves,
            offsets = offsets,
            constraints = constraints,
            calibrations = calibrations,
            latestCalibrationBySample = latestCalBySample,
            conflicts = conflicts,
            candidates = finalCandidates,
            decisions = effectiveDecisions,
            intervalDecisions = intervalDecisions,
            events = events
        )
        val stale = calibrations.values.filter { partial.isStale(it) }.map { it.calibrationId }.toSet()
        return partial.copy(staleCalibrations = stale)
    }
}
