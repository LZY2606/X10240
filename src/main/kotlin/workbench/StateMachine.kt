package workbench

import com.fasterxml.jackson.databind.JsonNode

fun applyEvents(events: List<EventRecord>): ProjectState {
    var state = ProjectState()
    events.forEach { event ->
        state = reduce(state, event).copy(events = state.events + event)
    }
    return state
}

private fun reduce(state: ProjectState, event: EventRecord): ProjectState = when (event.type) {
    "package_imported" -> importPackage(state, event)
    "reservoir_revised" -> state.copy(reservoirs = state.reservoirs + parseReservoir(event.payload).let { it.seriesId to it })
    "measurement_revised" -> {
        val revision = parseMeasurement(event.payload)
        val existing = state.measurements[revision.seriesId]
        state.copy(measurements = state.measurements + (revision.seriesId to revision.copy(
            excluded = existing?.excluded ?: false,
            excludedBy = existing?.excludedBy
        )))
    }
    "curve_activated" -> {
        val curveId = event.payload.path("curveId").asText()
        val parentId = event.payload.path("parentId").asText()
        val affected = event.payload.path("affectedMeasurementIds").map(JsonNode::asText).toSet()
        state.copy(measurements = state.measurements.mapValues { (id, measurement) ->
            if (id in affected && measurement.curveId == parentId) measurement.copy(curveId = curveId) else measurement
        })
    }
    "calibration_batch_derived" -> {
        val incoming = event.payload.path("calibrations").map { json.treeToValue(it, Calibration::class.java) }
        val affected = incoming.map(Calibration::measurementSeriesId).toSet()
        val retained = state.calibrations.filter { it.measurementSeriesId !in affected }.map {
            if (it.measurementSeriesId in affected) it else it
        }
        val expired = state.calibrations.filter { it.measurementSeriesId in affected }.map { it.copy(status = "stale") }
        state.copy(calibrations = retained + expired + incoming)
    }
    "analysis_batch_derived" -> state.copy(
        sequenceStatuses = event.payload.path("sequenceStatuses").map { json.treeToValue(it, SequenceStatus::class.java) },
        findings = event.payload.path("findings").map { json.treeToValue(it, Finding::class.java) }
    )
    "decision_recorded" -> state.copy(decisions = state.decisions + parseDecision(event))
    "decision_undone" -> {
        val parentId = event.payload.path("parentEventId").asText()
        val undone = state.decisions.map { if (it.eventId == parentId) it.copy(undoneBy = event.eventId) else it }
        val parent = state.decisions.single { it.eventId == parentId }
        val undoDecision = Decision(
            eventId = event.eventId,
            seq = event.seq,
            targetId = event.payload.path("targetId").asText(),
            sequenceId = parent.sequenceId,
            action = "undo",
            state = "undone",
            selectedYears = emptyList(),
            rationale = event.payload.path("rationale").asText(),
            sourceRef = event.payload.path("sourceRef").asText(),
            undoneBy = null,
            parentEventId = parentId
        )
        state.copy(decisions = undone + undoDecision)
    }
    else -> state
}

private fun importPackage(initial: ProjectState, event: EventRecord): ProjectState {
    val node = event.payload
    val curves = node.path("curveVersions").associate { curveNode ->
        curveNode.path("id").asText() to CurveVersion(
            id = curveNode.path("id").asText(),
            label = curveNode.path("label").asText(),
            parentId = curveNode.path("parentId").asText(null),
            points = curveNode.path("points").map { CurvePoint(it.path("calendarYear").asInt(), it.path("radiocarbonAge").asDouble()) },
            sourceRef = "case-package"
        )
    }
    val reservoirs = node.path("reservoirs").associate {
        val reservoir = parseReservoir(it, "v1")
        reservoir.seriesId to reservoir
    }
    val exclusions = node.path("exclusions").associate { it.path("targetId").asText() to it.path("id").asText() }
    val measurements = node.path("measurements").associate {
        val measurement = parseMeasurement(it, "v1")
        measurement.seriesId to measurement.copy(excluded = measurement.seriesId in exclusions, excludedBy = exclusions[measurement.seriesId])
    }
    val sequences = node.path("sequences").associate {
        val seq = SequenceConstraint(
            id = it.path("id").asText(),
            olderId = it.path("olderId").asText(),
            youngerId = it.path("youngerId").asText(),
            sourceRef = it.path("sourceRef").asText()
        )
        seq.id to seq
    }
    return ProjectState(
        packageId = node.path("packageId").asText(),
        title = node.path("title").asText(),
        calendarEra = node.path("calendarEra").asText(),
        curves = curves,
        reservoirs = reservoirs,
        measurements = measurements,
        sequences = sequences
    )
}

private fun parseReservoir(node: JsonNode): Reservoir = Reservoir(
    seriesId = node.path("seriesId").asText().ifBlank { node.path("id").asText() },
    version = node.path("version").asText("v1"),
    parentVersion = node.path("parentVersion").asText(null),
    label = node.path("label").asText(),
    mean = node.path("mean").asDouble(),
    sigma = node.path("sigma").asDouble(),
    unit = node.path("unit").asText(),
    sourceRef = node.path("sourceRef").asText()
)

private fun parseMeasurement(node: JsonNode, version: String = node.path("version").asText("v1")): Measurement = Measurement(
    seriesId = node.path("seriesId").asText().ifBlank { node.path("id").asText() },
    version = version,
    parentVersion = node.path("parentVersion").asText(null),
    label = node.path("label").asText(),
    rawAge = node.path("radiocarbonAge").asText(),
    rawSigma = node.path("sigma").asText(),
    age = node.path("radiocarbonAge").asDouble(),
    sigma = node.path("sigma").asDouble(),
    unit = node.path("unit").asText(),
    curveId = node.path("curveId").asText(),
    reservoirSeriesId = node.path("reservoirId").asText().ifBlank { node.path("reservoirSeriesId").asText() },
    sourceRef = node.path("sourceRef").asText(),
    rawText = node.path("rawText").asText()
)

private fun parseDecision(event: EventRecord): Decision = Decision(
    eventId = event.eventId,
    seq = event.seq,
    targetId = event.payload.path("targetId").asText(),
    sequenceId = event.payload.path("sequenceId").asText(null),
    action = event.payload.path("action").asText(),
    state = event.payload.path("state").asText(),
    selectedYears = event.payload.path("selectedYears").map { years -> years.map(JsonNode::asInt) },
    rationale = event.payload.path("rationale").asText(),
    sourceRef = event.payload.path("sourceRef").asText(),
    parentEventId = event.parentEventId
)
