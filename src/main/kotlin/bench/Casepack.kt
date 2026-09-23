package bench

/** 离线案例包：导入基线 + 演练脚本（参数修订、局部源改动、冲突人工决定、曲线升版、撤销）。 */
class Casepack(val bench: Bench) {

    data class RunLog(val lines: MutableList<String> = mutableListOf()) {
        fun log(s: String) { lines.add(s) }
    }

    fun loadResource(): String =
        javaClass.getResource("/casepack/radiocarbon-bench-case.json")?.readText()
            ?: throw IllegalStateException("casepack resource missing")

    /** 导入基线输入（幂等）。 */
    fun importBaseline(doc: Map<String, Any?>, log: RunLog): Map<String, String> {
        val store = bench.store
        val keyToId = mutableMapOf<String, String>()
        @Suppress("UNCHECKED_CAST")
        for (inp in doc["inputs"] as List<Map<String, Any?>>) {
            val kind = InputKind.valueOf(inp["kind"] as String)
            val payload = inp["payload"] as Map<String, Any?>
            val rec = store.submitInput(kind, payload, inp["sourceRef"] as String, actor = "casepack")
            val key = when (kind) {
                InputKind.MEASUREMENT -> payload["labCode"] as String
                InputKind.CURVE -> payload["name"] as String
                InputKind.OFFSET -> payload["name"] as String
                else -> "${kind.name}:${rec.id.takeLast(8)}"
            }
            keyToId[key] = rec.id
            log.log("import $kind $key -> ${rec.id}")
        }
        return keyToId
    }

    /** 执行演练脚本：每一步都通过 Bench 的真实工作流，留下事件。 */
    fun runScript(doc: Map<String, Any?>, ids: Map<String, String>, log: RunLog) {
        val store = bench.store
        val calibByLab = mutableMapOf<String, String>()
        fun currentCalib(lab: String): String = calibByLab[lab]
            ?: store.results(ResultKind.CALIBRATION)
                .filter { Json.mapOf(store.getInput(it.sources[0])!!.payload)["labCode"] == lab }
                .maxByOrNull { it.createdSeq }!!.id
        @Suppress("UNCHECKED_CAST")
        for (step in doc["script"] as List<Map<String, Any?>>) {
            when (step["op"] as String) {
                "importInputs" -> log.log("script: inputs already imported")
                "calibrateAll" -> {
                    val curve = ids[step["curve"] as String]!!
                    @Suppress("UNCHECKED_CAST")
                    val offsetFor = step["offsetFor"] as Map<String, String>
                    val excluded = store.inputs(InputKind.EXCLUSION).map { Json.mapOf(it.payload)["labCode"] }.toSet()
                    for (m in store.inputs(InputKind.MEASUREMENT)) {
                        val lab = Json.mapOf(m.payload)["labCode"] as String
                        if (lab in excluded) { log.log("skip excluded $lab"); continue }
                        val r = bench.calibrate(m.id, curve, offsetFor[lab]?.let { ids[it] }, actor = "casepack")
                        calibByLab[lab] = r.id
                        log.log("calibrate $lab -> ${r.id}")
                    }
                }
                "checkStratigraphy" -> {
                    val conflicts = bench.checkStratigraphy(actor = "casepack")
                    for (c in conflicts) log.log("strat conflict P=${"%.3f".format(c.probConstraintHolds)} candidates=${c.candidates.size}")
                }
                "decide" -> {
                    val r = bench.decide(currentCalib(step["lab"] as String), step["verdict"] as String,
                        step["reason"] as String, step["actor"] as String)
                    log.log("decide ${step["lab"]} ${step["verdict"]} -> ${r.id}")
                }
                "resolveConflict" -> {
                    val lab = step["lab"] as String
                    val target = currentCalib(lab)
                    val chosen = store.results(ResultKind.DECISION).first {
                        Json.mapOf(it.payload)["targetId"] == target && Json.mapOf(it.payload)["verdict"] == step["chooseVerdict"] }
                    bench.resolveConflict(target, chosen.id, step["actor"] as String)
                    log.log("resolve $lab -> keep ${step["chooseVerdict"]}")
                }
                "reviseOffset" -> {
                    val oldId = ids[step["name"] as String]!!
                    @Suppress("UNCHECKED_CAST")
                    val payload = mapOf("name" to step["name"], "unit" to "14C yr",
                        "components" to (step["components"] as List<Map<String, Any?>>))
                    val rec = store.submitInput(InputKind.OFFSET, payload, step["sourceRef"] as String,
                        parentId = oldId, actor = "casepack")
                    log.log("revise offset ${step["name"]} -> v${rec.version}; stale propagated")
                    // 重校准依赖该偏移的测量
                    val stale = store.results(ResultKind.CALIBRATION).filter { it.status == ResultStatus.STALE }
                    for (s in stale) {
                        val lab = Json.mapOf(store.getInput(s.sources[0])!!.payload)["labCode"] as String
                        val curveId = store.latestVersionOf(s.sources[1]).id
                        val r = bench.calibrate(s.sources[0], curveId, rec.id, actor = "casepack")
                        calibByLab[lab] = r.id
                        log.log("recalibrate $lab with revised offset -> ${r.id}")
                    }
                }
                "reviseMeasurement" -> {
                    val lab = step["lab"] as String
                    val old = bench.latestMeasurement(lab)!!
                    val oldP = Json.mapOf(old.payload)
                    val payload = mapOf("labCode" to lab, "c14Bp" to oldP["c14Bp"],
                        "sigma" to (step["sigma"] as Number).toDouble(), "unit" to oldP["unit"],
                        "material" to oldP["material"], "note" to "σ 修订（局部源改动）")
                    val rec = store.submitInput(InputKind.MEASUREMENT, payload, step["sourceRef"] as String,
                        parentId = old.id, actor = "casepack")
                    log.log("revise measurement $lab sigma -> v${rec.version}; only dependents staled")
                    val stale = store.results(ResultKind.CALIBRATION).filter { it.status == ResultStatus.STALE }
                    for (s in stale) {
                        val curveId = store.latestVersionOf(s.sources[1]).id
                        val offId = s.sources.getOrNull(2)?.let { store.latestVersionOf(it).id }
                        val r = bench.calibrate(rec.id, curveId, offId, actor = "casepack")
                        calibByLab[lab] = r.id
                        log.log("recalibrate $lab -> ${r.id}")
                    }
                }
                "upgradeCurve" -> {
                    val pairs = bench.upgradeCurve(ids[step["from"] as String]!!, ids[step["to"] as String]!!, actor = "casepack")
                    for ((old, new) in pairs) {
                        val lab = Json.mapOf(store.getInput(old.sources[0])!!.payload)["labCode"]
                        calibByLab[lab] = new.id
                        log.log("curve upgrade: $lab ${old.id.takeLast(8)} -> ${new.id.takeLast(8)}")
                    }
                }
                "undoLastDecision" -> {
                    val lab = step["lab"] as String
                    val target = currentCalib(lab)
                    val d = store.results(ResultKind.DECISION).lastOrNull {
                        it.status == ResultStatus.CURRENT && Json.mapOf(it.payload)["targetId"] == target }
                    if (d != null) { bench.undoDecision(d.id, step["actor"] as String); log.log("undo decision ${d.id}") }
                }
                else -> log.log("unknown op ${step["op"]}")
            }
        }
    }

    /** 一键：导入基线并执行完整演练。 */
    fun importAndRun(log: RunLog = RunLog()): RunLog {
        val doc = Json.mapOf(loadResource())
        val ids = importBaseline(doc, log)
        runScript(doc, ids, log)
        return log
    }
}
