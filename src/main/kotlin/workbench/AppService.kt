package workbench

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import java.nio.file.Path

class AppService(private val database: Path, private val clock: TimeSource = SystemTimeSource()) {
    private val store = EventStore(database, clock)

    fun snapshot(): ProjectState = applyEvents(store.loadEvents())
    fun snapshotAt(eventId: String): ProjectState {
        val events = store.loadEvents()
        val index = events.indexOfFirst { it.eventId == eventId }
        if (index < 0) throw ValidationException(listOf("unknown event: $eventId"))
        return applyEvents(events.take(index + 1))
    }

    fun currentCalibration(measurementId: String): Calibration =
        snapshot().calibrations.last { it.measurementSeriesId == measurementId && it.status != "stale" }

    fun importPackage(text: String): ProjectState {
        if (!store.isEmpty()) throw ValidationException(listOf("project already contains an immutable initial package"))
        val parsed = parseProjectPackage(text)
        val importEvent = store.append("package_imported", parsed, "importer")
        val state = applyEvents(listOf(importEvent))
        appendDerivations(importEvent, state)
        return snapshot()
    }

    fun reviseReservoir(reservoirId: String, mean: Double, sigma: Double, sourceRef: String, version: String): ProjectState {
        val state = snapshot()
        val old = state.reservoirs[reservoirId] ?: throw ValidationException(listOf("unknown reservoir: $reservoirId"))
        val payload = json.createObjectNode()
            .put("seriesId", reservoirId).put("version", version).put("parentVersion", old.version)
            .put("label", old.label).put("mean", mean).put("sigma", sigma).put("unit", old.unit).put("sourceRef", sourceRef)
        val event = store.append("reservoir_revised", payload)
        appendDerivations(event, applyEvents(state.events + event))
        return snapshot()
    }

    fun reviseMeasurement(measurementId: String, age: String, sigma: String, sourceRef: String, version: String): ProjectState {
        val state = snapshot()
        val old = state.measurements[measurementId] ?: throw ValidationException(listOf("unknown measurement: $measurementId"))
        age.toDoubleOrNull() ?: throw ValidationException(listOf("measurements.$measurementId.radiocarbonAge: must be numeric"))
        sigma.toDoubleOrNull()?.takeIf { it > 0 } ?: throw ValidationException(listOf("measurements.$measurementId.sigma: must be positive"))
        val payload = json.createObjectNode()
            .put("seriesId", measurementId).put("version", version).put("parentVersion", old.version)
            .put("label", old.label).put("radiocarbonAge", age).put("sigma", sigma).put("unit", old.unit)
            .put("curveId", old.curveId).put("reservoirId", old.reservoirSeriesId)
            .put("sourceRef", sourceRef).put("rawText", "$age ± $sigma BP")
        val event = store.append("measurement_revised", payload)
        appendDerivations(event, applyEvents(state.events + event))
        return snapshot()
    }

    fun activateCurve(curveId: String, sourceRef: String, affectedMeasurementIds: List<String>? = null): ProjectState {
        val state = snapshot()
        val curve = state.curves[curveId] ?: throw ValidationException(listOf("unknown curve: $curveId"))
        val parent = curve.parentId
        if (parent == null || state.curves[parent] == null) throw ValidationException(listOf("curve $curveId is not a child of an active curve"))
        val affected = affectedMeasurementIds ?: state.measurements.values.filter { it.curveId == parent }.map { it.seriesId }
        val payload = json.createObjectNode().put("curveId", curveId).put("parentId", parent).put("sourceRef", sourceRef)
        val array = payload.putArray("affectedMeasurementIds"); affected.forEach(array::add)
        val event = store.append("curve_activated", payload)
        appendDerivations(event, applyEvents(state.events + event))
        return snapshot()
    }

    fun resolveConflict(sequenceId: String, action: String, selectedYears: List<List<Int>>, rationale: String, sourceRef: String = "review-form"): ProjectState {
        val state = snapshot()
        state.sequences[sequenceId] ?: throw ValidationException(listOf("unknown sequence: $sequenceId"))
        val normalized = when (action) {
            "reject_gamma_interval" -> "rejected"
            "relax_constraint", "exclude_sample", "revise_reservoir_assumption", "accept_younger_gamma_override" -> "accepted_with_conflict"
            "accept_interval" -> "accepted"
            else -> throw ValidationException(listOf("unknown decision action: $action"))
        }
        val status = state.sequenceStatuses.singleOrNull { it.sequenceId == sequenceId }
        val targetId = if (action == "reject_gamma_interval") status?.youngerId ?: state.sequences.getValue(sequenceId).youngerId else status?.youngerId ?: state.sequences.getValue(sequenceId).youngerId
        val payload = json.createObjectNode()
            .put("targetId", targetId).put("sequenceId", sequenceId).put("action", action).put("state", normalized)
            .put("rationale", rationale).put("sourceRef", sourceRef)
        val selected = payload.putArray("selectedYears")
        selectedYears.forEach { years -> val row = selected.addArray(); years.forEach(row::add) }
        store.append("decision_recorded", payload)
        return snapshot()
    }

