package org.chrono.workbench.db

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.chrono.workbench.domain.*
import org.chrono.workbench.events.EventTypes
import org.chrono.workbench.events.JudgmentCodes
import org.chrono.workbench.events.StoredEvent
import org.chrono.workbench.model.*
import org.chrono.workbench.time.Clock
import org.chrono.workbench.util.Ids
import java.time.format.DateTimeFormatter

class ProjectService(
    val store: SqliteStore,
    private val clock: Clock,
    private val actor: String = "reviewer@gsb"
) {
    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }
    val state = ProjectState()

    init {
        rebuild()
    }

    fun rebuild() {
        val fresh = ProjectState()
        val events = store.loadEvents()
        Reducer.reduce(fresh, events, json)
        for (row in store.loadDerived()) {
            fresh.derived[row.result.derivedId] = DerivedRecord(row.result, row.input, row.eventSeq, stale = false, staleReason = null)
        }
        markStale(fresh)
        state.curves.clear(); state.curves.putAll(fresh.curves)
        state.measurements.clear(); state.measurements.putAll(fresh.measurements)
        state.reservoirs.clear(); state.reservoirs.putAll(fresh.reservoirs)
        state.constraints.clear(); state.constraints.putAll(fresh.constraints)
        state.decisions.clear(); state.decisions.putAll(fresh.decisions)
        state.derived.clear(); state.derived.putAll(fresh.derived)
        state.eraSystem = fresh.eraSystem
        state.events.clear(); state.events.addAll(fresh.events)
        state.opened = fresh.opened
        state.projectId = fresh.projectId
        state.projectName = fresh.projectName
        state.activeCurveId = fresh.activeCurveId
        state.sequenceAtEventSeq = fresh.sequenceAtEventSeq
    }

    private fun now(): String = DateTimeFormatter.ISO_INSTANT.format(clock.now())

    private fun nextSeq(): Int = (state.events.maxOfOrNull { it.seq } ?: 0) + 1

    @Synchronized
    private fun append(
        type: String,
        summary: String,
        payloadBuilder: JsonObjectBuilder.() -> Unit,
        sources: List<String> = emptyList(),
        affected: List<String> = emptyList(),
        reversedEventId: String? = null,
        replayTag: String? = null,
        actorOverride: String? = null
    ): StoredEvent {
        val seq = nextSeq()
        val parent = state.events.lastOrNull()?.eventId
        val payload = buildJsonObject(payloadBuilder)
        val canonical = "$seq|$type|${payload}"
        val eventId = Ids.event(type, canonical)
        val event = StoredEvent(
            seq = seq,
            eventId = eventId,
            parentEventId = parent,
            type = type,
            atInstant = now(),
            actor = actorOverride ?: actor,
            summary = summary,
            payload = payload,
            sources = sources,
            affectedObjectIds = affected,
            reversedEventId = reversedEventId,
            replayTag = replayTag
        )
        store.appendEvent(event)
        Reducer.apply(state, event, json)
        return event
    }

    fun openProject(projectId: String, name: String, basis: String, replayTag: String? = null): StoredEvent {
        if (state.opened) return state.events.first()
        val e = append(
            EventTypes.PROJECT_OPENED,
            "Project opened from provenance baseline",
            {
                put("projectId", projectId)
                put("name", name)
                put("basis", basis)
            },
            replayTag = replayTag
        )
        return e
    }

    fun registerCurve(curve: Curve, superseded: Boolean = false, replayTag: String? = null): StoredEvent {
        val existing = state.curves[curve.id]
        if (existing != null) return state.events.first { it.type == EventTypes.CURVE_REGISTERED && it.affectedObjectIds.contains(curve.id) }
        store.putSource(curve.id, "curve", curve.points.first().calBP, curve)
        return append(
            if (superseded) EventTypes.CURVE_SUPERSEDED else EventTypes.CURVE_REGISTERED,
            "Calibration curve ${curve.version} registered" + (curve.supersedesCurveId?.let { " superseding $it" } ?: ""),
            { put("curve", json.encodeToJsonElement(curve)) },
            sources = listOf(curve.source.locator),
            affected = listOf(curve.id),
            replayTag = replayTag
        )
    }

    fun importMeasurement(measurement: Measurement, replayTag: String? = null): StoredEvent {
        store.putSource(measurement.id, "measurement", measurement.seq, measurement)
        return append(
            EventTypes.MEASUREMENT_IMPORTED,
            "Measurement ${measurement.labCode} imported from ${measurement.source.name}",
            { put("measurement", json.encodeToJsonElement(measurement)) },
            sources = listOf(measurement.source.locator),
            affected = listOf(measurement.id),
            replayTag = replayTag
        )
    }

    fun reviseMeasurement(revised: Measurement, replayTag: String? = null): StoredEvent {
        store.putSource(revised.id, "measurement", revised.seq, revised)
        return append(
            EventTypes.MEASUREMENT_REVISED,
            "Measurement ${revised.labCode} revised as a new version (parent ${revised.revisionOf}); original is preserved",
            { put("measurement", json.encodeToJsonElement(revised)) },
            sources = listOf(revised.source.locator),
            affected = listOf(revised.id, revised.revisionOf ?: ""),
            replayTag = replayTag
        )
    }

    fun setReservoir(reservoir: ReservoirOffset, replayTag: String? = null): StoredEvent {
        store.putSource(reservoir.id, "reservoir", reservoir.seq, reservoir)
        return append(
            EventTypes.RESERVOIR_SET,
            "Reservoir offset '${reservoir.label}' attached to ${reservoir.measurementId}",
            { put("reservoir", json.encodeToJsonElement(reservoir)) },
            sources = listOf(reservoir.source.locator),
            affected = listOf(reservoir.id, reservoir.measurementId),
            replayTag = replayTag
        )
    }

    fun setEra(era: EraSystem, replayTag: String? = null): StoredEvent {
        store.putSource(era.id, "era", 0, era)
        return append(
            EventTypes.ERA_SET,
            "Era system '${era.name}' established",
            { put("era", json.encodeToJsonElement(era)) },
            sources = listOf(era.source.locator),
            affected = listOf(era.id),
            replayTag = replayTag
        )
    }

    fun setConstraint(constraint: SequenceConstraint, replayTag: String? = null): StoredEvent {
        store.putSource(constraint.id, "sequence", constraint.seq, constraint)
        return append(
            EventTypes.SEQUENCE_SET,
            "Sequence ${constraint.olderMeasurementId} ${constraint.relation} ${constraint.youngerMeasurementId}",
            { put("constraint", json.encodeToJsonElement(constraint)) },
            sources = listOf(constraint.source.locator),
            affected = listOf(constraint.id, constraint.olderMeasurementId, constraint.youngerMeasurementId),
            replayTag = replayTag
        )
    }

    fun decideExclusion(decision: ExclusionDecision, replayTag: String? = null): StoredEvent {
        store.putSource(decision.id, "decision", decision.seq, decision)
        return append(
            EventTypes.EXCLUSION_DECIDED,
            "Manual decision ${decision.decision} for ${decision.measurementId}: ${decision.reason}",
            { put("decision", json.encodeToJsonElement(decision)) },
            sources = listOf(decision.source.locator),
            affected = listOf(decision.id, decision.measurementId),
            replayTag = replayTag
        )
    }
}

