package bench

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

val JsonFmt = Json { ignoreUnknownKeys = true; encodeDefaults = true }

fun sha256(s: String): String =
    MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

interface Clock { fun now(): String }
class SystemClock : Clock { override fun now(): String = Instant.now().toString() }
class VirtualClock(startMs: Long = 1_700_000_000_000L, private val stepMs: Long = 1000L) : Clock {
    private var t = startMs
    override fun now(): String { t += stepMs; return Instant.ofEpochMilli(t).toString() }
}

interface IdGen { fun next(prefix: String): String }
class UuidIdGen : IdGen { override fun next(prefix: String): String = "$prefix-${UUID.randomUUID()}" }
class SeqIdGen : IdGen {
    private var n = 0
    override fun next(prefix: String): String = prefix + "-" + (++n).toString().padStart(4, '0')
}

object ET {
    const val CALENDAR_DEFINED = "calendar.defined"
    const val CURVE_REGISTERED = "curve.registered"
    const val OFFSET_DEFINED = "offset.defined"
    const val MEASUREMENT_IMPORTED = "measurement.imported"
    const val MEASUREMENT_REVISED = "measurement.revised"
    const val CONSTRAINT_ADDED = "sequence.constraint.added"
    const val CALIBRATION_COMPUTED = "calibration.computed"
    const val DECISION_MADE = "decision.made"
    const val DECISION_REVERTED = "decision.reverted"
    const val EXCLUSION_DECIDED = "exclusion.decided"
    const val CANDIDATE_GENERATED = "sequence.candidate.generated"
    const val QUARANTINED = "input.quarantined"
    val ALL = setOf(CALENDAR_DEFINED, CURVE_REGISTERED, OFFSET_DEFINED, MEASUREMENT_IMPORTED,
        MEASUREMENT_REVISED, CONSTRAINT_ADDED, CALIBRATION_COMPUTED, DECISION_MADE, DECISION_REVERTED,
        EXCLUSION_DECIDED, CANDIDATE_GENERATED, QUARANTINED)
}

@Serializable
data class EventEnvelope(val id: String, val seq: Int, val ts: String, val type: String, val payload: JsonObject)

// ---------------- payload schemas ----------------

@Serializable data class CalendarDefined(val systemId: String, val epochCE: Int, val label: String, val source: String)
@Serializable data class CurveRegistered(val curveId: String, val version: Int, val name: String,
    val unit: String, val basis: String, val points: List<List<Double>>, val source: String,
    val builtinKey: String? = null)
@Serializable data class OffsetDefined(val offsetId: String, val version: Int, val parentVersion: Int? = null,
    val label: String, val mean: Double, val sigma: Double, val unit: String, val source: String)
@Serializable data class MeasurementImported(val measurementId: String, val version: Int = 1,
    val labCode: String, val ageBP: Double, val sigma: Double, val unit: String, val raw: String, val source: String)
@Serializable data class MeasurementRevised(val measurementId: String, val version: Int, val parentVersion: Int,
    val labCode: String, val ageBP: Double, val sigma: Double, val unit: String, val raw: String,
    val source: String, val reason: String)
@Serializable data class ConstraintAdded(val constraintId: String, val olderId: String, val youngerId: String,
    val source: String, val note: String)
@Serializable data class HpdIntervalS(val level: Double, val loCalBp: Int, val hiCalBp: Int,
    val mass: Double, val likelihoodSum: Double)
@Serializable data class CalibrationComputed(val resultId: String, val measurementId: String,
    val measurementVersion: Int, val curveId: String, val curveVersion: Int, val offsetId: String,
    val offsetVersion: Int, val offsetMean: Double, val offsetSigma: Double, val algorithm: String,
    val fingerprint: String, val gridFrom: Int, val gridTo: Int, val gridStep: Int,
    val normalization: Double, val density: List<Double>, val intervals: List<HpdIntervalS>,
    val peaks: List<Int>, val sources: Map<String, String>)
@Serializable data class DecisionMade(val decisionId: String, val targetType: String, val targetId: String,
    val verdict: String, val rationale: String, val reviewer: String)
@Serializable data class DecisionReverted(val decisionId: String, val reason: String)
@Serializable data class ExclusionDecided(val exclusionId: String, val measurementId: String,
    val excluded: Boolean, val reason: String, val source: String)
@Serializable data class CandidateGenerated(val candidateId: String, val constraintId: String, val kind: String,
    val detail: String, val olderResultId: String, val youngerResultId: String, val pConstraintHolds: Double)
