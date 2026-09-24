package org.chrono.domain

/**
 * 投影状态：回放到某 seq 得到的不可变快照内容。
 * 曲线按 (id, version) 存放；测量按 id 存放最新版但保留版本链；约束与决定全保留。
 */
data class ProjectState(
    val projectId: String? = null,
    val projectName: String? = null,
    val calendarSystem: String? = null,
    val curves: Map<String, Map<String, CalibrationCurve>> = emptyMap(),
    val measurementChain: Map<String, List<Measurement>> = emptyMap(),
    val edges: Map<String, SequenceEdge> = emptyMap(),
    val exclusions: Map<String, ExclusionDecision> = emptyMap(), // measurementId -> 最新决定
    val adjudications: Map<String, List<AdjudicationRecord>> = emptyMap(), // edgeId
    val restrictions: Map<String, Restriction> = emptyMap(), // measurementId
    val pins: Map<String, Pair<String, Int>> = emptyMap()    // measurementId -> (curveVersion, measurementVersion)
) {
    fun latestMeasurements(): List<Measurement> = measurementChain.values.map { it.last() }
    fun measurement(id: String): Measurement? = measurementChain[id]?.last()
    fun curveFor(m: Measurement): CalibrationCurve? {
        val pinnedVersion = pins[m.measurementId]?.takeIf { it.second == m.version }?.first
        val v = pinnedVersion ?: m.curveVersion
        return curves[m.curveId]?.get(v)
    }
    fun allCurveVersions(): List<CalibrationCurve> = curves.values.flatMap { it.values }
}

data class AdjudicationRecord(
    val eventSeq: Long, val edgeId: String, val accepted: Boolean,
    val adjustmentKind: String, val targetMeasurementId: String,
    val payloadJson: String, val rejectReason: String?, val decidedAt: String
)

data class Restriction(val measurementId: String, val fromAstro: Double, val toAstro: Double, val originEdgeId: String)

object StateProjection {
    fun apply(state: ProjectState, e: DomainEvent): ProjectState = when (e) {
        is DomainEvent.ProjectCreated -> state.copy(projectId = e.projectId, projectName = e.projectName, calendarSystem = e.calendarSystem)

        is DomainEvent.CurveImported -> state.copy(
            curves = state.curves + (e.curve.curveId to ((state.curves[e.curve.curveId] ?: emptyMap()) + (e.curve.version to e.curve)))
        )
        is DomainEvent.CurveUpgraded -> state.copy(
            curves = state.curves + (e.curve.curveId to ((state.curves[e.curve.curveId] ?: emptyMap()) + (e.curve.version to e.curve)))
        )

        is DomainEvent.MeasurementSubmitted -> {
            val chain = (state.measurementChain[e.measurement.measurementId] ?: emptyList()) + e.measurement
            state.copy(measurementChain = state.measurementChain + (e.measurement.measurementId to chain))
        }
        is DomainEvent.MeasurementRevised -> {
            val prev = state.measurementChain[e.measurement.measurementId] ?: error("cannot revise unknown measurement ${e.measurement.measurementId}")
            require(e.measurement.version == prev.last().version + 1) { "measurement revision must increment version" }
            require(e.measurement.parentVersion == prev.last().version) { "revision parent must point at previous version" }
            state.copy(measurementChain = state.measurementChain + (e.measurement.measurementId to (prev + e.measurement)))
        }

        is DomainEvent.SequenceDeclared -> state.copy(edges = state.edges + (e.edge.edgeId to e.edge))

        is DomainEvent.ExclusionRecorded ->
            state.copy(exclusions = state.exclusions + (e.decision.measurementId to e.decision))
        is DomainEvent.ExclusionReversed ->
            state.copy(exclusions = state.exclusions + (e.decision.measurementId to e.decision))

        is DomainEvent.ConflictAdjudicated -> {
            val rec = AdjudicationRecord(-1L, e.edgeId, e.accepted, e.adjustmentKind, e.targetMeasurementId, e.payloadJson, e.rejectReason, e.occurredAt)
            val list = (state.adjudications[e.edgeId] ?: emptyList()) + rec
            state.copy(adjudications = state.adjudications + (e.edgeId to list))
        }

        is DomainEvent.IntervalRestrictionApplied ->
            state.copy(restrictions = state.restrictions + (e.measurementId to
                Restriction(e.measurementId, e.fromAstro, e.toAstro, e.originEdgeId)))
        is DomainEvent.IntervalRestrictionLifted ->
            state.copy(restrictions = state.restrictions - e.measurementId)

        is DomainEvent.CurvePinChanged ->
            state.copy(pins = state.pins + (e.measurementId to (e.toCurveVersion to e.measurementVersion)))

        is DomainEvent.NoteRecorded -> state
    }
}
