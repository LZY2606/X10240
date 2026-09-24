package org.chronology.workbench

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement

class Projector(
    private val calibrationEngine: CalibrationEngine = CalibrationEngine(1.0),
    private val sequenceEngine: SequenceEngine = SequenceEngine(),
) {
    fun project(allEvents: List<EventRecord>, asOfSeq: Long? = null): ProjectState {
        val events = allEvents.take(asOfSeq?.toInt() ?: allEvents.size)
        var projectId = "empty-workbench"
        var title = "空工作台"
        var description = ""
        var datum = "CAL_BP_CE1950"
        val inputs = LinkedHashMap<String, InputVersion>()
        val inputHistory = LinkedHashMap<String, MutableList<InputVersion>>()
        val decisions = LinkedHashMap<String, DecisionView>()
        val diagnostics = mutableListOf<Diagnostic>()
        val derivedReplay = LinkedHashMap<String, DerivedView>()

        events.forEachIndexed { index, event ->
            when (event.type) {
                "PROJECT_CREATED" -> {
                    projectId = event.payload.str("projectId") ?: event.subjectId
                    title = event.payload.str("title") ?: projectId
                    description = event.payload.str("description") ?: ""
                    datum = event.payload.str("coordinateDatum") ?: datum
                    putInput(inputs, inputHistory, event, "project")
                }
                "CURVE_VERSION_SUBMITTED", "MEASUREMENT_VERSION_SUBMITTED", "RESERVOIR_VERSION_SUBMITTED",
                "ERA_SYSTEM_SUBMITTED", "SEQUENCE_RELATION_SUBMITTED", "EXCLUSION_DECIDED" -> {
                    val kind = event.type.substringBefore("_VERSION").substringBefore("_SUBMITTED").lowercase().replace("sequence_relation", "sequence_relation")
                    val diagnostic = inputDiagnostic(event)
                    if (diagnostic != null) diagnostics.add(diagnostic)
                    putInput(inputs, inputHistory, event, kind)
                    if (event.parentVersionId != null && inputs[event.parentVersionId] != null) {
                        val old = inputs[event.parentVersionId]!!
                        inputs[event.parentVersionId] = old.copy(supersededBy = event.versionId)
                    }
                }
                "REVIEWER_DECIDED" -> {
                    val decision = decode<DecisionPayload>(event.payload.obj("decision") ?: event.payload)
                    decisions[event.id] = DecisionView(event.id, event.subjectId, event.versionId ?: event.id, index + 1L, decision.action, decision.targetType, decision.targetId, event.payload, event.directSources)
                }
                "DECISION_REVERSED" -> {
                    val target = event.payload.str("decisionEventId")
                    if (target != null && decisions[target] != null) decisions[target] = decisions[target]!!.copy(reversed = true)
                }
                "CALIBRATION_REQUESTED" -> {
                    val view = calibrationView(event, inputs, decisions, index + 1L)
                    if (view != null) derivedReplay[view.eventId] = view
                }
                "SEQUENCE_EVALUATION_REQUESTED" -> {
                    val view = sequenceView(event, inputs, decisions, derivedReplay, index + 1L)
                    if (view != null) derivedReplay[view.eventId] = view
                }
                "CALIBRATION_DERIVED" -> verifyCalibration(event, derivedReplay, diagnostics)
                "SEQUENCE_DERIVED" -> verifySequence(event, derivedReplay, diagnostics)
                "DECISION_CONFLICT_DETECTED" -> {
                    derivedReplay[event.id] = DerivedView(
                        event.id, event.directSources.firstOrNull().orEmpty(), "decision-conflict", event.subjectId,
                        event.versionId ?: event.id, index + 1L,
                        event.payload.str("status") ?: "CONFLICT",
                        event.payload, event.directSources, event.algorithmVersion ?: "unknown",
                        event.parameterFingerprint ?: "", emptyList(),
                    )
                }
            }
        }

        val inputList = inputs.values
        derivedReplay.values.filter { it.kind == "calibration" }.forEach { view ->
            if (view.status.startsWith("CALIBRATION") || view.status == "MULTIMODAL" || view.status == "CURRENT") {
                val staleReason = calibrationStale(view, inputs)
                if (staleReason != null) {
                    derivedReplay[view.eventId] = view.copy(status = staleReason)
                }
            }
        }
        derivedReplay.values.filter { it.kind == "sequence" }.forEach { view ->
            val staleReason = sequenceStale(view, derivedReplay, inputs)
            if (staleReason != null) derivedReplay[view.eventId] = view.copy(status = staleReason)
        }

        return ProjectState(projectId, title, description, datum, events, inputs, inputHistory, derivedReplay, decisions, diagnostics, events.size.toLong())
    }

    private fun putInput(map: MutableMap<String, InputVersion>, history: MutableMap<String, MutableList<InputVersion>>, event: EventRecord, kind: String) {
        val versionId = event.versionId ?: event.id
        val version = InputVersion(event.subjectId, versionId, event.parentVersionId, kind, event.id, event.seq, event.payload, event.directSources)
        map[versionId] = version
        history.getOrPut(event.subjectId) { mutableListOf() }.add(version)
    }

    private fun inputDiagnostic(event: EventRecord): Diagnostic? = when (event.type) {
        "MEASUREMENT_VERSION_SUBMITTED" -> {
            val payload = decode<MeasurementPayload>(event.payload)
            listOf(payload.age14CBP, payload.sigma14CBP).firstOrNull { it.numericValue == null }?.let {
                Diagnostic("UNCONVERTIBLE_QUANTITY", event.subjectId, "原始表示保留但无法规范化: ${it.rawRepresentation}", "ERROR", event.id)
            }
        }
        else -> null
    }

    private fun calibrationView(event: EventRecord, inputs: Map<String, InputVersion>, decisions: Map<String, DecisionView>, seq: Long): DerivedView? {
        val request = decode<CalibrationRequestPayload>(event.payload.obj("request") ?: return null)
        val measurementInput = inputs[request.measurementVersionId] ?: return null
        val curveInput = inputs[request.curveVersionId] ?: return null
        val reservoirInput = inputs[request.reservoirVersionId] ?: return null
        val measurement = decode<MeasurementPayload>(measurementInput.payload)
        val curve = decode<CurvePayload>(curveInput.payload)
        val reservoir = decode<ReservoirPayload>(reservoirInput.payload)
        val age = measurement.age14CBP.numericValue
        val sigma = measurement.sigma14CBP.numericValue
        if (age == null || sigma == null) {
            return derivedShell(event, request.sampleId, "calibration", seq, "UNKNOWN_UNCONVERTIBLE_INPUT", jsonObjectOf("reason" to "原始测量无法转换，未进行绘图规范化"), listOf("curve14CBP"))
        }
        val exclusionIntervals = request.exclusionVersionIds.mapNotNull { id ->
            inputs[id]?.let { decode<ExclusionPayload>(it.payload) }.let { it?.startBP to it?.endBP }
        }.mapNotNull { (start, end) -> if (start != null && end != null) start to end else null }
        val computation = calibrationEngine.calibrate(
            request.sampleId, event.id, age, sigma, InterpolatedCurve(curve.points), curve.versionId,
            reservoir.scenarios.map { ReservoirScenario(it.offsetBP, it.sigmaBP, it.weight / reservoir.scenarios.sumOf { s -> s.weight }, it.hypothesisId) },
            measurement.versionId, reservoir.versionId, exclusionIntervals,
        )
        val payload = workbenchJson.encodeToJsonElement(CalibrationComputation.serializer(), computation) as JsonObject
        val activeReversal = decisions.values.any { it.reversed && it.targetId == computation.resultEventId }
        return DerivedView(computation.resultEventId, event.id, "calibration", request.sampleId, computation.versionId, seq, if (activeReversal) "REJECTED_BY_HUMAN" else computation.status, payload, computation.directSources, ALGORITHM_CALIBRATION, computation.parameterFingerprint, computation.sourceRanges)
    }

    private fun sequenceView(event: EventRecord, inputs: Map<String, InputVersion>, decisions: Map<String, DecisionView>, derived: Map<String, DerivedView>, seq: Long): DerivedView? {
        val request = decode<SequenceRequestPayload>(event.payload.obj("request") ?: return null)
        val relations = request.relationVersionIds.mapNotNull { inputs[it] }.map { decode<SequenceRelationPayload>(it.payload) }.map { SequenceRelationSpec(it.relationId, it.versionId, it.youngerSampleId, it.olderSampleId, it.rationale) }
        val distributions = request.calibrationRequestIds.mapNotNull { requestId -> derived.values.firstOrNull { it.kind == "calibration" && it.requestEventId == requestId } }
            .associate { it.subjectId to decode<CalibrationComputation>(it.payload) }
        if (relations.size != request.relationVersionIds.size || distributions.size < 2) return null
        val computation = sequenceEngine.evaluate(request.sequenceId, event.id, relations, distributions)
        val payload = workbenchJson.encodeToJsonElement(SequenceComputation.serializer(), computation) as JsonObject
        return DerivedView(computation.resultEventId, event.id, "sequence", request.sequenceId, computation.versionId, seq, computation.status, payload, (event.directSources + computation.directSources).distinct(), ALGORITHM_SEQUENCE, computation.parameterFingerprint, computation.sourceRanges)
    }

    private fun derivedShell(event: EventRecord, subjectId: String, kind: String, seq: Long, status: String, payload: JsonObject, extraSources: List<String>): DerivedView {
        val id = StableIds.stable("derived-view", listOf(event.id, status))
        return DerivedView(id, event.id, kind, subjectId, id, seq, status, payload, event.directSources + extraSources, "none", "", emptyList())
    }

    private fun calibrationStale(view: DerivedView, inputs: Map<String, InputVersion>): String? {
        val directVersions = view.directSources.mapNotNull { inputs[it] }
        val revisedMeasurementOrReservoir = directVersions.firstOrNull { version ->
            version.kind in setOf("measurement", "reservoir") && version.supersededBy != null
        }
        if (revisedMeasurementOrReservoir != null) return "STALE_DEPENDENCY_CHANGED"
        val curve = directVersions.firstOrNull { it.kind == "curve" }
        if (curve?.supersededBy != null) {
            val next = inputs[curve.supersededBy]
            val nextCurve = next?.let { runCatching { decode<CurvePayload>(it.payload) }.getOrNull() }
            val changedStart = nextCurve?.changeStartBP
            val changedEnd = nextCurve?.changeEndBP
            val usedRange = view.sourceRanges.firstOrNull { it.versionId == curve.versionId }
            if (changedStart == null || changedEnd == null || usedRange == null || overlaps(usedRange.startBP, usedRange.endBP, changedStart, changedEnd)) {
                return "STALE_DEPENDENCY_CHANGED"
            }
            return "PARALLEL_CURRENT_LOCAL_SEGMENTS_UNCHANGED"
        }
        return null
    }

    private fun sequenceStale(view: DerivedView, derived: Map<String, DerivedView>, inputs: Map<String, InputVersion>): String? {
        if (view.directSources.mapNotNull { inputs[it] }.any { it.kind == "sequence_relation" && it.supersededBy != null }) return "STALE_DEPENDENCY_CHANGED"
        val calibrationRequests = view.directSources.filter { it.endsWith("") }
        val calibrationViews = derived.values.filter { it.kind == "calibration" && it.requestEventId in view.directSources }
        return if (calibrationViews.any { it.status == "STALE_DEPENDENCY_CHANGED" }) "STALE_DEPENDENCY_CHANGED" else null
    }

    private fun overlaps(startA: Double, endA: Double, startB: Double, endB: Double): Boolean =
        minOf(startA, endA) <= maxOf(startB, endB) && minOf(startB, endB) <= maxOf(startA, endA)

    private fun verifyCalibration(event: EventRecord, computed: MutableMap<String, DerivedView>, diagnostics: MutableList<Diagnostic>) {
        val view = computed[event.id] ?: return
        if (event.parameterFingerprint != view.parameterFingerprint) {
            diagnostics.add(Diagnostic("REPLAY_MISMATCH", event.subjectId, "校准结果在固定版本下无法重放: ${event.id}", "ERROR", event.id))
        }
        computed.put(event.id, view.copy(directSources = (view.directSources + event.directSources).distinct()))
    }

    private fun verifySequence(event: EventRecord, computed: MutableMap<String, DerivedView>, diagnostics: MutableList<Diagnostic>) {
        val view = computed[event.id] ?: return
        if (event.parameterFingerprint != view.parameterFingerprint) {
            diagnostics.add(Diagnostic("REPLAY_MISMATCH", event.subjectId, "层序结果在固定版本下无法重放: ${event.id}", "ERROR", event.id))
        }
        computed.put(event.id, view.copy(directSources = (view.directSources + event.directSources).distinct()))
    }

    private inline fun <reified T> decode(element: JsonObject): T = workbenchJson.decodeFromJsonElement(element)
}
