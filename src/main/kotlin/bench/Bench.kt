package bench

/** 业务编排：把测量、曲线、储库偏移、层序约束组合成审阅工作流。 */
class Bench(val store: Store) {

    // ---------- 输入解析 ----------

    fun measurementOf(input: InputRecord): C14Age {
        val p = Json.mapOf(input.payload)
        return C14Age((p["c14Bp"] as Number).toDouble(), (p["sigma"] as Number).toDouble())
    }

    fun curveOf(input: InputRecord): CurveTable {
        val p = Json.mapOf(input.payload)
        val pts = (p["points"] as List<*>).map {
            val m = it as Map<*, *>
            CurvePoint((m["calBp"] as Number).toDouble(), (m["c14Age"] as Number).toDouble(), (m["c14Sigma"] as Number).toDouble())
        }
        return CurveTable(p["name"] as String, pts)
    }

    fun offsetOf(input: InputRecord): OffsetDistribution {
        val p = Json.mapOf(input.payload)
        val comps = (p["components"] as List<*>).map {
            val m = it as Map<*, *>
            OffsetComponent((m["weight"] as Number).toDouble(), (m["mean"] as Number).toDouble(), (m["wrongSigma"] as? Number)?.toDouble()
                ?: (m["sigma"] as Number).toDouble())
        }
        return OffsetDistribution(comps)
    }

    fun eraSystem(): EraSystem {
        val latest = store.inputs(InputKind.ERA_SYSTEM).maxByOrNull { it.createdSeq } ?: return EraSystem.CAL_BP
        return EraSystem.valueOf(Json.mapOf(latest.payload)["system"] as String)
    }

    fun latestMeasurement(labCode: String): InputRecord? =
        store.inputs(InputKind.MEASUREMENT).filter { Json.mapOf(it.payload)["labCode"] == labCode }
            .groupBy { Json.mapOf(it.payload)["labCode"] as String }
            .values.firstOrNull()?.maxByOrNull { it.version }

    // ---------- 校准 ----------

    /** 校准一条测量；结果带算法版本、参数指纹、直接来源。 */
    fun calibrate(measurementInputId: String, curveInputId: String, offsetInputId: String?,
                  grid: GridSpec = Calibrate.DEFAULT_GRID, actor: String = "reviewer"): ResultRecord {
        val mInput = store.getInput(measurementInputId) ?: throw IllegalArgumentException("unknown measurement $measurementInputId")
        val cInput = store.getInput(curveInputId) ?: throw IllegalArgumentException("unknown curve $curveInputId")
        val oInput = offsetInputId?.let { store.getInput(it) ?: throw IllegalArgumentException("unknown offset $it") }
        val m = measurementOf(mInput); val curve = curveOf(cInput); val off = oInput?.let { offsetOf(it) }
        val fp = Calibrate.fingerprint(m, curve.name, off, grid)
        val dg = Calibrate.calibrate(m, curve, off, grid)
        val intervals = Calibrate.hpdIntervals(dg)
        val sources = listOfNotNull(measurementInputId, curveInputId, offsetInputId)
        val payload = mapOf(
            "labCode" to Json.mapOf(mInput.payload)["labCode"],
            "curve" to curve.name,
            "grid" to mapOf("start" to grid.startCalBp, "end" to grid.endCalBp, "step" to grid.step),
            "boundaryInterpolated" to dg.boundaryInterpolated,
            "diagnostics" to dg.diagnostics,
            "density" to dg.density.toList(),
            "intervals" to intervals.map { mapOf("fromCalBp" to it.fromCalBp, "toCalBp" to it.toCalBp,
                "probability" to it.probability, "level" to it.level) },
            "eraSystem" to eraSystem().name
        )
        // 同一测量的旧校准结果（同曲线族旧版等）标记 SUPERSEDED
        val ev = store.recordEvent("calibration.requested", actor,
            mapOf("measurement" to measurementInputId, "curve" to curveInputId, "offset" to offsetInputId, "fingerprint" to fp))
        val old = store.results(ResultKind.CALIBRATION).filter {
            it.status == ResultStatus.CURRENT && measurementOf(store.getInput(it.sources[0])!!).let { mm ->
                Json.mapOf(store.getInput(it.sources[0])!!.payload)["labCode"] == Json.mapOf(mInput.payload)["labCode"] } && it.sources != sources
        }
        for (o in old) store.supersede(o.id, ev.seq)
        return store.putResult(ResultKind.CALIBRATION, Calibrate.ALGO_VERSION, fp, sources, payload, actor)
    }