@Serializable data class Quarantined(val quarantineId: String, val rawJson: String, val diagnostic: String)

// ---------------- domain records (in-memory state) ----------------

data class CurvePoint(val calBp: Int, val bpAge: Double, val sigma: Double)
data class CurveVersion(val curveId: String, val version: Int, val name: String, val unit: String,
    val basis: String, val points: List<CurvePoint>, val source: String, val eventId: String)
data class MeasurementVersion(val measurementId: String, val version: Int, val parentVersion: Int?,
    val labCode: String, val ageBP: Double, val sigma: Double, val unit: String, val raw: String,
    val source: String, val eventId: String)
data class OffsetVersion(val offsetId: String, val version: Int, val parentVersion: Int?, val label: String,
    val mean: Double, val sigma: Double, val unit: String, val source: String, val eventId: String)
data class CalendarSystem(val systemId: String, val epochCE: Int, val label: String, val source: String, val eventId: String)
data class SequenceConstraint(val constraintId: String, val olderId: String, val youngerId: String,
    val source: String, val note: String, val eventId: String)
data class CalibrationResult(val resultId: String, val measurementId: String, val measurementVersion: Int,
    val curveId: String, val curveVersion: Int, val offsetId: String, val offsetVersion: Int,
    val offsetMean: Double, val offsetSigma: Double, val algorithm: String, val fingerprint: String,
    val gridFrom: Int, val gridTo: Int, val gridStep: Int, val normalization: Double,
    val density: List<Double>, val intervals: List<HpdIntervalS>, val peaks: List<Int>,
    val sources: Map<String, String>, val eventId: String, val seq: Int)
data class Decision(val decisionId: String, val targetType: String, val targetId: String, val verdict: String,
    val rationale: String, val reviewer: String, val eventId: String, var reverted: Boolean = false)
data class Exclusion(val exclusionId: String, val measurementId: String, val excluded: Boolean,
    val reason: String, val source: String, val eventId: String)
data class SeqCandidate(val candidateId: String, val constraintId: String, val kind: String, val detail: String,
    val olderResultId: String, val youngerResultId: String, val pConstraintHolds: Double, val eventId: String)
data class Quarantine(val quarantineId: String, val rawJson: String, val diagnostic: String, val eventId: String)

class ProjectState {
    var currentCurveId: String? = null
    val calendars = mutableListOf<CalendarSystem>()
    val curves = linkedMapOf<String, MutableList<CurveVersion>>()
    val measurements = linkedMapOf<String, MutableList<MeasurementVersion>>()
    val offsets = linkedMapOf<String, MutableList<OffsetVersion>>()
    val constraints = linkedMapOf<String, SequenceConstraint>()
    val calibrations = linkedMapOf<String, CalibrationResult>()
    val decisions = linkedMapOf<String, Decision>()
    val exclusions = mutableListOf<Exclusion>()
    val candidates = linkedMapOf<String, SeqCandidate>()
    val quarantines = mutableListOf<Quarantine>()

    fun calendar(): CalendarSystem? = calendars.lastOrNull()
    fun headMeasurement(id: String): MeasurementVersion? = measurements[id]?.maxByOrNull { it.version }
    fun headCurve(id: String): CurveVersion? = curves[id]?.maxByOrNull { it.version }
    fun headOffset(id: String): OffsetVersion? = offsets[id]?.maxByOrNull { it.version }
    fun exclusionFor(measurementId: String): Exclusion? = exclusions.lastOrNull { it.measurementId == measurementId }
    fun activeDecisions(): List<Decision> = decisions.values.filter { !it.reverted }
    fun decisionsFor(targetType: String, targetId: String): List<Decision> =
        decisions.values.filter { it.targetType == targetType && it.targetId == targetId }
    fun candidateStatus(candidateId: String): String {
        val ds = decisionsFor("candidate", candidateId).filter { !it.reverted }
        return ds.lastOrNull()?.verdict ?: "open"
    }

    /** 局部输入变化只使依赖它的结果过期；过期结果仍可查看。 */
    fun isStale(c: CalibrationResult): Boolean {
        val m = headMeasurement(c.measurementId) ?: return true
        if (m.version != c.measurementVersion) return true
        val cv = headCurve(c.curveId) ?: return true
        if (cv.version != c.curveVersion) return true
        val ov = headOffset(c.offsetId) ?: return true
        if (ov.version != c.offsetVersion) return true
        return false
    }
}

