package gsb.persist

import gsb.calib.CalibrationEngine
import gsb.calib.SequenceEvaluator
import gsb.domain.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * 应用服务：命令 -> 事件；事件是唯一事实源。
 * 所有派生计算携带算法版本、参数指纹与直接来源。
 */
class ProjectService(
    private val store: EventStore,
    private val clock: Clock = SYSTEM_CLOCK,
    private val idCounter: AtomicLong = AtomicLong(1)
) {
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = false }

    fun state(projectId: String): ProjectState = Reducer.replay(store.load(projectId))

    fun events(projectId: String, uptoSeq: Long? = null): List<Event> =
        store.load(projectId).let { all -> if (uptoSeq == null) all else all.filter { it.seq <= uptoSeq } }

    fun stateAt(projectId: String, uptoSeq: Long): ProjectState =
        Reducer.replay(store.load(projectId), uptoSeq)

    private fun nid(prefix: String): String =
        "$prefix-${clock.now().take(10).filter { it.isDigit() || it == '-' }}-${idCounter.getAndIncrement().toString(36)}"

    private fun emit(projectId: String, type: EventType, payloadBuilder: () -> JsonObject,
                     actor: String = "reviewer", note: String = "", derived: List<String> = emptyList()): Event {
        val ev = Event(
            seq = 0L,
            id = nid(type.name.lowercase().replace("_", "-")),
            type = type,
            occurredAt = clock.now(),
            actor = actor,
            payload = payloadBuilder(),
            derivedIds = derived,
            note = note
        )
        return store.append(ev, projectId)
    }

    fun createProject(p: Project): Event =
        emit(p.id, EventType.PROJECT_CREATED, { json.encodeToJsonElement(p) as JsonObject })

    fun registerCurve(projectId: String, curve: CalibrationCurve): Event =
        emit(projectId, EventType.CURVE_REGISTERED, { json.encodeToJsonElement(curve) as JsonObject })

    fun supersedeCurve(projectId: String, curve: CalibrationCurve): Event =
        emit(projectId, EventType.CURVE_SUPERSEDED,
            { json.encodeToJsonElement(curve) as JsonObject },
            note = "曲线升版：${curve.supersedes ?: "?"} -> ${curve.version}")

    fun assignCurve(projectId: String, curveId: String, actor: String = "reviewer"): Event =
        emit(projectId, EventType.CURVE_ASSIGNED, {
            JsonObject(mapOf("curveId" to JsonPrimitive(curveId), "at" to JsonPrimitive(clock.now())))
        }, actor = actor, note = "切换当前校准曲线（历史 run 不被覆盖，标记过期）")

    fun submitMeasurement(projectId: String, m: Measurement): Event =
        emit(projectId, EventType.MEASUREMENT_SUBMITTED, { json.encodeToJsonElement(m) as JsonObject })

    fun reviseMeasurement(projectId: String, m: Measurement): Event =
        emit(projectId, EventType.MEASUREMENT_REVISED, { json.encodeToJsonElement(m) as JsonObject },
            note = "原测量不可覆盖：${m.parentId} 标记 retired，新版本 ${m.id}")

    fun submitReservoir(projectId: String, r: ReservoirAssumption): Event =
        emit(projectId, EventType.RESERVOIR_SUBMITTED, { json.encodeToJsonElement(r) as JsonObject })

    fun reviseReservoir(projectId: String, r: ReservoirAssumption): Event =
        emit(projectId, EventType.RESERVOIR_REVISED, { json.encodeToJsonElement(r) as JsonObject },
            note = "储库偏移参数修订：${r.parentId} -> ${r.id}")

    fun submitSequence(projectId: String, q: SequenceConstraint): Event =
        emit(projectId, EventType.SEQUENCE_SUBMITTED, { json.encodeToJsonElement(q) as JsonObject })

    fun reviseSequence(projectId: String, q: SequenceConstraint): Event =
        emit(projectId, EventType.SEQUENCE_REVISED, { json.encodeToJsonElement(q) as JsonObject })

    fun submitExclusion(projectId: String, e: ExclusionDecision): Event =
        emit(projectId, EventType.EXCLUSION_SUBMITTED, { json.encodeToJsonElement(e) as JsonObject })

    /** 对当前（或指定版本）输入执行校准。返回保存的事件（其 payload 即完整结果）。 */
    fun runCalibration(
        projectId: String,
        measurementId: String,
        curveId: String? = null,
        reservoirOverrideId: String? = null,
        hypothesisLabel: String? = null
    ): Event {
        val st = state(projectId)
        val m = st.measurements[measurementId]
            ?: error("measurement not found: $measurementId")
        val curve = (curveId ?: st.project?.activeCurveId)?.let { st.curves[it] }
            ?: error("no active calibration curve")
        val reservoirId = reservoirOverrideId ?: m.reservoirId
        val reservoir = reservoirId?.let { st.reservoirs[it] }
        val runId = nid("run")
        var counter = 0
        val result = CalibrationEngine.calibrate(
            measurement = m,
            curve = curve,
            reservoir = reservoir,
            createdAt = clock.now(),
            runId = runId,
            evidenceIdFactory = { key -> "ev-${runId.removePrefix("run-")}-$key" }
        )
        val withLabel = if (hypothesisLabel != null)
            result.copy(diagnostics = result.diagnostics + "offset-hypothesis:$hypothesisLabel")
        else result
        return emit(projectId, EventType.RUN_CALIBRATION,
            { json.encodeToJsonElement(withLabel) as JsonObject },
            derived = listOf(runId),
            note = hypothesisLabel?.let { "偏移假设：$it" } ?: "")
    }

    fun runSequenceEval(projectId: String, constraintId: String): Event {
        val st = state(projectId)
        val q = st.sequences[constraintId] ?: error("sequence not found: $constraintId")
        // 每个样品取其“最新、未过期”的 run（同一最新输入指纹）。
        val runs = q.members.mapNotNull { mem ->
            st.runs.values.filter { it.sampleId == mem.sampleId }.maxByOrNull { it.runId }
        }
        check(runs.size == q.members.size) { "层序成员缺少校准结果" }
        val evalId = nid("seqeval")
        val result = SequenceEvaluator.evaluate(q, runs, clock.now(), evalId)
        return emit(projectId, EventType.RUN_SEQUENCE_EVAL,
            { json.encodeToJsonElement(result) as JsonObject },
            derived = listOf(evalId))
    }

    fun decide(
        projectId: String,
        kind: String, targetId: String, accept: Boolean,
        reason: String, actor: String = "reviewer",
        parentDecisionId: String? = null, paramPatch: JsonObject? = null
    ): Event {
        val type = when {
            paramPatch != null -> EventType.DECISION_PARAM_ADJUST
            accept -> EventType.DECISION_ACCEPT
            else -> EventType.DECISION_REJECT
        }
        val id = nid("decision")
        val d = DecisionRecord(
            id = id,
            targetKind = kind,
            targetId = targetId,
            decision = if (paramPatch != null) "ADJUSTED" else if (accept) "ACCEPTED" else "REJECTED",
            reason = reason,
            actor = actor,
            at = clock.now(),
            parentEventId = parentDecisionId,
            paramPatch = paramPatch
        )
        return emit(projectId, type, { json.encodeToJsonElement(d) as JsonObject }, actor = actor,
            note = "人工决定（接受/否定/参数调整）写入历史，不覆盖旧结论")
    }

    fun undo(projectId: String, targetEventId: String, reason: String, actor: String = "reviewer"): Event =
        emit(projectId, EventType.UNDO, {
            JsonObject(mapOf(
                "undoesEventId" to JsonPrimitive(targetEventId),
                "reason" to JsonPrimitive(reason),
                "at" to JsonPrimitive(clock.now())
            ))
        }, actor = actor, note = "撤销生成新事件，旧事件保留")

    fun exportPackage(projectId: String, exportedAt: String = clock.now()): ProjectPackage {
        val evs = store.load(projectId)
        val st = Reducer.replay(evs)
        val sources = buildSet {
            st.measurements.values.forEach { add(it.source.origin) }
            st.curves.values.forEach { add(it.source.origin); it.points.forEach { p -> add(p.source.origin) } }
            st.reservoirs.values.forEach { add(it.source.origin) }
            st.sequences.values.forEach { add(it.source.origin) }
            st.exclusions.values.forEach { add(it.source.origin) }
        }.toList()
        return ProjectPackage(
            projectId = projectId,
            exportedAt = exportedAt,
            events = evs,
            manifest = PackageManifest(
                eventCount = evs.size,
                sourceRefs = sources,
                curveVersions = st.curves.values.map { "${it.id}@${it.version}" }.distinct(),
                identityOrder = evs.map { it.id }
            )
        )
    }

    /** 导入：逐事件重放校验；身份与顺序原样保留。返回导入的事件。 */
    fun importPackage(pkg: ProjectPackage, replace: Boolean = false): List<Event> {
        // 先纯重放，确认包自洽。
        Reducer.replay(pkg.events)
        require(pkg.manifest.identityOrder == pkg.events.map { it.id }) { "manifest identity/order mismatch" }
        val existing = runCatching { store.load(pkg.projectId) }.getOrDefault(emptyList())
        if (existing.isNotEmpty() && !replace) error("project already exists: ${pkg.projectId}")
        return store.appendRawBatch(pkg.events, pkg.projectId)
    }
}