// ---- derived calibration -------------------------------------------------

fun currentInputFor(measurementId: String): CalibrationInput? {
    val m = state.measurements[measurementId] ?: return null
    if (m.convertStatus != ConvertStatus.OK.name || m.ageBP == null || m.sigmaBP == null) return null
    val curve = state.activeCurve() ?: return null
    val reservoir = state.reservoirFor(measurementId)
    return CalibrationInput(
        measurementId = m.id,
        labCode = m.labCode,
        ageBP = m.ageBP,
        sigmaBP = m.sigmaBP,
        curve = curve,
        reservoir = reservoir
    )
}

@Synchronized
fun requestCalibration(measurementId: String, replayTag: String? = null, emitJudgments: Boolean = true): CalibrationResult? {
    val input = currentInputFor(measurementId) ?: return null
    val fingerprint = CalibrationEngine.parameterFingerprint(input)
    val derivedId = Ids.derived(fingerprint)
    val existing = state.derived[derivedId]
    val event = append(
        EventTypes.DERIVED_REQUESTED,
        "Calibration requested for $measurementId against ${input.curve.version}",
        {
            put("derivedId", derivedId)
            put("measurementId", measurementId)
            put("curveId", input.curve.id)
            put("reservoirId", input.reservoir?.id)
            put("parameterFingerprint", fingerprint)
            put("algorithmVersion", ALGORITHM_VERSION)
            put("reused", existing != null)
        },
        sources = listOf(input.measurementId, input.curve.id) + listOfNotNull(input.reservoir?.id),
        affected = listOf(derivedId, measurementId),
        replayTag = replayTag
    )
    if (existing == null) {
        val result = CalibrationEngine.calibrate(input, derivedId)
        store.putDerived(derivedId, input, result, event.seq)
        state.derived[derivedId] = DerivedRecord(result, input, event.seq, stale = false, staleReason = null)
        markStale()
        if (emitJudgments) emitCalibrationJudgments(measurementId, result, event.eventId, replayTag)
        return result
    }
    return existing.result
}

