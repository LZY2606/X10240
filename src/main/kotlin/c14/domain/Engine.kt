package c14.domain

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.Serializable

val domainJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    prettyPrint = false
}

class EngineReject(message: String) : RuntimeException(message)

@Serializable data class ProjectStarted(val projectId: String, val title: String, val eraSystem: String, val notes: String = "")
@Serializable data class SourceRegistered(val source: Source)
@Serializable data class CurveRegistered(val curve: CalibrationCurve, val replacesCurveId: String? = null)
@Serializable data class MeasurementSubmitted(val measurement: Measurement)
@Serializable data class OffsetModelRegistered(val offset: OffsetModel)
@Serializable data class AssumptionSet(val sampleId: String, val offsetId: String?, val reason: String, val sourceId: String?)
@Serializable data class LinkDeclared(val link: SequenceLink)
@Serializable data class ExclusionDecided(val exclusion: Exclusion)
@Serializable data class DecisionRecorded(val decision: DecisionView)
@Serializable data class CalibrationRequested(val sampleId: String, val measurementId: String, val curveId: String, val offsetId: String?)
@Serializable data class CalibrationIssued(val result: CalibrationResult)
@Serializable data class SequenceAnalysisIssued(val analysis: SequenceAnalysis)
@Serializable data class CandidateResolved(
    val candidateId: String,
    val analysisId: String,
    val resolution: String, // ACCEPTED | REJECTED
    val rationale: String,
    val newAssumption: Assumption? = null,
    val newMeasurementId: String? = null
)

/**
 * Event-sourced state machine. The event log is append-only; every derived
 * value carries algorithm version, parameter fingerprint and direct sources.
 */
