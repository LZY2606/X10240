package workbench

class Projections {
    val measurements = LinkedHashMap<String, MutableList<MeasurementV>>()
    val curves = LinkedHashMap<String, MutableList<CurveV>>()
    val offsets = LinkedHashMap<String, MutableList<OffsetV>>()
    val constraints = LinkedHashMap<String, ConstraintV>()
    val exclusions = LinkedHashMap<String, ExclusionV>()
    var eraSystem: EraSystemV? = null
    val results = LinkedHashMap<String, CalibrationResult>()
    val stratChecks = mutableListOf<StratCheckResult>()
    val decisions = LinkedHashMap<String, DecisionV>()
    val undos = mutableListOf<UndoV>()

    fun apply(p: Payload) {
        when (p) {
            is Payload.MeasurementSubmitted -> measurements.getOrPut(p.m.measurementId) { mutableListOf() }.add(p.m)
            is Payload.CurveRegistered -> curves.getOrPut(p.c.curveId) { mutableListOf() }.add(p.c)
            is Payload.OffsetSubmitted -> offsets.getOrPut(p.o.offsetId) { mutableListOf() }.add(p.o)
            is Payload.ConstraintSubmitted -> constraints[p.c.id] = p.c
            is Payload.ExclusionSubmitted -> exclusions[p.e.id] = p.e
            is Payload.EraSystemSet -> eraSystem = p.e
            is Payload.CalibrationComputed -> results[p.r.id] = p.r
            is Payload.StratChecked -> stratChecks.add(p.s)
            is Payload.DecisionMade -> decisions[p.d.id] = p.d
            is Payload.DecisionUndone -> undos.add(p.u)
        }
    }

    fun latestMeasurement(id: String): MeasurementV? = measurements[id]?.maxByOrNull { it.version }
    fun latestCurve(id: String): CurveV? = curves[id]?.maxByOrNull { it.version }
    fun latestOffset(id: String): OffsetV? = offsets[id]?.maxByOrNull { it.version }

    fun isStale(r: CalibrationResult): Boolean {
        val m = latestMeasurement(r.measurementId) ?: return true
        if (m.version != r.measurementVersion) return true
        val c = latestCurve(r.curveId) ?: return true
        if (c.version != r.curveVersion) return true
        if (r.offsetId != null) {
            val o = latestOffset(r.offsetId) ?: return true
            if (o.version != r.offsetVersion) return true
        }
        return false
    }

    fun isUndone(d: DecisionV): Boolean = undos.any { it.undoOf == d.id }
    fun activeDecisions(): List<DecisionV> = decisions.values.filter { !isUndone(it) }

    /** 每个测量最新的 OK 结果（按事件顺序），供层序检查使用。 */
    fun latestOkResults(): Map<String, CalibrationResult> {
        val out = LinkedHashMap<String, CalibrationResult>()
        for (r in results.values) if (r.status == "OK") out[r.measurementId] = r
        return out
    }
}

class App(val store: Store) {
    fun projections(upToSeq: Long = Long.MAX_VALUE): Projections {
        val p = Projections()
        for (e in store.events()) {
            if (e.seq > upToSeq) break
            p.apply(e.payload)
        }
        return p
    }

    fun submit(payload: Payload): Envelope = store.append(payload)

    fun calibrate(measurementId: String, offsetId: String?): CalibrationResult {
        val p = projections()
        val m = p.latestMeasurement(measurementId) ?: error("测量不存在：$measurementId")
        val curve = p.curves.values.mapNotNull { it.maxByOrNull { c -> c.version } }.maxByOrNull { it.version }
            ?: error("未注册任何校准曲线")
        val offset = offsetId?.let { p.latestOffset(it) ?: error("偏移假设不存在：$it") }
        val era = p.eraSystem?.era ?: "CAL_BP"
        val result = Calibration.calibrate(store.ids.next(), m, curve, offset, era)
        store.append(Payload.CalibrationComputed(result))
        return result
    }

    fun stratCheck(): StratCheckResult {
        val p = projections()
        val res = Stratigraphy.check(
            store.ids.next(),
            p.constraints.values.toList(),
            p.latestOkResults()
        ) { store.ids.next() }
        store.append(Payload.StratChecked(res))
        return res
    }

    fun decide(resultId: String, peakIndex: Int?, action: String, reason: String, reviewer: String): DecisionV {
        val d = DecisionV(store.ids.next(), resultId, peakIndex, action, reason, reviewer)
        store.append(Payload.DecisionMade(d))
        return d
    }

    fun undo(decisionId: String, reason: String): UndoV {
        val u = UndoV(store.ids.next(), decisionId, reason)
        store.append(Payload.DecisionUndone(u))
        return u
    }
}
