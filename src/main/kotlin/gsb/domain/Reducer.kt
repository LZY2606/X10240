package gsb.domain

import gsb.calib.CalibrationEngine
import gsb.calib.SequenceEvaluator
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * 纯函数状态机：apply(state, event) -> state。
 * 不做 IO，不读系统时钟（时间来自事件本身），便于虚拟时间与逐事件重放。
 */
object Reducer {
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun apply(state: ProjectState, event: Event): ProjectState {
        var project = state.project
        val s = state.copy(
            curves = LinkedHashMap(state.curves),
            measurements = LinkedHashMap(state.measurements),
            reservoirs = LinkedHashMap(state.reservoirs),
            sequences = LinkedHashMap(state.sequences),
            exclusions = LinkedHashMap(state.exclusions),
            runs = LinkedHashMap(state.runs),
            sequenceEvals = LinkedHashMap(state.sequenceEvals),
            decisions = LinkedHashMap(state.decisions),
            runInputVersions = LinkedHashMap(state.runInputVersions),
            lastSeq = event.seq,
            project = project
        )
        when (event.type) {
            EventType.PROJECT_CREATED -> {
                project = json.decodeFromJsonElement<Project>(event.payload)
            }
            EventType.CURVE_REGISTERED -> {
                val c = json.decodeFromJsonElement<CalibrationCurve>(event.payload)
                s.curves[c.id] = c
                if (s.project?.activeCurveId == null) {
                    project = project!!.copy(activeCurveId = c.id)
                }
            }
            EventType.CURVE_SUPERSEDED -> {
                val c = json.decodeFromJsonElement<CalibrationCurve>(event.payload)
                s.curves[c.id] = c
            }
            EventType.CURVE_ASSIGNED -> {
                val curveId = event.payload.jsonObject["curveId"]!!.jsonPrimitive.content
                require(s.curves.containsKey(curveId)) { "curve not registered: $curveId" }
                project = project!!.copy(activeCurveId = curveId)
            }
            EventType.MEASUREMENT_SUBMITTED -> {
                val m = json.decodeFromJsonElement<Measurement>(event.payload)
                require(!s.measurements.containsKey(m.id)) { "measurement id already exists: ${m.id}" }
                s.measurements[m.id] = m
            }
            EventType.MEASUREMENT_REVISED -> {
                val m = json.decodeFromJsonElement<Measurement>(event.payload)
                require(m.parentId != null && s.measurements.containsKey(m.parentId)) { "revision needs existing parent" }
                s.measurements[m.parentId] = s.measurements[m.parentId]!!.copy(retired = true)
                s.measurements[m.id] = m
            }
            EventType.RESERVOIR_SUBMITTED -> {
                val r = json.decodeFromJsonElement<ReservoirAssumption>(event.payload)
                s.reservoirs[r.id] = r
            }
            EventType.RESERVOIR_REVISED -> {
                val r = json.decodeFromJsonElement<ReservoirAssumption>(event.payload)
                require(r.parentId != null && s.reservoirs.containsKey(r.parentId))
                s.reservoirs[r.parentId] = s.reservoirs[r.parentId]!!.copy(retired = true)
                s.reservoirs[r.id] = r
            }
            EventType.SEQUENCE_SUBMITTED -> {
                val q = json.decodeFromJsonElement<SequenceConstraint>(event.payload)
                s.sequences[q.id] = q
            }
            EventType.SEQUENCE_REVISED -> {
                val q = json.decodeFromJsonElement<SequenceConstraint>(event.payload)
                require(q.parentId != null)
                s.sequences[q.parentId] = s.sequences[q.parentId]!!.copy(retired = true)
                s.sequences[q.id] = q
            }
            EventType.EXCLUSION_SUBMITTED -> {
                val e = json.decodeFromJsonElement<ExclusionDecision>(event.payload)
                s.exclusions[e.id] = e
            }
            EventType.EXCLUSION_REVISED -> {
                val e = json.decodeFromJsonElement<ExclusionDecision>(event.payload)
                require(e.parentId != null)
                s.exclusions[e.parentId] = s.exclusions[e.parentId]!!.copy(retired = true)
                s.exclusions[e.id] = e
            }
            EventType.RUN_CALIBRATION -> {
                val r = json.decodeFromJsonElement<CalibrationRunResult>(event.payload)
                s.runs[r.runId] = r
                s.runInputVersions[r.runId] = setOf(
                    "measurement:${r.measurementId}",
                    "curve:${r.curveId}:${r.curveVersion}",
                    "reservoir:${r.reservoirId ?: "none"}"
                )
            }
            EventType.RUN_SEQUENCE_EVAL -> {
                val e = json.decodeFromJsonElement<SequenceEvalResult>(event.payload)
                s.sequenceEvals[e.evalId] = e
            }
            EventType.DECISION_ACCEPT,
            EventType.DECISION_REJECT,
            EventType.DECISION_PARAM_ADJUST -> {
                val d = json.decodeFromJsonElement<DecisionRecord>(event.payload)
                if (d.parentEventId != null && s.decisions.containsKey(d.parentEventId)) {
                    val old = s.decisions[d.parentEventId]!!
                    s.decisions[d.parentEventId] = old.copy(supersededBy = d.id)
                }
                s.decisions[d.id] = d
            }
            EventType.UNDO -> {
                // 逻辑撤销：把目标事件标记为 retired 语义由 payload 指向，并生成一条反向决定记录。
                val targetId = event.payload.jsonObject["undoesEventId"]!!.jsonPrimitive.content
                val d = json.decodeFromJsonElement<DecisionRecord>(
                    JsonObject(mapOf(
                        "id" to JsonPrimitive("decision-undo-${event.seq}"),
                        "targetKind" to JsonPrimitive("EVENT"),
                        "targetId" to JsonPrimitive(targetId),
                        "decision" to JsonPrimitive("UNDONE"),
                        "reason" to JsonPrimitive(event.payload.jsonObject["reason"]?.jsonPrimitive?.content ?: "undo"),
                        "actor" to JsonPrimitive(event.actor),
                        "at" to JsonPrimitive(event.occurredAt),
                        "parentEventId" to JsonPrimitive(targetId)
                    ))
                )
                s.decisions[d.id] = d
            }
        }
        return s
    }