    fun densityGridOf(result: ResultRecord): DensityGrid {
        val p = Json.mapOf(result.payload)
        val g = p["grid"] as Map<*, *>
        val spec = GridSpec((g["start"] as Number).toDouble(), (g["end"] as Number).toDouble(), (g["step"] as Number).toDouble())
        val density = (p["density"] as List<*>).map { (it as Number).toDouble() }.toDoubleArray()
        return DensityGrid(spec, density, p["boundaryInterpolated"] as Boolean,
            (p["diagnostics"] as List<*>).map { it as String })
    }

    fun intervalsOf(result: ResultRecord): List<CalInterval> {
        val p = Json.mapOf(result.payload)
        return (p["intervals"] as List<*>).map {
            val m = it as Map<*, *>
            CalInterval((m["fromCalBp"] as Number).toDouble(), (m["toCalBp"] as Number).toDouble(),
                (m["probability"] as Number).toDouble(), (m["level"] as Number).toDouble())
        }
    }

    /** 区间概率 + 积分证据（可展开读回）。 */
    fun intervalEvidence(resultId: String, fromCalBp: Double, toCalBp: Double): IntervalEvidence {
        val r = store.getResult(resultId) ?: throw IllegalArgumentException("unknown result $resultId")
        return densityGridOf(r).probabilityWithEvidence(fromCalBp, toCalBp)
    }

    // ---------- 层序 ----------

    data class StratConflict(val constraintId: String, val olderCalib: String, val youngerCalib: String,
                             val probConstraintHolds: Double, val candidates: List<ResultRecord>)

    /** 检查全部层序约束；冲突时生成候选调整（不改写测量），保留全部合法候选。 */
    fun checkStratigraphy(actor: String = "reviewer"): List<StratConflict> {
        val out = mutableListOf<StratConflict>()
        val calibs = store.results(ResultKind.CALIBRATION).filter { it.status == ResultStatus.CURRENT }
        fun calibFor(labCode: String): ResultRecord? = calibs.firstOrNull {
            Json.mapOf(store.getInput(it.sources[0])!!.payload)["labCode"] == labCode }
        for (c in store.inputs(InputKind.STRATIGRAPHY)) {
            val p = Json.mapOf(c.payload)
            val olderLab = p["olderLab"] as String; val youngerLab = p["youngerLab"] as String
            val ca = calibFor(olderLab) ?: continue
            val cb = calibFor(youngerLab) ?: continue
            val ga = densityGridOf(ca); val gb = densityGridOf(cb)
            val probHolds = Calibrate.probOlder(ga, gb)
            if (probHolds < 0.5) {
                // 候选边界：对方分布的若干分位数，保留全部合法候选
                val candidates = mutableListOf<ResultRecord>()
                for (q in listOf(0.5, 0.75, 0.95)) {
                    val boundary = Calibrate.quantile(gb, q)
                    val adj = Calibrate.truncationCandidate(ga, olderThan = boundary, youngerThan = null)
                    if (adj.diagnostics.none { it.startsWith("truncation-empty") }) {
                        candidates.add(store.putResult(ResultKind.ADJUSTMENT_CANDIDATE, Calibrate.ALGO_VERSION,
                            Calibrate.fingerprint(C14Age(boundary, 1.0), "strat-trunc", null, ga.spec),
                            listOf(c.id, ca.id, cb.id),
                            mapOf("kind" to "older-truncated", "targetCalib" to ca.id, "boundaryCalBp" to boundary,
                                "quantile" to q, "diagnostics" to adj.diagnostics,
                                "density" to adj.density.toList(), "grid" to mapOf("start" to ga.spec.startCalBp,
                                    "end" to ga.spec.endCalBp, "step" to ga.spec.step)), actor))
                    }
                    val boundaryB = Calibrate.quantile(ga, 1.0 - q)
                    val adjB = Calibrate.truncationCandidate(gb, olderThan = null, youngerThan = boundaryB)
                    if (adjB.diagnostics.none { it.startsWith("truncation-empty") }) {
                        candidates.add(store.putResult(ResultKind.ADJUSTMENT_CANDIDATE, Calibrate.ALGO_VERSION,
                            Calibrate.fingerprint(C14Age(boundaryB, 1.0), "strat-trunc", null, gb.spec),
                            listOf(c.id, ca.id, cb.id),
                            mapOf("kind" to "younger-truncated", "targetCalib" to cb.id, "boundaryCalBp" to boundaryB,
                                "quantile" to q, "diagnostics" to adjB.diagnostics,
                                "density" to adjB.density.toList(), "grid" to mapOf("start" to gb.spec.startCalBp,
                                    "end" to gb.spec.endCalBp, "step" to gb.spec.step)), actor))
                    }
                }
                store.recordEvent("stratigraphy.conflict", actor, mapOf(
                    "constraintId" to c.id, "olderLab" to olderLab, "youngerLab" to youngerLab,
                    "probConstraintHolds" to probHolds,
                    "candidateIds" to candidates.map { it.id },
                    "note" to "层序冲突：产生候选调整，测量值未被改写"))
                out.add(StratConflict(c.id, ca.id, cb.id, probHolds, candidates))
            }
        }
        return out
    }

