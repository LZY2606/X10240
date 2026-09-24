package gsb.rcarb.project

import gsb.rcarb.domain.*
import kotlinx.serialization.Serializable

/** 事件溯源投影状态：从事件流确定性重放得到。 */
class ProjectState(val events: List<DossierEvent>) {
    val measurements = LinkedHashMap<String, LinkedHashMap<Int, Measurement>>()
    val curves = LinkedHashMap<String, LinkedHashMap<String, CalibrationCurve>>()
    val reservoirs = LinkedHashMap<String, LinkedHashMap<Int, ReservoirModel>>()
    val constraints = LinkedHashMap<String, LinkedHashMap<Int, SequenceConstraint>>()
    val eraSystems = LinkedHashMap<String, EventPayload.EraSystemSubmitted>()
    val inputs = LinkedHashMap<String, EventPayload.CalibrationInputCreated>()
    val decisions = mutableListOf<EventPayload.DecisionRecorded>()
    val quarantined = mutableListOf<EventPayload.Quarantined>()
    val upgrades = mutableListOf<EventPayload.CurveUpgradeRegistered>()
    var project: EventPayload.ProjectCreated? = null

    init { events.forEach(::apply) }

    private fun apply(e: DossierEvent) {
        when (val p = e.payload) {
            is EventPayload.ProjectCreated -> project = p
            is EventPayload.MeasurementSubmitted ->
                measurements.getOrPut(p.measurement.measurementId) { LinkedHashMap() }[p.measurement.version] = p.measurement
            is EventPayload.CurveSubmitted ->
                curves.getOrPut(p.curve.curveId) { LinkedHashMap() }[p.curve.version] = p.curve
            is EventPayload.ReservoirSubmitted ->
                reservoirs.getOrPut(p.reservoir.reservoirId) { LinkedHashMap() }[p.reservoir.version] = p.reservoir
            is EventPayload.SequenceSubmitted ->
                constraints.getOrPut(p.constraint.constraintId) { LinkedHashMap() }[p.constraint.version] = p.constraint
            is EventPayload.EraSystemSubmitted -> eraSystems[p.eraSystemId] = p
            is EventPayload.Quarantined -> quarantined += p
            is EventPayload.CalibrationInputCreated -> inputs[p.inputId] = p
            is EventPayload.DecisionRecorded -> decisions += p
            is EventPayload.CurveUpgradeRegistered -> upgrades += p
        }
    }

    fun latestMeasurement(id: String): Measurement? = measurements[id]?.maxByOrNull { it.key }?.value
    fun latestCurve(id: String): CalibrationCurve? = curves[id]?.let { map ->
        map.values.sortedWith(compareBy<CalibrationCurve> { semanticVersion(it.version) }.thenBy { it.version }).lastOrNull()
    }
    fun latestReservoir(id: String): ReservoirModel? = reservoirs[id]?.maxByOrNull { it.key }?.value
    fun latestConstraint(id: String): SequenceConstraint? = constraints[id]?.maxByOrNull { it.key }?.value

    private fun semanticVersion(v: String): List<Int> =
        v.split('.').mapNotNull { it.toIntOrNull() }.let { if (it.isEmpty()) listOf(0) else it }

    /** 输入所钉版本是否已被更新版本取代（=> 派生结果过期）。 */
    fun isInputStale(input: EventPayload.CalibrationInputCreated): Boolean {
        val m = latestMeasurement(input.measurementId)
        if (m != null && m.version > input.measurementVersion) return true
        val c = latestCurve(input.curveId)
        if (c != null && c.version != input.curveVersion) return true
        if (input.reservoirId != null) {
            val r = latestReservoir(input.reservoirId)
            if (r != null && input.reservoirVersion != null && r.version > input.reservoirVersion) return true
        }
        return false
    }

    fun currentDecision(inputId: String, intervalId: String): EventPayload.DecisionRecorded? =
        decisions.filter { it.inputId == inputId && it.intervalId == intervalId }
            .sortedBy { d -> events.first { it.payload is EventPayload.DecisionRecorded && (it.payload as EventPayload.DecisionRecorded).decisionId == d.decisionId }.seq }
            .lastOrNull()
}