private fun emitCalibrationJudgments(measurementId: String, result: CalibrationResult, evidenceEventId: String, replayTag: String?) {
    fun judgment(code: String, decided: Boolean, basis: String, detail: Map<String, String> = emptyMap()) {
        append(
            EventTypes.JUDGMENT_RECORDED,
            "Judgment $code for $measurementId",
            {
                put("code", code)
                put("subjectId", measurementId)
                put("decided", decided)
                put("basis", basis)
                put("evidenceRef", evidenceEventId)
                putJsonObject("detail") { detail.forEach { (k, v) -> put(k, v) } }
            },
            affected = listOf(measurementId, result.derivedId),
            replayTag = replayTag
        )
    }
    when (result.status) {
        "OUTSIDE_CURVE_UNKNOWN" -> judgment(
            JudgmentCodes.OUTSIDE_CURVE_UNKNOWN, true,
            "Raw normalizer is effectively zero; the measurement has no support on the registered curve and is retained as UNKNOWN rather than forced to an edge year.",
            mapOf("normalizer" to result.rawNormalizer.toString())
        )
        "EDGE_TRUNCATED" -> judgment(
            JudgmentCodes.BOUNDARY_TRUNCATED, true,
            "The posterior still has mass at the curve boundary; interpolation inside the domain is shown, no extrapolation is invented.",
            mapOf("truncatedOld" to result.truncatedAtOldBoundary.toString(), "truncatedYoung" to result.truncatedAtYoungBoundary.toString())
        )
    }
    if (result.multimodal) judgment(
        JudgmentCodes.MULTIMODAL_KEPT, true,
        "The calibrated posterior has ${result.modeCount} modes and ${result.intervals95.size} HPD intervals; the distribution is retained in full and is never collapsed to a single mean year.",
        mapOf("modeCount" to result.modeCount.toString(), "intervalCount" to result.intervals95.size.toString())
    )
    if (result.intervals95.any { it.spansBCECE }) judgment(
        JudgmentCodes.BCECE_CROSSING, true,
        "At least one 95% interval crosses the BCE/CE boundary at ${state.eraSystem?.zeroCalBP ?: 1950} calBP and is labeled on both sides.",
        mapOf("intervalIds" to result.intervals95.filter { it.spansBCECE }.joinToString(",") { it.intervalId })
    )
    val tied = result.intervals95.filter { it.tiedWith.isNotEmpty() }
    if (tied.isNotEmpty()) judgment(
        JudgmentCodes.TIED_CALENDAR_INTERVALS, true,
        "Intervals share an identical normalized mass within tolerance; all tied calendar intervals are retained as equal legal candidates.",
        mapOf("tied" to tied.joinToString(",") { it.intervalId })
    )
    if (result.scenarios.size > 1) judgment(
        JudgmentCodes.RESERVOIR_DISTRIBUTION_APPLIED, true,
        "A discrete reservoir-offset mixture was marginalized; each scenario density is preserved alongside the mixture posterior.",
        mapOf("scenarioCount" to result.scenarios.size.toString())
    )
    judgment(
        JudgmentCodes.REPRODUCIBLE_FIXED_VERSION, true,
        "All values derive from fixed curve id ${result.directSources.firstOrNull { it.startsWith("curv") }}, algorithm ${result.algorithmVersion} and a fixed 1-year grid; rerunning under the same fingerprint is bitwise reproducible.",
        mapOf("fingerprint" to result.parameterFingerprint)
    )
}