    fun replay(events: List<Event>, uptoSeq: Long? = null): ProjectState =
        events.asSequence().filter { uptoSeq == null || it.seq <= uptoSeq }
            .fold(ProjectState()) { acc, e -> apply(acc, e) }

    /** 计算当前各派生结果的过期情况（不改写历史结果，过期结果仍可查看）。 */
    fun staleness(state: ProjectState): StaleReport {
        val activeCurve = state.project?.activeCurveId
        val curveVersion = activeCurve?.let { state.curves[it]?.version }
        val items = state.runs.values.map { run ->
            val m = state.measurements[run.measurementId]
            val changed = mutableListOf<String>()
            var stale = false
            if (m == null || m.retired) { stale = true; changed += "measurement:${run.measurementId}" }
            if (activeCurve != null && (run.curveId != activeCurve)) {
                stale = true; changed += "curve-assignment"
            }
            val c = state.curves[run.curveId]
            if (c == null || c.version != run.curveVersion) {
                stale = true; changed += "curve:${run.curveId}"
            }
            val rid = run.reservoirId
            if (rid != null) {
                val r = state.reservoirs[rid]
                if (r == null || r.retired) { stale = true; changed += "reservoir:$rid" }
            }
            val currentFp = currentFingerprint(state, run)
            DerivedStaleness(
                derivedId = run.runId,
                stale = stale || currentFp != run.paramFingerprint,
                currentFingerprint = currentFp,
                recordedFingerprint = run.paramFingerprint,
                changedSources = if (currentFp != run.paramFingerprint && "inputs" !in changed) changed + "inputs:recomputed-fingerprint" else changed
            )
        }
        return StaleReport(items)
    }

    private fun currentFingerprint(state: ProjectState, run: CalibrationRunResult): String {
        val m = state.measurements[run.measurementId]
        val curve = state.curves[run.curveId]
        val r = run.reservoirId?.let { state.reservoirs[it] }
        val ageRaw = m?.c14Age?.raw ?: ""
        val sigRaw = m?.c14Sigma?.raw ?: ""
        val deltaR = r?.deltaR?.normalized ?: 0.0
        val sigmaR = r?.deltaRSigma?.normalized ?: 0.0
        return CalibrationEngine.paramFingerprint(
            run.curveId, run.curveVersion, run.measurementId, ageRaw, sigRaw,
            r?.id ?: "none", deltaR, sigmaR, CalibrationEngine.GRID_STEP,
            run.gridFromCalBp, run.gridToCalBp
        )
    }
}
