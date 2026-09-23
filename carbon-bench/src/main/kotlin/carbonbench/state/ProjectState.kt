package carbonbench.state

import carbonbench.domain.CalibrationResult
import carbonbench.domain.Event
import carbonbench.domain.ExclusionDecision
import carbonbench.domain.IntervalDecision
import carbonbench.domain.MeasurementVersion
import carbonbench.domain.Project
import carbonbench.domain.ReservoirOffset
import carbonbench.domain.SequenceAnalysis
import carbonbench.domain.SequenceConstraint
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

object EventTypes {
    const val PROJECT_CREATED = "project-created"
    const val CURVE_REGISTERED = "curve-registered"
    const val MEASUREMENT_SUBMITTED = "measurement-submitted"
    const val MEASUREMENT_REVISED = "measurement-revised"
    const val OFFSET_RECORDED = "offset-recorded"
    const val OFFSET_REVISED = "offset-revised"
    const val CONSTRAINT_ASSERTED = "constraint-asserted"
    const val CONSTRAINT_RETRACTED = "constraint-retracted"
    const val EXCLUSION_DECIDED = "exclusion-decided"
    const val EXCLUSION_RETRACTED = "exclusion-retracted"
    const val CALIBRATION_COMPUTED = "calibration-computed"
    const val DERIVED_INVALIDATED = "derived-invalidated"
    const val SEQUENCE_COMPUTED = "sequence-computed"
    const val INTERVAL_DECIDED = "interval-decided"
    const val DECISION_RETRACTED = "decision-retracted"
    const val CANDIDATE_ADJUSTMENT_ACCEPTED = "candidate-adjustment-accepted"
    const val CALIBRATION_VERIFIED = "calibration-verified"
    const val INPUT_DIAGNOSTIC = "input-diagnostic"
}

data class Snapshot(
    val eventSeq: Long,
    val atEventId: String,
    val project: Project?,
    val measurements: List<MeasurementVersion>,
    val offsets: List<ReservoirOffset>,
    val constraints: List<SequenceConstraint>,
    val exclusions: List<ExclusionDecision>,
    val calibrations: List<CalibrationResult>,
    val analyses: List<SequenceAnalysis>,
    val intervalDecisions: List<IntervalDecision>
) {
    fun activeMeasurement(labCode: String): MeasurementVersion? =
        measurements.filter { it.active && it.labCode == labCode }.maxByOrNull { it.version }

    fun activeOffset(id: String): ReservoirOffset? = offsets.firstOrNull { it.active && it.id == id }

    fun activeConstraints(): List<SequenceConstraint> = constraints.filter { it.status == "asserted" }

    fun activeExclusions(): List<ExclusionDecision> = exclusions.filter { it.status == "active" }

    fun isExcluded(labCode: String): Boolean =
        activeExclusions().any { it.labCode == labCode && it.excluded }

    fun calibration(id: String): CalibrationResult? = calibrations.firstOrNull { it.id == id }
    fun analysis(id: String): SequenceAnalysis? = analyses.firstOrNull { it.id == id }
}

/** 纯函数事件回放。 */
object ProjectState {