fun latestDerivedFor(measurementId: String): DerivedRecord? =
    state.derived.values
        .filter { it.input.measurementId == measurementId && !it.result.parameterFingerprint.contains("curve=${state.activeCurveId}").not() }
        .maxByOrNull { it.createdAtEventSeq }

fun allCurrentDerived(): List<DerivedRecord> =
    state.measurements.keys.mapNotNull { m ->
        val input = currentInputFor(m)
        val fp = input?.let { CalibrationEngine.parameterFingerprint(it) }
        state.derived.values.firstOrNull { it.result.parameterFingerprint == fp }
    }

private fun markStale(target: ProjectState = state) {
    for (record in target.derived.values) {
        val curveOk = target.curves[record.input.curve.id] != null
        val meas = target.measurements[record.input.measurementId]
        val measOk = meas != null && meas.ageBP == record.input.ageBP && meas.sigmaBP == record.input.sigmaBP
        val reservoirNow = meas?.id?.let { target.reservoirFor(it) }
        val resOk = reservoirNow?.id == record.input.reservoir?.id
        val activeOk = target.activeCurveId == record.input.curve.id
        record.stale = !(curveOk && measOk && resOk && activeOk)
        record.staleReason = when {
            !curveOk -> "curve removed"
            !activeOk -> "a newer curve version is active"
            !measOk -> "source measurement revised"
            !resOk -> "reservoir-offset assumption changed"
            else -> null
        }
    }
}

// ---- sequence evaluation -------------------------------------------------

@Synchronized
fun evaluateSequence(constraintId: String, replayTag: String? = null): SequenceVerdict? {
    val constraint = state.constraints[constraintId] ?: return null
    if (!constraint.active) return null
    val olderM = state.measurements[constraint.olderMeasurementId]
    val youngerM = state.measurements[constraint.youngerMeasurementId]
    val olderRec = olderM?.let { currentDerived(it.id) }
    val youngerRec = youngerM?.let { currentDerived(it.id) }
    val event = append(
        EventTypes.JUDGMENT_RECORDED,
        "Sequence evaluation for $constraintId",
        {
            put("code", "SEQUENCE_EVALUATION")
            put("subjectId", constraintId)
            put("decided", false)
            put("basis", "Pairwise ordering probability over calibrated posteriors")
            put("evidenceRef", "derived:${olderRec?.result?.derivedId},${youngerRec?.result?.derivedId}")
        },
        affected = listOf(constraintId),
        replayTag = replayTag
    )
    val verdict = SequenceEngine.evaluate(
        constraintId = constraintId,
        olderMeasurementId = constraint.olderMeasurementId,
        youngerMeasurementId = constraint.youngerMeasurementId,
        older = olderRec?.result,
        younger = youngerRec?.result,
        olderReservoirYears = olderM?.id?.let { state.reservoirFor(it)?.meanYears ?: 0.0 } ?: 0.0,
        youngerReservoirYears = youngerM?.id?.let { state.reservoirFor(it)?.meanYears ?: 0.0 } ?: 0.0,
        eventRef = event.eventId
    )
    append(
        EventTypes.JUDGMENT_RECORDED,
        "Sequence verdict ${verdict.status} for $constraintId",
        {
            put("code", if (verdict.status == "CONFLICTED") JudgmentCodes.SEQUENCE_CONFLICT_ADJUSTMENT else JudgmentCodes.SEQUENCE_SATISFIED)
            put("subjectId", constraintId)
            put("decided", verdict.status == "SATISFIED")
            put("basis", verdict.note)
            put("evidenceRef", event.eventId)
            putJsonObject("detail") {
                put("probabilityOlder", if (verdict.probabilityOlder.isNaN()) "NaN" else verdict.probabilityOlder.toString())
                put("status", verdict.status)
                putJsonArray("candidates") { verdict.candidates.forEach { c -> add(json.encodeToJsonElement(c)) } }
            }
        },
        affected = listOf(constraintId) + verdict.candidates.map { it.targetObjectId },
        replayTag = replayTag
    )
    return verdict
}