fun applyEvent(s: ProjectState, e: EventEnvelope) {
    when (e.type) {
        ET.CALENDAR_DEFINED -> {
            val p = JsonFmt.decodeFromJsonElement<CalendarDefined>(e.payload)
            s.calendars.add(CalendarSystem(p.systemId, p.epochCE, p.label, p.source, e.id))
        }
        ET.CURVE_REGISTERED -> {
            val p = JsonFmt.decodeFromJsonElement<CurveRegistered>(e.payload)
            val pts = p.points.map { CurvePoint(it[0].toInt(), it[1], it[2]) }
            s.curves.getOrPut(p.curveId) { mutableListOf() }
                .add(CurveVersion(p.curveId, p.version, p.name, p.unit, p.basis, pts, p.source, e.id))
            s.currentCurveId = p.curveId
        }
        ET.OFFSET_DEFINED -> {
            val p = JsonFmt.decodeFromJsonElement<OffsetDefined>(e.payload)
            s.offsets.getOrPut(p.offsetId) { mutableListOf() }
                .add(OffsetVersion(p.offsetId, p.version, p.parentVersion, p.label, p.mean, p.sigma, p.unit, p.source, e.id))
        }
        ET.MEASUREMENT_IMPORTED -> {
            val p = JsonFmt.decodeFromJsonElement<MeasurementImported>(e.payload)
            s.measurements.getOrPut(p.measurementId) { mutableListOf() }
                .add(MeasurementVersion(p.measurementId, p.version, null, p.labCode, p.ageBP, p.sigma, p.unit, p.raw, p.source, e.id))
        }
        ET.MEASUREMENT_REVISED -> {
            val p = JsonFmt.decodeFromJsonElement<MeasurementRevised>(e.payload)
            s.measurements.getOrPut(p.measurementId) { mutableListOf() }
                .add(MeasurementVersion(p.measurementId, p.version, p.parentVersion, p.labCode, p.ageBP, p.sigma, p.unit, p.raw, p.source, e.id))
        }
        ET.CONSTRAINT_ADDED -> {
            val p = JsonFmt.decodeFromJsonElement<ConstraintAdded>(e.payload)
            s.constraints[p.constraintId] = SequenceConstraint(p.constraintId, p.olderId, p.youngerId, p.source, p.note, e.id)
        }
        ET.CALIBRATION_COMPUTED -> {
            val p = JsonFmt.decodeFromJsonElement<CalibrationComputed>(e.payload)
            s.calibrations[p.resultId] = CalibrationResult(p.resultId, p.measurementId, p.measurementVersion,
                p.curveId, p.curveVersion, p.offsetId, p.offsetVersion, p.offsetMean, p.offsetSigma, p.algorithm,
                p.fingerprint, p.gridFrom, p.gridTo, p.gridStep, p.normalization, p.density, p.intervals,
                p.peaks, p.sources, e.id, e.seq)
        }
        ET.DECISION_MADE -> {
            val p = JsonFmt.decodeFromJsonElement<DecisionMade>(e.payload)
            s.decisions[p.decisionId] = Decision(p.decisionId, p.targetType, p.targetId, p.verdict, p.rationale, p.reviewer, e.id)
        }
        ET.DECISION_REVERTED -> {
            val p = JsonFmt.decodeFromJsonElement<DecisionReverted>(e.payload)
            s.decisions[p.decisionId]?.reverted = true
        }
        ET.EXCLUSION_DECIDED -> {
            val p = JsonFmt.decodeFromJsonElement<ExclusionDecided>(e.payload)
            s.exclusions.add(Exclusion(p.exclusionId, p.measurementId, p.excluded, p.reason, p.source, e.id))
        }
        ET.CANDIDATE_GENERATED -> {
            val p = JsonFmt.decodeFromJsonElement<CandidateGenerated>(e.payload)
            s.candidates[p.candidateId] = SeqCandidate(p.candidateId, p.constraintId, p.kind, p.detail,
                p.olderResultId, p.youngerResultId, p.pConstraintHolds, e.id)
        }
        ET.QUARANTINED -> {
            val p = JsonFmt.decodeFromJsonElement<Quarantined>(e.payload)
            s.quarantines.add(Quarantine(p.quarantineId, p.rawJson, p.diagnostic, e.id))
        }
    }
}

fun fold(events: List<EventEnvelope>): ProjectState {
    val s = ProjectState()
    for (e in events) applyEvent(s, e)
    return s
}