    fun undoLastDecision(rationale: String): ProjectState {
        val state = snapshot()
        val decision = state.decisions.lastOrNull { it.undoneBy == null } ?: throw ValidationException(listOf("no active decision to undo"))
        val payload = json.createObjectNode()
            .put("parentEventId", decision.eventId).put("targetId", decision.targetId)
            .put("sequenceId", decision.sequenceId).put("rationale", rationale).put("sourceRef", "undo-form")
        store.append("decision_undone", payload)
        return snapshot()
    }

    fun exportProject(): ProjectExport {
        val events = store.loadEvents()
        return ProjectExport("radiocarbon-workbench-export/v1", events, sha256(*events.map { it.causalHash }.toTypedArray()))
    }

    fun importProject(exportText: String): ProjectState {
        val node = try { json.readTree(exportText) } catch (e: Exception) { throw ValidationException(listOf("export is not valid JSON")) }
        if (node.path("format").asText() != "radiocarbon-workbench-export/v1") throw ValidationException(listOf("unsupported export format"))
        if (!node.path("events").isArray) throw ValidationException(listOf("export events must be an array"))
        val events = node.path("events").map { event ->
            EventRecord(
                eventId = event.path("eventId").asText().takeIf { it.isNotBlank() } ?: throw ValidationException(listOf("event missing eventId")),
                seq = event.path("seq").asLong(),
                at = event.path("at").asText(),
                actor = event.path("actor").asText("importer"),
                type = event.path("type").asText(),
                payload = event.path("payload").takeIf(JsonNode::isObject) ?: throw ValidationException(listOf("event payload missing")),
                parentEventId = event.path("parentEventId").asText(null),
                causalHash = event.path("causalHash").asText()
            )
        }
        events.forEachIndexed { index, event -> if (event.seq != index + 1L) throw ValidationException(listOf("event sequence mismatch at ${event.eventId}")) }
        applyEvents(events)
        if (!store.isEmpty()) throw ValidationException(listOf("cannot import over an existing database; delete or reset first"))
        store.replaceAll(events)
        return snapshot()
    }

    fun reset() {
        store.deleteAll()
    }

    private fun appendDerivations(trigger: EventRecord, state: ProjectState) {
        val calibs = deriveCalibrations(trigger, state)
        if (calibs.isNotEmpty()) {
            val payload = json.createObjectNode().put("triggerEventId", trigger.eventId).put("algorithmVersion", ALGORITHM_VERSION)
            val array = payload.putArray("calibrations")
            calibs.forEach { array.add(json.valueToTree<JsonNode>(it)) }
            val calibrationEvent = store.append("calibration_batch_derived", payload, "system", trigger.eventId, sha256("calbatch", trigger.eventId, calibs.joinToString(",") { it.parameterFingerprint }).take(24))
            appendAnalysis(calibrationEvent, calibs, state)
        } else {
            appendAnalysis(trigger, state.calibrations.filter { it.status != "stale" }, state)
        }
    }

    private fun appendAnalysis(trigger: EventRecord, calibs: List<Calibration>, state: ProjectState) {
        val effectiveCalibrations = state.calibrations + calibs
        val statuses = deriveSequenceStatuses(state.copy(calibrations = effectiveCalibrations), trigger.eventId)
        val findings = deriveFindings(trigger.eventId, state.copy(calibrations = effectiveCalibrations, sequenceStatuses = statuses))
        val payload = json.createObjectNode().put("triggerEventId", trigger.eventId)
        val statusArray = payload.putArray("sequenceStatuses"); statuses.forEach { statusArray.add(json.valueToTree<JsonNode>(it)) }
        val findingArray = payload.putArray("findings"); findings.forEach { findingArray.add(json.valueToTree<JsonNode>(it)) }
        store.append("analysis_batch_derived", payload, "system", trigger.eventId, sha256("analysis", trigger.eventId, findings.joinToString(",") { it.code }).take(24))
    }