private fun currentDerived(measurementId: String): DerivedRecord? {
    val input = currentInputFor(measurementId) ?: return null
    val fp = CalibrationEngine.parameterFingerprint(input)
    return state.derived[Ids.derived(fp)]
}

// ---- manual review and reversal ------------------------------------------

@Synchronized
fun reviewCandidate(measurementId: String, action: String, reason: String, decidedBy: String, replayTag: String? = null): StoredEvent {
    val from = reviewStatus(measurementId)
    val to = when (action) {
        "ACCEPT" -> "ACCEPTED"
        "REJECT" -> "REJECTED"
        "HOLD" -> "UNDER_REVIEW"
        else -> error("unknown review action $action")
    }
    return append(
        EventTypes.REVIEW_CANDIDATE,
        "Review $action on $measurementId: $reason",
        {
            put("measurementId", measurementId)
            put("action", action)
            put("reason", reason)
            put("decidedBy", decidedBy)
            put("fromStatus", from)
            put("toStatus", to)
        },
        sources = listOf("review:$decidedBy"),
        affected = listOf(measurementId),
        replayTag = replayTag,
        actorOverride = decidedBy
    )
}

@Synchronized
fun reverseEvent(eventId: String, actorOverride: String = actor, replayTag: String? = null): StoredEvent {
    val original = state.events.firstOrNull { it.eventId == eventId }
        ?: error("event $eventId not found")
    require(original.type in setOf(
        EventTypes.RESERVOIR_SET,
        EventTypes.SEQUENCE_SET,
        EventTypes.EXCLUSION_DECIDED,
        EventTypes.REVIEW_CANDIDATE
    )) { "events of type ${original.type} are part of the immutable source record and cannot be reversed" }
    val event = append(
        EventTypes.EVENT_REVERSED,
        "Undo of ${original.type} ($eventId); history is preserved, only its effect is retracted",
        {
            put("reversedEventId", eventId)
            put("reversedType", original.type)
            put("targetObjectId", original.affectedObjectIds.firstOrNull() ?: "")
            put("effect", "retracted")
        },
        affected = original.affectedObjectIds,
        reversedEventId = eventId,
        replayTag = replayTag,
        actorOverride = actorOverride
    )
    markStale()
    return event
}

fun reviewStatus(measurementId: String): String {
    val relevant = state.events.filter {
        it.type == EventTypes.REVIEW_CANDIDATE &&
            !state.events.any { r -> r.type == EventTypes.EVENT_REVERSED && r.reversedEventId == it.eventId } &&
            json.decodeFromJsonElement<Payloads.ReviewPayload>(it.payload).measurementId == measurementId
    }
    val decision = state.decisionFor(measurementId)
    return when {
        relevant.isNotEmpty() -> json.decodeFromJsonElement<Payloads.ReviewPayload>(relevant.last().payload).toStatus
        decision != null -> decision.decision
        else -> "PENDING"
    }
}

// ---- history replay ------------------------------------------------------

fun replayTo(seq: Int): ProjectState {
    val snap = ProjectState(
        projectId = state.projectId,
        projectName = state.projectName
    )
    val slice = store.loadEvents().filter { it.seq <= seq }
    Reducer.reduce(snap, slice, json)
    for (row in store.loadDerived().filter { it.eventSeq <= seq }) {
        snap.derived[row.result.derivedId] = DerivedRecord(row.result, row.input, row.eventSeq, stale = false, staleReason = null)
    }
    markStale(snap)
    return snap
}

