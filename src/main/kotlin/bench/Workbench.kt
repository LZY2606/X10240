package bench

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

class WorkbenchException(message: String) : RuntimeException(message)

@Serializable
data class ArchiveMeta(val title: String = "", val narrative: String = "", val domain: String = "",
    val exportedEvents: Int = 0)

@Serializable
data class Archive(val format: String, val meta: ArchiveMeta? = null,
    val events: List<EventEnvelope>? = null, val steps: List<JsonObject>? = null)

data class ImportReport(val ok: Boolean, val errors: List<String>, val imported: Int, val quarantined: Int)

inline fun <reified T> toJsonElement(v: T): JsonElement = JsonFmt.encodeToJsonElement(v)

class Workbench(val store: Store, val clock: Clock, val idGen: IdGen) {
    var events: List<EventEnvelope> = store.loadEvents()
        private set
    var state: ProjectState = fold(events)
        private set

    val FORMAT = "carbon-bench-archive/1"
    private val prettyJson = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }

    fun reload() {
        events = store.loadEvents()
        state = fold(events)
    }

    fun stateAt(seq: Int): ProjectState = fold(events.filter { it.seq <= seq })

    fun isEmpty(): Boolean = events.isEmpty()

    @Synchronized
    internal fun append(type: String, payload: JsonObject, fixedId: String? = null, fixedTs: String? = null): EventEnvelope {
        val seq = events.size + 1
        val e = EventEnvelope(fixedId ?: idGen.next("evt"), seq, fixedTs ?: clock.now(), type, payload)
        store.append(e)
        events = events + e
        applyEvent(state, e)
        return e
    }

    // ---------------- direct-source commands ----------------

    fun defineCalendar(systemId: String = "cal-sys", label: String, source: String): EventEnvelope {
        return append(ET.CALENDAR_DEFINED, toJsonElement(CalendarDefined(systemId, 1950, label, source)).jsonObject)
    }

    fun registerBuiltinCurve(key: String, source: String, version: Int? = null): EventEnvelope {
        if (key !in CurveData.KEYS) throw WorkbenchException("未知内置曲线: $key")
        val existing = state.curves[CurveData.CURVE_ID]?.size ?: 0
        val ver = version ?: (existing + 1)
        val pts = CurveData.points(key)
        return registerCurve(CurveData.CURVE_ID, ver, "$key（离线合成，用于可复现校准）", pts, source, key)
    }

    fun registerCurve(curveId: String, version: Int, name: String, points: List<CurvePoint>,
                      source: String, builtinKey: String? = null): EventEnvelope {
        val p = CurveRegistered(curveId, version, name, "14C yr BP", "cal BP",
            points.map { listOf(it.calBp.toDouble(), it.bpAge, it.sigma) }, source, builtinKey)
        return append(ET.CURVE_REGISTERED, toJsonElement(p).jsonObject)
    }

    fun defineOffset(offsetId: String, label: String, mean: Double, sigma: Double, source: String): EventEnvelope {
        val ver = (state.offsets[offsetId]?.size ?: 0) + 1
        val parent = state.headOffset(offsetId)?.version
        val p = OffsetDefined(offsetId, ver, parent, label, mean, sigma, "14C yr", source)
        return append(ET.OFFSET_DEFINED, toJsonElement(p).jsonObject)
    }

    fun importMeasurement(measurementId: String, labCode: String, ageBP: Double, sigma: Double,
                          raw: String, source: String): EventEnvelope {
        if (measurementId in state.measurements)
            throw WorkbenchException("测量 $measurementId 已存在；修订必须以新版本提交，不能覆盖原输入")
        val p = MeasurementImported(measurementId, 1, labCode, ageBP, sigma, "14C yr BP", raw, source)
        return append(ET.MEASUREMENT_IMPORTED, toJsonElement(p).jsonObject)
    }

    fun reviseMeasurement(measurementId: String, ageBP: Double, sigma: Double, raw: String,
                          source: String, reason: String): EventEnvelope {
        val parent = state.headMeasurement(measurementId)
            ?: throw WorkbenchException("测量不存在，不能修订: $measurementId")
        val ver = parent.version + 1
        val p = MeasurementRevised(measurementId, ver, parent.version, parent.labCode, ageBP, sigma,
            "14C yr BP", raw, source, reason)
        return append(ET.MEASUREMENT_REVISED, toJsonElement(p).jsonObject)
    }

    fun addConstraint(olderId: String, youngerId: String, source: String, note: String): EventEnvelope {
        state.headMeasurement(olderId) ?: throw WorkbenchException("层序引用的测量不存在: $olderId")
        state.headMeasurement(youngerId) ?: throw WorkbenchException("层序引用的测量不存在: $youngerId")
        val id = idGen.next("con")
        return append(ET.CONSTRAINT_ADDED, toJsonElement(ConstraintAdded(id, olderId, youngerId, source, note)).jsonObject)
    }

    fun decideExclusion(measurementId: String, excluded: Boolean, reason: String, source: String): EventEnvelope {
        state.headMeasurement(measurementId) ?: throw WorkbenchException("测量不存在: $measurementId")
        val id = idGen.next("exc")
        return append(ET.EXCLUSION_DECIDED,
            toJsonElement(ExclusionDecided(id, measurementId, excluded, reason, source)).jsonObject)
    }

    // ---------------- derived: calibration ----------------

    fun computeCalibration(measurementId: String, offsetId: String, resultId: String? = null): CalibrationResult {
        val m = state.headMeasurement(measurementId) ?: throw WorkbenchException("测量不存在: $measurementId")
        val curveId = state.currentCurveId ?: throw WorkbenchException("尚未注册校准曲线")
        val curve = state.headCurve(curveId) ?: throw WorkbenchException("曲线版本缺失")
        val off = state.headOffset(offsetId) ?: throw WorkbenchException("偏移假设不存在: $offsetId")
        val comp = Calibration.calibrate(m.ageBP, m.sigma, off.mean, off.sigma, curve.points)
        val fp = Calibration.fingerprint(Calibration.ALGORITHM, m.measurementId, m.version,
            curve.curveId, curve.version, off.offsetId, off.version, off.mean, off.sigma,
            m.ageBP, m.sigma, comp.gridFrom, comp.gridTo, comp.gridStep)
        val rid = resultId ?: idGen.next("cal")
        val p = CalibrationComputed(rid, m.measurementId, m.version, curve.curveId, curve.version,
            off.offsetId, off.version, off.mean, off.sigma, Calibration.ALGORITHM, fp,
            comp.gridFrom, comp.gridTo, comp.gridStep, comp.normalization, comp.density,
            comp.intervals, comp.peaks,
            mapOf("measurement" to m.eventId, "curve" to curve.eventId, "offset" to off.eventId))
        append(ET.CALIBRATION_COMPUTED, toJsonElement(p).jsonObject)
        return state.calibrations[rid]!!
    }

    fun latestCalibration(measurementId: String, s: ProjectState = state): CalibrationResult? =
        s.calibrations.values.filter { it.measurementId == measurementId }.maxByOrNull { it.seq }

    // ---------------- decisions / sequence ----------------

    fun decide(targetType: String, targetId: String, verdict: String, rationale: String,
               reviewer: String, decisionId: String? = null): EventEnvelope {
        if (verdict !in setOf("accept", "reject", "adjust"))
            throw WorkbenchException("未知裁定: $verdict")
        val exists = when (targetType) {
            "calibration" -> targetId in state.calibrations
            "candidate" -> targetId in state.candidates
            "measurement" -> state.headMeasurement(targetId) != null
            else -> false
        }
        if (!exists) throw WorkbenchException("裁定目标不存在: $targetType/$targetId")
        val id = decisionId ?: idGen.next("dec")
        return append(ET.DECISION_MADE,
            toJsonElement(DecisionMade(id, targetType, targetId, verdict, rationale, reviewer)).jsonObject)
    }

    fun revertDecision(decisionId: String, reason: String): EventEnvelope {
        state.decisions[decisionId] ?: throw WorkbenchException("裁定不存在: $decisionId")
        return append(ET.DECISION_REVERTED, toJsonElement(DecisionReverted(decisionId, reason)).jsonObject)
    }

    fun adjustOffsetAndRecalibrate(resultId: String, newMean: Double, newSigma: Double,
                                   rationale: String, reviewer: String): CalibrationResult {
        val old = state.calibrations[resultId] ?: throw WorkbenchException("校准结果不存在: $resultId")
        defineOffset(old.offsetId, state.headOffset(old.offsetId)!!.label, newMean, newSigma,
            source = "复核参数调整（$reviewer）：$rationale")
        decide("calibration", resultId, "adjust", rationale, reviewer)
        return computeCalibration(old.measurementId, old.offsetId)
    }

    @Synchronized
    fun analyzeSequences(): List<ConflictAnalysis> {
        val out = mutableListOf<ConflictAnalysis>()
        for (c in state.constraints.values) {
            val older = latestCalibration(c.olderId) ?: continue
            val younger = latestCalibration(c.youngerId) ?: continue
            val a = SequenceAnalysis.analyze(c, older, younger)
            out += a
            if (a.conflict) {
                for (spec in a.candidateSpecs) {
                    val dup = state.candidates.values.any {
                        it.constraintId == c.constraintId && it.kind == spec.kind &&
                            state.candidateStatus(it.candidateId) == "open"
                    }
                    if (!dup) {
                        val cid = idGen.next("cand")
                        append(ET.CANDIDATE_GENERATED, toJsonElement(CandidateGenerated(
                            cid, c.constraintId, spec.kind, spec.detail,
                            older.resultId, younger.resultId, a.pConstraintHolds)).jsonObject)
                    }
                }
            }
        }
        return out
    }

    // ---------------- export / reset ----------------

    fun exportJson(): String {
        val meta = ArchiveMeta(title = "放射性碳校准工作台事件档案",
            domain = "radiocarbon-calibration", exportedEvents = events.size)
        return prettyJson.encodeToString(Archive.serializer(), Archive(FORMAT, meta, events = events))
    }

    @Synchronized
    fun reset() {
        store.clear()
        reload()
    }
}