    private fun deriveCalibrations(trigger: EventRecord, state: ProjectState): List<Calibration> = when (trigger.type) {
        "package_imported" -> state.measurements.values.mapNotNull { calibrationFor(it, state) }
        "reservoir_revised" -> state.measurements.values.filter { it.reservoirSeriesId == trigger.payload.path("seriesId").asText() && !it.excluded }
            .map { calibrationFor(it, state) }
        "measurement_revised" -> state.measurements.values.filter { it.seriesId == trigger.payload.path("seriesId").asText() && !it.excluded }
            .map { calibrationFor(it, state) }
        "curve_activated" -> {
            val ids = trigger.payload.path("affectedMeasurementIds").map { it.asText() }.toSet()
            state.measurements.values.filter { it.seriesId in ids && !it.excluded }.map { calibrationFor(it, state) }
        }
        else -> emptyList()
    }

    private fun calibrationFor(measurement: Measurement, state: ProjectState): Calibration {
        val curve = state.curves[measurement.curveId] ?: error("missing curve")
        val reservoir = state.reservoirs[measurement.reservoirSeriesId] ?: error("missing reservoir")
        return CalibrationEngine.calibrate(measurement, curve, reservoir) ?: error("excluded measurements are not calibrated")
    }

    private fun deriveSequenceStatuses(state: ProjectState, eventId: String): List<SequenceStatus> = state.sequences.values.map { seq ->
        val older = latest(state, seq.olderId)
        val younger = latest(state, seq.youngerId)
        val activeDecision = state.decisions.lastOrNull { it.sequenceId == seq.id && it.undoneBy == null }
        val baseState = if (older == null || younger == null) "unknown"
        else if (compatible(older, younger)) "compatible" else "conflict"
        val candidates = if (baseState == "conflict") listOf(
            AdjustmentCandidate("relax_constraint", seq.id, "Relax or revise the stratigraphic constraint using a new sourced version", true),
            AdjustmentCandidate("revise_reservoir_assumption", seq.youngerId, "Propose a new reservoir-offset distribution; measured radiocarbon age stays unchanged", true),
            AdjustmentCandidate("exclude_sample", seq.youngerId, "Exclude the younger sample only with a documented human decision", true)
        ) else emptyList()
        SequenceStatus(
            sequenceId = seq.id, olderId = seq.olderId, youngerId = seq.youngerId,
            state = if (activeDecision != null && baseState == "conflict") "decided_${activeDecision.state}" else baseState,
            olderCandidateYears = older?.modes ?: emptyList(),
            youngerCandidateYears = younger?.modes ?: emptyList(),
            legalAdjustmentCandidates = candidates,
            evidenceRefs = listOf(seq.sourceRef, older?.stableId ?: "unknown", younger?.stableId ?: "unknown", eventId),
            reason = if (baseState == "conflict") "No single ordered pair covers all calibrated modes; all legal adjustments remain first-class candidates" else "Calendar posterior modes have at least one ordered legal pair"
        )
    }

    private fun latest(state: ProjectState, id: String): Calibration? =
        state.calibrations.lastOrNull { it.measurementSeriesId == id && it.status != "stale" }

    private fun compatible(older: Calibration, younger: Calibration): Boolean {
        val oldYears = older.intervals.filter { it.level == "95%" }.flatMap { it.years }.toSet()
        val youngYears = younger.intervals.filter { it.level == "95%" }.flatMap { it.years }.toSet()
        return oldYears.any { old -> youngYears.any { young -> young >= old } }
    }

    private fun deriveFindings(eventId: String, state: ProjectState): List<Finding> = buildList {
        state.calibrations.filter { it.status == "truncated_at_curve_boundary" && state.events.none { e -> e.type == "calibration_batch_derived" && e.payload.toString().contains(it.stableId) } }.forEach {
            add(Finding("boundary_interpolation_truncated", "warning", listOf(it.measurementSeriesId, it.stableId), eventId, json.valueToTree(it.integralEvidence)))
        }
        state.calibrations.filter { it.modes.size > 1 }.forEach {
            add(Finding("multimodal_posterior_retained", "information", listOf(it.measurementSeriesId, it.stableId), eventId, json.createObjectNode().put("averageCollapsed", false).put("modes", it.modes.joinToString(","))))
        }
        state.sequenceStatuses.filter { it.state == "conflict" }.forEach {
            add(Finding("sequence_conflict_candidates_preserved", "warning", listOf(it.sequenceId), eventId, json.valueToTree(it.legalAdjustmentCandidates)))
        }
    }.distinctBy { it.code to it.objectIds.joinToString() }
}