// ---- export / import -----------------------------------------------------

@Synchronized
fun exportBundle(): ExportBundle {
    return ExportBundle(
        projectId = state.projectId,
        projectName = state.projectName,
        exportedAtInstant = now(),
        events = store.loadEvents(),
        derived = store.loadDerived().map { BundleDerivedRow(it.input, it.result, it.eventSeq) }
    )
}

fun exportBundleJson(): String = json.encodeToString(exportBundle())

@Synchronized
fun replaceWithBundle(bundle: ExportBundle, replayTag: String? = "reimport"): ImportReport {
    require(bundle.format == "gsb-workbench-bundle") { "unsupported bundle format ${bundle.format}" }
    store.deleteAll()
    state.curves.clear()
    state.measurements.clear()
    state.reservoirs.clear()
    state.constraints.clear()
    state.decisions.clear()
    state.derived.clear()
    state.events.clear()
    state.eraSystem = null
    state.activeCurveId = null
    state.projectId = bundle.projectId
    state.projectName = bundle.projectName
    state.opened = true

    val ordered = bundle.events.sortedBy { it.seq }
    for (e in ordered) {
        store.appendEvent(e)
        Reducer.apply(state, e, json)
        persistSourcesForEvent(e)
    }
    for (row in bundle.derived.sortedBy { it.eventSeq }) {
        store.putDerived(row.result.derivedId, row.input, row.result, row.eventSeq)
        state.derived[row.result.derivedId] = DerivedRecord(row.result, row.input, row.eventSeq, false, null)
    }
    markStale()
    val expectedIds = ordered.map { it.eventId }
    val actualIds = store.loadEvents().map { it.eventId }
    return ImportReport(
        importedEvents = ordered.size,
        importedDerived = bundle.derived.size,
        eventIdsStable = expectedIds == actualIds,
        firstEventId = actualIds.firstOrNull() ?: "",
        lastEventId = actualIds.lastOrNull() ?: ""
    )
}

private fun persistSourcesForEvent(e: StoredEvent) {
    runCatching {
        when (e.type) {
            EventTypes.CURVE_REGISTERED, EventTypes.CURVE_SUPERSEDED -> {
                val p = json.decodeFromJsonElement<Payloads.CurvePayload>(e.payload)
                store.putSource(p.curve.id, "curve", p.curve.points.first().calBP, p.curve)
            }
            EventTypes.MEASUREMENT_IMPORTED, EventTypes.MEASUREMENT_REVISED -> {
                val p = json.decodeFromJsonElement<Payloads.MeasurementPayload>(e.payload)
                store.putSource(p.measurement.id, "measurement", p.measurement.seq, p.measurement)
            }
            EventTypes.RESERVOIR_SET -> {
                val p = json.decodeFromJsonElement<Payloads.ReservoirPayload>(e.payload)
                store.putSource(p.reservoir.id, "reservoir", p.reservoir.seq, p.reservoir)
            }
            EventTypes.ERA_SET -> {
                val p = json.decodeFromJsonElement<Payloads.EraPayload>(e.payload)
                store.putSource(p.era.id, "era", 0, p.era)
            }
            EventTypes.SEQUENCE_SET -> {
                val p = json.decodeFromJsonElement<Payloads.ConstraintPayload>(e.payload)
                store.putSource(p.constraint.id, "sequence", p.constraint.seq, p.constraint)
            }
            EventTypes.EXCLUSION_DECIDED -> {
                val p = json.decodeFromJsonElement<Payloads.DecisionPayload>(e.payload)
                store.putSource(p.decision.id, "decision", p.decision.seq, p.decision)
            }
        }
    }
}

@Synchronized
fun importBundleJson(text: String): ImportReport =
    replaceWithBundle(json.decodeFromString<ExportBundle>(text))