    fun replay(events: List<Event>): Snapshot {
        val sorted = events.sortedWith(compareBy({ it.seq }, { it.id }))
        var project: Project? = null
        val measurements = LinkedHashMap<String, MeasurementVersion>()
        val offsets = LinkedHashMap<String, ReservoirOffset>()
        val constraints = LinkedHashMap<String, SequenceConstraint>()
        val exclusions = LinkedHashMap<String, ExclusionDecision>()
        val calibrations = LinkedHashMap<String, CalibrationResult>()
        val analyses = LinkedHashMap<String, SequenceAnalysis>()
        val intervalDecisions = LinkedHashMap<String, IntervalDecision>()

        var lastEventId = ""
        for (e in sorted) {
            when (e.type) {
                EventTypes.PROJECT_CREATED ->
                    project = Codec.json.decodeFromJsonElement(Project.serializer(), e.payload)

                EventTypes.CURVE_REGISTERED, EventTypes.CANDIDATE_ADJUSTMENT_ACCEPTED,
                EventTypes.CALIBRATION_VERIFIED, EventTypes.INPUT_DIAGNOSTIC -> Unit

                EventTypes.MEASUREMENT_SUBMITTED, EventTypes.MEASUREMENT_REVISED -> {
                    val mv = Codec.json.decodeFromJsonElement(MeasurementVersion.serializer(), e.payload)
                    if (e.type == EventTypes.MEASUREMENT_REVISED) {
                        mv.parentId?.let { pid ->
                            measurements[pid]?.let { measurements[pid] = it.copy(active = false) }
                            staleCalibrations(calibrations, analyses, setOf(pid), null,
                                "测量 ${mv.labCode} 的父版本 $pid 被新版本 ${mv.id} 取代")
                        }
                    }
                    measurements[mv.id] = mv
                }

                EventTypes.OFFSET_RECORDED, EventTypes.OFFSET_REVISED -> {
                    val off = Codec.json.decodeFromJsonElement(ReservoirOffset.serializer(), e.payload)
                    if (e.type == EventTypes.OFFSET_REVISED) {
                        off.parentId?.let { pid ->
                            offsets[pid]?.let { offsets[pid] = it.copy(active = false) }
                            staleCalibrations(calibrations, analyses, null, setOf(pid),
                                "偏移分布 ${off.label} 的父版本 $pid 被新版本 ${off.id} 取代")
                        }
                    }
                    offsets[off.id] = off
                }

                EventTypes.CONSTRAINT_ASSERTED -> {
                    val c = Codec.json.decodeFromJsonElement(SequenceConstraint.serializer(), e.payload)
                    constraints[c.id] = c
                    staleAllAnalyses(analyses, "层序约束集合变化（新增 ${c.id}）")
                }

                EventTypes.CONSTRAINT_RETRACTED -> {
                    val cid = (e.payload["constraintId"] as JsonPrimitive).content
                    constraints[cid]?.let { constraints[cid] = it.copy(status = "retracted") }
                    staleAllAnalyses(analyses, "层序约束 $cid 被撤销")
                }

                EventTypes.EXCLUSION_DECIDED, EventTypes.EXCLUSION_RETRACTED -> {
                    val d = Codec.json.decodeFromJsonElement(ExclusionDecision.serializer(), e.payload)
                    exclusions[d.id] = d
                }

                EventTypes.CALIBRATION_COMPUTED -> {
                    val r = Codec.json.decodeFromJsonElement(CalibrationResult.serializer(), e.payload)
                    calibrations[r.id] = r
                }

                EventTypes.DERIVED_INVALIDATED -> {
                    val calId = (e.payload["calibrationId"] as JsonPrimitive).content
                    val reason = (e.payload["reason"] as JsonPrimitive).content
                    calibrations[calId]?.let { calibrations[calId] = it.copy(status = "stale", staleReason = reason) }
                    staleCalibrations(calibrations, analyses, setOf(calId), null, reason, alreadyStaled = true)
                }

                EventTypes.SEQUENCE_COMPUTED -> {
                    val a = Codec.json.decodeFromJsonElement(SequenceAnalysis.serializer(), e.payload)
                    analyses[a.id] = a
                }

                EventTypes.INTERVAL_DECIDED, EventTypes.DECISION_RETRACTED -> {
                    val d = Codec.json.decodeFromJsonElement(IntervalDecision.serializer(), e.payload)
                    intervalDecisions[d.id] = d
                }
            }
            lastEventId = e.id
        }

        return Snapshot(
            eventSeq = sorted.lastOrNull()?.seq ?: 0L,
            atEventId = lastEventId,
            project = project,
            measurements = measurements.values.toList(),
            offsets = offsets.values.toList(),
            constraints = constraints.values.toList(),
            exclusions = exclusions.values.toList(),
            calibrations = calibrations.values.toList(),
            analyses = analyses.values.toList(),
            intervalDecisions = intervalDecisions.values.toList()
        )
    }

    private fun staleCalibrations(
        calibrations: MutableMap<String, CalibrationResult>,
        analyses: MutableMap<String, SequenceAnalysis>,
        measurementIds: Set<String>?,
        offsetIds: Set<String>?,
        reason: String,
        alreadyStaled: Boolean = false
    ) {
        val hit = mutableSetOf<String>()
        if (!alreadyStaled) {
            for ((id, c) in calibrations) {
                val matches = (measurementIds != null && c.measurementId in measurementIds) ||
                        (offsetIds != null && c.offsetId != null && c.offsetId in offsetIds)
                if (matches && c.status == "current") {
                    calibrations[id] = c.copy(status = "stale", staleReason = reason)
                    hit.add(id)
                }
            }
        } else {
            hit.addAll(measurementIds.orEmpty())
        }
        for ((aid, a) in analyses) {
            if (a.status == "current" && hit.any { it in a.calibrationIds }) {
                analyses[aid] = a.copy(status = "stale", staleReason = reason)
            }
        }
    }

    private fun staleAllAnalyses(analyses: MutableMap<String, SequenceAnalysis>, reason: String) {
        for ((aid, a) in analyses) {
            if (a.status == "current") analyses[aid] = a.copy(status = "stale", staleReason = reason)
        }
    }
}