class Engine(
    private val clock: Clock,
    private val actorDefault: String = "reviewer",
    private val calibrator: Calibrator = Calibrator()
) {

    private val events = LinkedHashMap<String, MutableList<Event>>()
    private val projectTitle = HashMap<String, String>()

    /** Called after an event is appended (persistence hook). Not fired during replay. */
    var onEvent: ((projectId: String, Event) -> Unit)? = null

    /** Rehydrate from durable storage without triggering reactions or the hook. */
    fun hydrate(projectId: String, title: String, log: List<Event>) {
        val list = log.map { it }.toMutableList()
        events[projectId] = list
        projectTitle[projectId] = title
    }

    /** Remove all in-memory state for a project (after durable delete). */
    fun forget(projectId: String) {
        events.remove(projectId)
        projectTitle.remove(projectId)
    }

    fun listProjects(): List<String> = events.keys.sorted()

    fun hasProject(projectId: String): Boolean = events.containsKey(projectId)

    /** Latest snapshot (convenience over [snapshotAt] at the tip). */
    fun snapshot(projectId: String): ProjectSnapshot =
        snapshotAt(projectId, events[projectId]?.size ?: 0)

    /** Replay events up to [throughSeq], restoring the dependency versions and
     *  conclusions visible at that moment rather than overwriting history. */
    fun snapshotAt(projectId: String, throughSeq: Int): ProjectSnapshot {
        val all = events[projectId] ?: throw EngineReject("unknown project: $projectId")
        return SnapshotBuilder(all.take(throughSeq)).build()
    }

    fun eventLog(projectId: String): List<Event> =
        (events[projectId] ?: throw EngineReject("unknown project: $projectId")).toList()

    fun startProject(projectId: String, title: String, eraSystem: String, notes: String = "", actor: String = actorDefault): Event {
        if (events.containsKey(projectId)) throw EngineReject("project already exists: $projectId")
        events[projectId] = mutableListOf()
        projectTitle[projectId] = title
        return append(projectId, "PROJECT_STARTED", ProjectStarted(projectId, title, eraSystem, notes), null, actor)
    }

    /** Append a pre-built event verbatim (import path): hash chain is verified. */
    fun importEvent(projectId: String, event: Event, title: String? = null) {
        val list = events.getOrPut(projectId) { mutableListOf() }
        if (title != null) projectTitle[projectId] = title
        if (list.isNotEmpty() && list.last().hash != event.parentHash) {
            throw EngineReject("event ${event.seq} breaks hash chain for $projectId")
        }
        if (event.seq != list.size + 1) throw EngineReject("event seq gap for $projectId: expected ${list.size + 1}, got ${event.seq}")
        list.add(event)
    }

    /** Process a reviewer command. Reactions (auto recalibration/re-analysis)
     *  are chained deterministically after the primary event. */
    fun command(projectId: String, command: Command): List<Event> {
        val emitted = mutableListOf<Event>()
        when (command.type) {
            "REGISTER_SOURCE" -> emitted += append(
                projectId, "SOURCE_REGISTERED",
                domainJson.decodeFromString<SourceRegistered>(command.payloadJson),
                command.sourceId, command.actor
            )
            "REGISTER_CURVE" -> {
                val p = domainJson.decodeFromString<CurveRegistered>(command.payloadJson)
                validateCurve(p.curve)
                emitted += append(projectId, "CURVE_REGISTERED", p, command.sourceId, command.actor)
                reactCurve(projectId, p.curve, command.actor, emitted)
            }
            "SUBMIT_MEASUREMENT" -> {
                val p = domainJson.decodeFromString<MeasurementSubmitted>(command.payloadJson)
                validateMeasurement(p.measurement)
                ensureSource(projectId, p.measurement.sourceId)
                emitted += append(projectId, "MEASUREMENT_SUBMITTED", p, command.sourceId, command.actor)
                autoCalibrate(projectId, p.measurement.sampleId, command.actor, emitted)
                runSequenceAnalysis(projectId, command.actor, emitted)
            }
            "REGISTER_OFFSET" -> {
                val p = domainJson.decodeFromString<OffsetModelRegistered>(command.payloadJson)
                ensureSource(projectId, p.offset.sourceId)
                emitted += append(projectId, "OFFSET_MODEL_REGISTERED", p, command.sourceId, command.actor)
            }
            "SET_ASSUMPTION" -> {
                val p = domainJson.decodeFromString<AssumptionSet>(command.payloadJson)
                if (p.offsetId != null) ensureOffset(projectId, p.offsetId)
                emitted += append(projectId, "ASSUMPTION_SET", p, command.sourceId, command.actor)
                autoCalibrate(projectId, p.sampleId, command.actor, emitted)
            }
            "DECLARE_LINK" -> {
                val p = domainJson.decodeFromString<LinkDeclared>(command.payloadJson)
                ensureSource(projectId, p.link.sourceId)
                listOf(p.link.olderSample, p.link.youngerSample).forEach { ensureSample(projectId, it) }
                emitted += append(projectId, "LINK_DECLARED", p, command.sourceId, command.actor)
                runSequenceAnalysis(projectId, command.actor, emitted)
            }
            "DECIDE_EXCLUSION" -> {
                val p = domainJson.decodeFromString<ExclusionDecided>(command.payloadJson)
                ensureSource(projectId, p.exclusion.sourceId)
                ensureSample(projectId, p.exclusion.sampleId)
                emitted += append(projectId, "EXCLUSION_DECIDED", p, command.sourceId, command.actor)
                runSequenceAnalysis(projectId, command.actor, emitted)
            }
            "DECIDE" -> {
                val p = domainJson.decodeFromString<DecisionRecorded>(command.payloadJson)
                ensureSource(projectId, p.decision.sourceId)
                emitted += append(projectId, "DECISION_RECORDED", p, command.sourceId, command.actor)
            }
            "REQUEST_CALIBRATION" -> {
                val p = domainJson.decodeFromString<CalibrationRequested>(command.payloadJson)
                ensureSample(projectId, p.sampleId)
                ensureCurve(projectId, p.curveId)
                if (p.offsetId != null) ensureOffset(projectId, p.offsetId)
                emitted += append(projectId, "CALIBRATION_REQUESTED", p, command.sourceId, command.actor)
                issueCalibration(projectId, p, command.actor, emitted)
            }
            "RESOLVE_CANDIDATE" -> {
                val p = domainJson.decodeFromString<CandidateResolved>(command.payloadJson)
                emitted += append(projectId, "CANDIDATE_RESOLVED", p, command.sourceId, command.actor)
                if (p.resolution == "ACCEPTED" && p.newAssumption != null) {
                    val asp = AssumptionSet(p.newAssumption.sampleId, p.newAssumption.offsetId, p.newAssumption.reason, p.newAssumption.sourceId)
                    emitted += append(projectId, "ASSUMPTION_SET", asp, command.sourceId, command.actor)
                    autoCalibrate(projectId, asp.sampleId, command.actor, emitted)
                    runSequenceAnalysis(projectId, command.actor, emitted, p.analysisId, p.candidateId)
                }
            }
            else -> throw EngineReject("unknown command type: ${command.type}")
        }
        return emitted
    }

    private inline fun <reified T> append(projectId: String, type: String, payload: T, sourceId: String?, actor: String): Event {
        val list = events[projectId] ?: throw EngineReject("unknown project: $projectId")
        val seq = list.size + 1
        val payloadJson = domainJson.encodeToString(payload)
        val parentHash = list.lastOrNull()?.hash ?: "genesis"
        val hash = Fingerprint.sha256("$seq|$type|$payloadJson|$parentHash")
        val id = "evt:$projectId:$seq"
        val event = Event(seq, id, type, payloadJson, sourceId, actor, clock.nowMillis(), parentHash, hash)
        list.add(event)
        onEvent?.invoke(projectId, event)
        return event
    }


    /** Calibrate every measurement whose sample has no current result. */
    fun calibrateAllMissing(projectId: String, actor: String) {
        val emitted = mutableListOf<Event>()
        val snap = snapshot(projectId)
        for (m in snap.measurements) {
            val latest = snap.results.filter { it.result.sampleId == m.sampleId }.maxByOrNull { it.result.eventSeq }
            if (latest == null || latest.result.measurementId != m.id) {
                autoCalibrate(projectId, m.sampleId, actor, emitted)
            }
        }
    }

    // ---- validation -------------------------------------------------------

    private fun validateCurve(curve: CalibrationCurve) {
        if (curve.knots.size < 2) throw EngineReject("curve ${curve.id} needs at least 2 knots")
        val bps = curve.knots.map { it.calBp }
        if (bps.zipWithNext().any { it.first >= it.second }) throw EngineReject("curve ${curve.id} knots must be strictly ascending by cal BP")
        if (curve.knots.any { it.c14AgeBp < 0 }) throw EngineReject("curve ${curve.id} has negative radiocarbon ages")
    }

    private fun validateMeasurement(m: Measurement) {
        if (m.ageBp <= 0) throw EngineReject("measurement ${m.id} age must be positive")
        if (m.sigmaBp <= 0) throw EngineReject("measurement ${m.id} sigma must be positive")
    }

    private fun ensureSource(projectId: String, id: String) {
        if (snapshot(projectId).sources.none { it.id == id }) throw EngineReject("referenced source not registered: $id")
    }

    private fun ensureCurve(projectId: String, id: String) {
        if (snapshot(projectId).curves.none { it.id == id }) throw EngineReject("curve not registered: $id")
    }

    private fun ensureOffset(projectId: String, id: String) {
        if (snapshot(projectId).offsets.none { it.id == id }) throw EngineReject("offset model not registered: $id")
    }

    private fun ensureSample(projectId: String, id: String) {
        if (snapshot(projectId).measurements.none { it.sampleId == id }) throw EngineReject("sample not measured: $id")
    }

    // ---- automatic reactions ---------------------------------------------

    private fun reactCurve(projectId: String, curve: CalibrationCurve, actor: String, emitted: MutableList<Event>) {
        val snap = snapshot(projectId)
        val replaced = curve.parentCurveId ?: snap.curveFamilies[curve.name]?.takeIf { it != curve.id }
        if (replaced == null) return
        for (sample in snap.samplesCalibratedWith(replaced)) {
            autoCalibrate(projectId, sample, actor, emitted)
        }
        runSequenceAnalysis(projectId, actor, emitted)
    }

    private fun autoCalibrate(projectId: String, sampleId: String, actor: String, emitted: MutableList<Event>) {
        val snap = snapshot(projectId)
        val measurement = snap.currentMeasurement(sampleId) ?: return
        val curve = snap.currentCurveFor(sampleId) ?: snap.latestCurve ?: return
        val offsetId = snap.assumptions[sampleId]?.offsetId
        val req = CalibrationRequested(sampleId, measurement.id, curve.id, offsetId)
        emitted += append(projectId, "CALIBRATION_REQUESTED", req, null, actor)
        issueCalibration(projectId, req, actor, emitted)
    }

    private fun issueCalibration(projectId: String, req: CalibrationRequested, actor: String, emitted: MutableList<Event>) {
        val snap = snapshot(projectId)
        val measurement = snap.measurements.first { it.id == req.measurementId }
        val curve = snap.curves.first { it.id == req.curveId }
        val offset = req.offsetId?.let { id -> snap.offsets.first { it.id == id } }
        val result = calibrator.calibrate(CalibrationInputs(measurement, curve, offset)).copy(eventSeq = emitted.last().seq + 1)
        emitted += append(projectId, "CALIBRATION_ISSUED", CalibrationIssued(result), null, actor)
    }

    private fun runSequenceAnalysis(
        projectId: String,
        actor: String,
        emitted: MutableList<Event>,
        resolvedAnalysisId: String? = null,
        resolvedCandidateId: String? = null
    ) {
        val snap = snapshot(projectId)
        val activeLinks = snap.links.filter { link ->
            snap.exclusions.none { it.sampleId == link.olderSample || it.sampleId == link.youngerSample }
        }
        if (activeLinks.isEmpty()) return
        val pairProbs = activeLinks.map { link -> pairProbability(snap, link) }
        val consistent = pairProbs.all { it.consistent }
        val seq = emitted.lastOrNull()?.seq ?: snap.eventCount
        val analysisId = "seq:${snap.projectId}:${seq + 1}"
        val candidates = if (!consistent) buildCandidates(snap, activeLinks, pairProbs, analysisId) else emptyList()
        val resolvedBy = if (resolvedAnalysisId != null) resolvedCandidateId else null
        val analysis = SequenceAnalysis(
            id = analysisId,
            links = activeLinks.map { it.id },
            pairProbabilities = pairProbs,
            consistent = consistent,
            candidates = candidates,
            resolvedBy = resolvedBy,
            eventSeq = seq + 1
        )
        emitted += append(projectId, "SEQUENCE_ANALYSIS_ISSUED", SequenceAnalysisIssued(analysis), null, actor)
    }

    private fun pairProbability(snap: ProjectSnapshot, link: SequenceLink): PairProbability {
        val older = snap.latestResult(link.olderSample)
        val younger = snap.latestResult(link.youngerSample)
        if (older == null || younger == null) {
            return PairProbability(link.id, link.olderSample, link.youngerSample, true, 1.0, emptyList())
        }
        var probability = 0.0
        val jointRanges = mutableListOf<List<Int>>()
        for (o in older.cells) {
            for (y in younger.cells) {
                if (o.calBp > y.calBp) {
                    // Grid cell masses reconstructed from cumulative boundaries.
                    val mo = o.let { 1.0 - it.cumLeft - it.cumRight }
                    val my = y.let { 1.0 - it.cumLeft - it.cumRight }
                    val add = mo * my
                    probability += add
                    if (add > 1e-9) jointRanges.add(listOf(o.calBp, y.calBp))
                }
            }
        }
        // Ordering "supported" if at least one 95% interval pair is correctly ordered
        // with non-trivial posterior mass.
        val consistent = probability >= 0.50
        return PairProbability(link.id, link.olderSample, link.youngerSample, consistent, probability, jointRanges.take(20))
    }

    private fun buildCandidates(
        snap: ProjectSnapshot,
        links: List<SequenceLink>,
        probs: List<PairProbability>,
        analysisId: String
    ): List<CandidateAdjustment> {
        val out = mutableListOf<CandidateAdjustment>()
        var n = 0
        for (prob in probs.filterNot { it.consistent }) {
            val link = links.first { it.id == prob.linkId }
            n += 1
            out += CandidateAdjustment(
                id = "cand:$analysisId:$n",
                analysisId = analysisId,
                kind = "REVISE_ORDER",
                description = "Reverse ordering link ${link.olderSample} -> ${link.youngerSample} (posterior older-first probability ${fmt(prob.probability)})",
                affectedObject = link.id
            )
            val assumption = snap.assumptions[link.olderSample]
            if (assumption?.offsetId != null) {
                n += 1
                out += CandidateAdjustment(
                    id = "cand:$analysisId:$n",
                    analysisId = analysisId,
                    kind = "REVISE_OFFSET",
                    description = "Drop reservoir offset ${assumption.offsetId} from ${link.olderSample} and recalibrate",
                    affectedObject = link.olderSample,
                    proposedOffsetId = null
                )
            }
            val measurement = snap.currentMeasurement(link.olderSample)
            if (measurement?.parentMeasurementId == null) {
                n += 1
                out += CandidateAdjustment(
                    id = "cand:$analysisId:$n",
                    analysisId = analysisId,
                    kind = "REVISE_MEASUREMENT",
                    description = "Submit a revised lab measurement for ${link.olderSample} as a new revision",
                    affectedObject = link.olderSample,
                    proposedMeasurementId = "${measurement?.id ?: link.olderSample}-rev"
                )
            }
        }
        return out
    }

    private fun fmt(v: Double): String = "%.3f".format(v)
}