    // ---------- 裁定 / 冲突解决 / 撤销 ----------

    fun decide(targetResultId: String, verdict: String, reason: String, actor: String): ResultRecord {
        val target = store.getResult(targetResultId) ?: throw IllegalArgumentException("unknown result $targetResultId")
        // 冲突检测：同一目标已有相互矛盾的 CURRENT 裁定
        val existing = store.results(ResultKind.DECISION).filter {
            it.status == ResultStatus.CURRENT && Json.mapOf(it.payload)["targetId"] == targetResultId }
        val conflicting = existing.firstOrNull { Json.mapOf(it.payload)["verdict"] != verdict }
        val decision = store.putResult(ResultKind.DECISION, "decision/1.0",
            Ids.of("fp", "$targetResultId|$verdict|$reason|$actor|${store.events().size}"),
            listOf(targetResultId),
            mapOf("targetId" to targetResultId, "verdict" to verdict, "reason" to reason,
                "conflictsWith" to conflicting?.id), actor)
        if (conflicting != null) {
            store.recordEvent("decision.conflict", actor, mapOf(
                "targetId" to targetResultId, "existing" to conflicting.id, "incoming" to decision.id,
                "note" to "互相冲突的人工决定：两条均保留，需显式解决"))
        }
        return decision
    }

    /** 冲突解决：选定一条裁定，其余标记 SUPERSEDED。 */
    fun resolveConflict(targetResultId: String, chosenDecisionId: String, actor: String) {
        val decisions = store.results(ResultKind.DECISION).filter {
            it.status == ResultStatus.CURRENT && Json.mapOf(it.payload)["targetId"] == targetResultId }
        val ev = store.recordEvent("decision.resolved", actor,
            mapOf("targetId" to targetResultId, "chosen" to chosenDecisionId,
                "rejected" to decisions.filter { it.id != chosenDecisionId }.map { it.id }))
        for (d in decisions) if (d.id != chosenDecisionId) store.supersede(d.id, ev.seq)
    }

    /** 撤销：写入新事件，目标结果被撤销的裁定失效，历史保留。 */
    fun undoDecision(decisionId: String, actor: String) {
        val d = store.getResult(decisionId) ?: throw IllegalArgumentException("unknown decision $decisionId")
        val ev = store.recordEvent("decision.undone", actor, mapOf("decisionId" to decisionId,
            "targetId" to Json.mapOf(d.payload)["targetId"]))
        store.supersede(decisionId, ev.seq)
    }

    // ---------- 曲线升版 ----------

    /** 用新版曲线重校准受影响的测量；旧结果 SUPERSEDED 但可查看；返回并列区间对照。 */
    fun upgradeCurve(oldCurveInputId: String, newCurveInputId: String, actor: String = "reviewer"): List<Pair<ResultRecord, ResultRecord>> {
        val affected = store.results(ResultKind.CALIBRATION).filter {
            it.status == ResultStatus.CURRENT && it.sources.contains(oldCurveInputId) }
        val pairs = mutableListOf<Pair<ResultRecord, ResultRecord>>()
        for (old in affected) {
            val measId = old.sources[0]
            val offsetId = old.sources.getOrNull(2)
            val ev = store.recordEvent("curve.upgraded", actor, mapOf(
                "from" to oldCurveInputId, "to" to newCurveInputId, "affectedCalib" to old.id))
            store.supersede(old.id, ev.seq)
            val fresh = calibrate(measId, newCurveInputId, offsetId, actor = actor)
            pairs.add(old to fresh)
        }
        return pairs
    }
}
