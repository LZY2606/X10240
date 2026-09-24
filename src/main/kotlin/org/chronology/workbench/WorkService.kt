package org.chronology.workbench

import kotlinx.serialization.json.JsonObject

class WorkException(val code: String, message: String) : RuntimeException(message)

class WorkService(
    private val store: SqliteEventStore,
    private val clock: WorkClock = FixedClock(),
    private val seed: Long = 20260924L,
) {
    private val calibrationEngine = CalibrationEngine(1.0)
    private val sequenceEngine = SequenceEngine()

    fun state(asOfSeq: Long? = null): ProjectState = Projector(calibrationEngine, sequenceEngine).project(store.events(), asOfSeq)

    fun exportBundle(): Bundle {
        val state = state()
        return Bundle(
            "radiocarbon-workbench-bundle-v1",
            ProjectInfo(state.projectId, state.title, state.description, state.coordinateDatum),
            store.events(),
        )
    }

    fun resetAndImport(bundle: Bundle, applyStages: Boolean = false): ImportReport {
        validateBundle(bundle)
        val count = store.appendAll(bundle.events, bundle.project.projectId, replace = true)
        val reportState = state()
        return ImportReport(count, bundle.project.projectId, reportState.diagnostics)
    }

    fun initializeProject(info: ProjectInfo): EventRecord {
        if (store.events().isNotEmpty()) throw WorkException("PROJECT_EXISTS", "项目已经初始化；新版本请通过事件修订表达")
        return appendInput("PROJECT_CREATED", info.projectId, info.projectId, null, jsonObjectOf(
            "projectId" to info.projectId,
            "title" to info.title,
            "description" to info.description,
            "coordinateDatum" to info.coordinateDatum,
            "provenance" to Provenance("source-project-seed", "工作台项目初始化", "project", "local-workbench"),
        ), emptyList())
    }

    fun submitCurve(payload: CurvePayload): EventRecord {
        validateCurve(payload)
        return appendInput("CURVE_VERSION_SUBMITTED", payload.curveId, payload.versionId, payload.parentVersionId, workbenchJson.encodeToJsonElement(CurvePayload.serializer(), payload) as JsonObject, listOf(payload.provenance.sourceId))
    }

    fun submitMeasurement(payload: MeasurementPayload): EventRecord {
        return appendInput("MEASUREMENT_VERSION_SUBMITTED", payload.sampleId, payload.versionId, payload.parentVersionId, workbenchJson.encodeToJsonElement(MeasurementPayload.serializer(), payload) as JsonObject, listOf(payload.provenance.sourceId))
    }

    fun submitReservoir(payload: ReservoirPayload): EventRecord {
        if (payload.scenarios.isEmpty()) throw WorkException("INVALID_RESERVOIR", "至少需要一个偏移情景")
        val weightSum = payload.scenarios.sumOf { it.weight }
        if (weightSum <= 0.0) throw WorkException("INVALID_RESERVOIR", "偏移权重之和必须为正")
        return appendInput("RESERVOIR_VERSION_SUBMITTED", payload.reservoirId, payload.versionId, payload.parentVersionId, workbenchJson.encodeToJsonElement(ReservoirPayload.serializer(), payload) as JsonObject, listOf(payload.provenance.sourceId))
    }

    fun submitEraSystem(payload: EraSystemPayload): EventRecord =
        appendInput("ERA_SYSTEM_SUBMITTED", payload.eraSystemId, payload.versionId, null, workbenchJson.encodeToJsonElement(EraSystemPayload.serializer(), payload) as JsonObject, listOf(payload.provenance.sourceId))

    fun submitRelation(payload: SequenceRelationPayload): EventRecord =
        appendInput("SEQUENCE_RELATION_SUBMITTED", payload.relationId, payload.versionId, null, workbenchJson.encodeToJsonElement(SequenceRelationPayload.serializer(), payload) as JsonObject, listOf(payload.provenance.sourceId))

    fun submitExclusion(payload: ExclusionPayload): EventRecord =
        appendInput("EXCLUSION_DECIDED", payload.exclusionId, payload.versionId, null, workbenchJson.encodeToJsonElement(ExclusionPayload.serializer(), payload) as JsonObject, listOf(payload.provenance.sourceId))

    fun calibrate(payload: CalibrationRequestPayload, rationaleSource: String = "reviewer-request"): EventRecord {
        val current = state()
        val required = listOf(payload.measurementVersionId, payload.curveVersionId, payload.reservoirVersionId) + payload.exclusionVersionIds
        required.forEach { version -> if (current.inputs.none { it.value.versionId == version }) throw WorkException("UNKNOWN_SOURCE", "未知直接来源: $version") }
        val request = appendInput("CALIBRATION_REQUESTED", payload.requestId, payload.requestId, null, jsonObjectOf(
            "request" to payload,
            "provenance" to Provenance("source-review-action", "审阅校准请求", "request", rationaleSource),
        ), required)
        materializeLatestDerived("calibration", request.id)
        return request
    }

    fun evaluateSequence(payload: SequenceRequestPayload, rationaleSource: String = "reviewer-request"): EventRecord {
        val current = state()
        payload.relationVersionIds.forEach { if (current.inputs.none { input -> input.value.versionId == it }) throw WorkException("UNKNOWN_SOURCE", "未知层序来源: $it") }
        payload.calibrationRequestIds.forEach { requestId ->
            if (current.derived.none { entry -> entry.value.requestEventId == requestId }) throw WorkException("UNKNOWN_CALIBRATION", "未知校准请求: $requestId")
        }
        val request = appendInput("SEQUENCE_EVALUATION_REQUESTED", payload.requestId, payload.requestId, null, jsonObjectOf(
            "request" to payload,
            "provenance" to Provenance("source-review-action", "层序评估请求", "request", rationaleSource),
        ), payload.relationVersionIds + payload.calibrationRequestIds)
        materializeLatestDerived("sequence", request.id)
        return request
    }

    fun decide(payload: DecisionPayload): EventRecord {
        val state = state()
        if (state.derived.none { it.value.subjectId == payload.targetId || it.value.requestEventId == payload.targetId } &&
            state.inputs.none { it.value.versionId == payload.targetId || it.value.subjectId == payload.targetId }) {
            throw WorkException("UNKNOWN_DECISION_TARGET", "决定必须指向已知对象")
        }
        val conflicting = state.decisions.values.firstOrNull { existing ->
            !existing.reversed && existing.subjectId == payload.subjectId &&
                existing.action == "ACCEPT" && payload.action == "ACCEPT" &&
                existing.payload.str("conflictKey") != null &&
                existing.payload.str("conflictKey") == payload.conflictKey &&
                existing.targetId != payload.targetId
        }
        val event = appendInput("REVIEWER_DECIDED", payload.decisionId, payload.decisionId, null, jsonObjectOf(
            "decision" to payload,
            "provenance" to Provenance("source-human-decision", "年代学审阅者人工决定", "decision", payload.rationale),
        ), listOf(payload.targetId))
        if (conflicting != null && payload.conflictKey != null) {
            appendDerived(
                "DECISION_CONFLICT_DETECTED",
                "conflict-${payload.conflictKey}",
                listOf(event.id, conflicting.eventId),
                jsonObjectOf("decisionEventId" to event.id, "previousDecisionEventId" to conflicting.eventId, "conflictKey" to payload.conflictKey, "status" to "CONFLICT"),
                "decision-conflict-v1",
                StableIds.fingerprint("decision-conflict-v1", mapOf("a" to event.id, "b" to conflicting.eventId)),
            )
        }
        return event
    }

    fun reverseDecision(decisionEventId: String, rationale: String): EventRecord {
        val current = state()
        val target = current.events.firstOrNull { it.id == decisionEventId && it.type == "REVIEWER_DECIDED" } ?: throw WorkException("UNKNOWN_DECISION", "只能撤销已提交的人工决定")
        return appendInput("DECISION_REVERSED", "reverse-$decisionEventId", "reverse-$decisionEventId", null, jsonObjectOf(
            "decisionEventId" to decisionEventId,
            "rationale" to rationale,
            "provenance" to Provenance("source-human-undo", "人工撤销", "undo", rationale),
        ), listOf(decisionEventId))
    }

    fun markStage(stageId: String): EventRecord {
        val eventId = StableIds.stable("stage", listOf(stageId))
        if (store.findEvent(eventId) != null) return store.findEvent(eventId)!!
        return appendInput("CASE_STAGE_APPLIED", stageId, eventId, null, jsonObjectOf("stageId" to stageId), emptyList())
    }

    private fun appendInput(type: String, subjectId: String, versionId: String?, parentVersionId: String?, payload: JsonObject, sources: List<String>): EventRecord {
        val event = buildEvent(type, subjectId, versionId, parentVersionId, payload, sources, null, null)
        return store.append(event, state().projectId)
    }

    private fun appendInput(type: String, subjectId: String, versionId: String?, payload: JsonObject, sources: List<String>): EventRecord =
        appendInput(type, subjectId, versionId, null, payload, sources)

    private fun appendDerived(type: String, subjectId: String, sources: List<String>, payload: JsonObject, algorithm: String, fingerprint: String): EventRecord {
        val id = StableIds.stable("derived", listOf(type, subjectId, fingerprint, sources.joinToString(",")))
        val event = buildEvent(type, subjectId, null, null, payload, sources, algorithm, fingerprint)
        return store.append(event, state().projectId)
    }

    private fun materializeLatestDerived(kind: String, requestEventId: String) {
        val view = state().derived.values.firstOrNull { it.kind == kind && it.requestEventId == requestEventId } ?: return
        val type = if (kind == "calibration") "CALIBRATION_DERIVED" else "SEQUENCE_DERIVED"
        if (store.findEvent(view.eventId) != null) return
        val event = EventRecord(view.eventId, 0, clock.nowIso(), type, view.subjectId, view.eventId, null, view.payload, view.directSources, view.algorithmVersion, view.parameterFingerprint, seed)
        store.append(event, state().projectId)
    }

    private fun buildEvent(type: String, subjectId: String, versionId: String?, parentVersionId: String?, payload: JsonObject, sources: List<String>, algorithm: String?, fingerprint: String?): EventRecord {
        val id = when {
            versionId != null && type in setOf("PROJECT_CREATED", "CASE_STAGE_APPLIED") -> versionId
            versionId != null -> versionId
            else -> StableIds.stable("evt", listOf(type, subjectId, clock.epochSeconds().toString(), sources.joinToString(","), payload.hashCode().toString()))
        }
        return EventRecord(id, 0, clock.nowIso(), type, subjectId, versionId, parentVersionId, payload, sources.distinct(), algorithm, fingerprint, seed)
    }

    private fun validateBundle(bundle: Bundle) {
        if (bundle.schema != "radiocarbon-workbench-bundle-v1") throw WorkException("INVALID_SCHEMA", "不支持的导出架构: ${bundle.schema}")
        val ids = mutableSetOf<String>()
        bundle.events.forEach { event ->
            if (!ids.add(event.id)) throw WorkException("DUPLICATE_EVENT_ID", "重复事件身份: ${event.id}")
            event.directSources.filter { it !in ids }.forEach { source ->
                if (!source.contains("://") && !source.startsWith("source-") && bundle.events.none { earlier -> earlier.id == source }) {
                    throw WorkException("MISSING_SOURCE", "事件 ${event.id} 缺少直接来源 $source")
                }
            }
        }
    }

    private fun validateCurve(payload: CurvePayload) {
        if (payload.points.size < 2) throw WorkException("INVALID_CURVE", "校准曲线至少需要两个点")
        payload.points.zipWithNext().forEach { (a, b) ->
            if (b.calendarBP <= a.calendarBP) throw WorkException("INVALID_CURVE", "曲线必须按日历 BP 升序")
        }
    }
}
