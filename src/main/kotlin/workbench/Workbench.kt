package workbench

import kotlinx.serialization.json.*

class Workbench(val store: Store) {
    val json = store.json

    companion object {
        const val GRID_FROM_BP = -100
        const val GRID_TO_BP = 5000
    }

    // ---------- 来源提交与修订 ----------

    fun submitSource(kind: String, id: String, payload: JsonObject): SourceRow {
        require(store.latestSource(id) == null) { "source $id already exists; use reviseSource" }
        val fp = fingerprintOf(kind, id, payload.toString())
        val evt = store.appendEvent("source.submitted", buildJsonObject {
            put("sourceId", id); put("kind", kind); put("version", 1); put("fingerprint", fp)
            put("payload", payload)
        })
        val row = SourceRow(id, 1, kind, null, null, fp, payload, evt.seq)
        store.putSource(row)
        return row
    }

    /** 修订以新版本 + 父版本关系表达；旧版本与依赖结果保留，依赖结果被标记过期。 */
    fun reviseSource(id: String, newPayload: JsonObject, note: String): SourceRow {
        val prev = store.latestSource(id) ?: error("source $id not found")
        val newVersion = prev.version + 1
        val fp = fingerprintOf(prev.kind, id, newPayload.toString())
        val evt = store.appendEvent("source.revised", buildJsonObject {
            put("sourceId", id); put("kind", prev.kind); put("version", newVersion)
            put("parentVersion", prev.version); put("note", note); put("fingerprint", fp)
            put("payload", newPayload)
        })
        val row = SourceRow(id, newVersion, prev.kind, prev.id, prev.version, fp, newPayload, evt.seq)
        store.putSource(row)
        // 局部过期：只标记直接引用旧版本的结果
        for (r in store.results()) {
            val ref = r.sourceRefs.firstOrNull { it.startsWith("${prev.kind}:$id:") }
            if (ref != null) {
                val reason = "source $id revised v${prev.version} -> v$newVersion"
                store.markResultStale(r.id, reason)
                store.appendEvent("result.staled", buildJsonObject {
                    put("resultId", r.id); put("reason", reason); put("triggerEvent", evt.id)
                })
            }
        }
        return row
    }

    // ---------- 校准 ----------

    fun calibrate(measurementId: String, curveId: String, offsetId: String,
                  gridFromBp: Int = GRID_FROM_BP, gridToBp: Int = GRID_TO_BP): ResultRow {
        val meas = store.latestSource(measurementId) ?: error("measurement $measurementId not found")
        val curve = store.latestSource(curveId) ?: error("curve $curveId not found")
        val offset = store.latestSource(offsetId) ?: error("offset $offsetId not found")
        val refs = listOf(
            "measurement:${meas.id}:${meas.version}",
            "curve:${curve.id}:${curve.version}",
            "offset:${offset.id}:${offset.version}"
        )
        val fp = fingerprintOf(ALGO_CALIBRATION, refs.joinToString(","), "$gridFromBp", "$gridToBp")
        val resultId = "res-" + fp.substring(0, 12)
        store.result(resultId)?.let {
            store.appendEvent("calibration.reused", buildJsonObject {
                put("resultId", it.id); put("fingerprint", fp)
            })
            return it
        }
        val measBp = meas.payload["bp"]!!.jsonPrimitive.double
        val measSigma = meas.payload["sigma"]!!.jsonPrimitive.double
        val curveData = CurveData(curve.payload["points"]!!.jsonArray.map { p ->
            val o = p.jsonObject
            CurvePoint(o["calBp"]!!.jsonPrimitive.int, o["c14Age"]!!.jsonPrimitive.double, o["sigma"]!!.jsonPrimitive.double)
        })
        val offsetMean = offset.payload["mean"]!!.jsonPrimitive.double
        val offsetSigma = offset.payload["sigma"]!!.jsonPrimitive.double
        val dist = Calibration.calibrate(measBp, measSigma, curveData, offsetMean, offsetSigma, gridFromBp, gridToBp)
        val payload = buildJsonObject {
            put("measurementId", measurementId); put("curveId", curveId); put("offsetId", offsetId)
            put("measBp", measBp); put("measSigma", measSigma)
            put("offsetMean", offsetMean); put("offsetSigma", offsetSigma)
            put("gridFromBp", dist.gridFromBp); put("gridToBp", dist.gridToBp); put("step", dist.step)
            put("normalization", dist.normalization); put("clampedCells", dist.clampedCells)
            putJsonArray("density") { dist.density.forEach { add(it) } }
            putJsonArray("peaks") { dist.peaks.forEach { p -> add(buildJsonObject {
                put("bp", p.bp); put("ceLabel", p.ceLabel); put("density", p.density) }) } }
            putJsonArray("intervals") { dist.intervals.forEach { i -> add(buildJsonObject {
                put("level", i.level); put("fromBp", i.fromBp); put("toBp", i.toBp)
                put("fromLabel", i.fromLabel); put("toLabel", i.toLabel)
                put("mass", i.mass); put("threshold", i.threshold); put("cellCount", i.cellCount) }) } }
        }
        val evt = store.appendEvent("calibration.computed", buildJsonObject {
            put("resultId", resultId); put("algorithm", ALGO_CALIBRATION); put("fingerprint", fp)
            putJsonArray("sourceRefs") { refs.forEach { add(it) } }
            put("peaks", dist.peaks.size); put("clampedCells", dist.clampedCells)
            put("intervals", dist.intervals.size)
        })
        val row = ResultRow(resultId, "calibration", ALGO_CALIBRATION, fp, refs, payload, evt.seq, false, null)
        store.putResult(row)
        return row
    }

    fun distributionOf(result: ResultRow): CalibratedDistribution {
        val p = result.payload
        return CalibratedDistribution(
            p["gridFromBp"]!!.jsonPrimitive.int, p["gridToBp"]!!.jsonPrimitive.int,
            p["step"]!!.jsonPrimitive.int,
            p["density"]!!.jsonArray.map { it.jsonPrimitive.double },
            p["normalization"]!!.jsonPrimitive.double,
            p["clampedCells"]!!.jsonPrimitive.int,
            p["peaks"]!!.jsonArray.map { o -> Peak(o.jsonObject["bp"]!!.jsonPrimitive.int,
                o.jsonObject["ceLabel"]!!.jsonPrimitive.content, o.jsonObject["density"]!!.jsonPrimitive.double) },
            p["intervals"]!!.jsonArray.map { o ->
                val q = o.jsonObject
                HpdInterval(q["level"]!!.jsonPrimitive.double, q["fromBp"]!!.jsonPrimitive.int, q["toBp"]!!.jsonPrimitive.int,
                    q["fromLabel"]!!.jsonPrimitive.content, q["toLabel"]!!.jsonPrimitive.content,
                    q["mass"]!!.jsonPrimitive.double, q["threshold"]!!.jsonPrimitive.double, q["cellCount"]!!.jsonPrimitive.int)
            })
    }

    fun intervalEvidence(resultId: String, intervalIndex: Int): Map<String, String> {
        val row = store.result(resultId) ?: error("result $resultId not found")
        val dist = distributionOf(row)
        val interval = dist.intervals[intervalIndex]
        val evidence = Calibration.intervalEvidence(dist, interval).toMutableMap()
        evidence["resultId"] = row.id
        evidence["resultFingerprint"] = row.fingerprint
        evidence["stale"] = row.stale.toString()
        row.staleReason?.let { evidence["staleReason"] = it }
        store.appendEvent("interval.evidence.read", buildJsonObject {
            put("resultId", row.id); put("intervalIndex", intervalIndex)
            put("level", interval.level); put("mass", interval.mass)
        })
        return evidence
    }

    // ---------- 层序 ----------

    fun checkSequence(constraintSourceId: String, calibrationResultIds: Map<String, String>): ResultRow {
        val constraint = store.latestSource(constraintSourceId) ?: error("constraint $constraintSourceId not found")
        val pairs = constraint.payload["pairs"]!!.jsonArray.map { p ->
            val o = p.jsonObject
            o["older"]!!.jsonPrimitive.content to o["younger"]!!.jsonPrimitive.content
        }
        val dists = calibrationResultIds.mapValues { (_, resId) -> distributionOf(store.result(resId) ?: error("result $resId not found")) }
        val report = Sequence.check(dists, pairs)
        val refs = mutableListOf("sequence-constraint:${constraint.id}:${constraint.version}")
        refs += calibrationResultIds.values.sorted().map { rid ->
            val r = store.result(rid)!!; "result:${r.id}"
        }
        val fp = fingerprintOf(ALGO_SEQUENCE, refs.joinToString(","))
        val resultId = "res-" + fp.substring(0, 12)
        val payload = buildJsonObject {
            put("constraintSourceId", constraintSourceId)
            put("conflicts", report.conflicts)
            putJsonArray("pairs") { report.pairs.forEach { pr -> add(buildJsonObject {
                put("olderId", pr.olderId); put("youngerId", pr.youngerId)
                put("pOrder", pr.pOrder); put("conflict", pr.conflict)
                putJsonArray("candidates") { pr.candidates.forEach { c -> add(buildJsonObject {
                    put("kind", c.kind); put("detail", c.detail) }) } }
            }) } }
        }
        val evt = store.appendEvent(if (report.conflicts > 0) "sequence.conflict" else "sequence.checked", buildJsonObject {
            put("resultId", resultId); put("algorithm", ALGO_SEQUENCE); put("fingerprint", fp)
            put("conflicts", report.conflicts)
        })
        val row = ResultRow(resultId, "sequence-report", ALGO_SEQUENCE, fp, refs, payload, evt.seq, false, null)
        if (store.result(resultId) == null) store.putResult(row)
        return store.result(resultId)!!
    }

    // ---------- 裁定与撤销 ----------

    fun decide(targetId: String, action: String, rationale: String, reviewer: String): DecisionRow {
        val seq = store.nextSeq()
        val id = "dec-%06d".format(seq)
        val evt = store.appendEvent("decision.recorded", buildJsonObject {
            put("decisionId", id); put("targetId", targetId); put("action", action)
            put("rationale", rationale); put("reviewer", reviewer)
        })
        val row = DecisionRow(id, targetId, action, rationale, reviewer, evt.seq, null)
        store.putDecision(row)
        return row
    }

    fun undoDecision(decisionId: String) {
        val d = store.decisions().firstOrNull { it.id == decisionId } ?: error("decision $decisionId not found")
        require(d.undoneSeq == null) { "decision $decisionId already undone" }
        val evt = store.appendEvent("decision.undone", buildJsonObject {
            put("decisionId", decisionId); put("targetId", d.targetId); put("originalAction", d.action)
        })
        store.markDecisionUndone(decisionId, evt.seq)
    }

    /** 同一目标上互相矛盾且都未撤销的裁定 => contested。 */
    fun decisionStatus(targetId: String): String {
        val active = store.decisions().filter { it.targetId == targetId && it.undoneSeq == null }
        if (active.isEmpty()) return "undecided"
        val accepts = active.any { it.action.startsWith("accept") }
        val rejects = active.any { it.action.startsWith("reject") }
        return if (accepts && rejects) "contested" else active.last().action
    }

    // ---------- 导入层：可复制小样例 + 明确格式错误 ----------

    data class ImportOutcome(val accepted: List<String>, val errors: List<String>)

    /** 每行 `labCode, bp, sigma`；无法转换的值保留原始表示并附诊断。 */
    fun importMeasurements(text: String): ImportOutcome {
        val accepted = mutableListOf<String>()
        val errors = mutableListOf<String>()
        text.lines().forEachIndexed { idx, rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed
            val parts = line.split(",").map { it.trim() }
            val lineNo = idx + 1
            if (parts.size != 3) {
                errors.add("line $lineNo: expected 'labCode, bp, sigma', got ${parts.size} fields")
                store.appendEvent("import.diagnostic", buildJsonObject {
                    put("line", lineNo); put("raw", line); put("error", "field-count") })
                return@forEachIndexed
            }
            val bp = parts[1].toDoubleOrNull()
            val sigma = parts[2].toDoubleOrNull()
            if (bp == null || sigma == null || sigma <= 0) {
                errors.add("line $lineNo: unconvertible numeric value in '${parts[1]}±${parts[2]}'")
                store.appendEvent("import.diagnostic", buildJsonObject {
                    put("line", lineNo); put("raw", line); put("error", "unconvertible-value") })
                return@forEachIndexed
            }
            val id = "src-measurement-" + parts[0].lowercase().replace(Regex("[^a-z0-9]+"), "-")
            if (store.latestSource(id) != null) {
                errors.add("line $lineNo: source $id already exists")
                return@forEachIndexed
            }
            submitSource("measurement", id, buildJsonObject {
                put("labCode", parts[0]); put("raw", line)
                put("bp", bp); put("sigma", sigma)
                put("unit", "BP (^14C yr, 5568/5730 半衰期无关的常规放射性碳年龄)")
                put("coordinateBasis", "conventional radiocarbon years BP")
            })
            accepted.add(id)
        }
        return ImportOutcome(accepted, errors)
    }

    // ---------- 导出 / 导入 ----------

    fun exportTo(file: java.io.File) {
        val root = store.exportJson()
        file.writeText(Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), root))
        store.appendEvent("export.created", buildJsonObject {
            put("path", file.name); put("events", store.events().size)
            put("sources", store.sources().size); put("results", store.results().size)
            put("decisions", store.decisions().size)
        })
    }

    fun importFrom(file: java.io.File) {
        val root = Json.parseToJsonElement(file.readText()).jsonObject
        store.importJson(root)
        store.appendEvent("import.completed", buildJsonObject {
            put("path", file.name); put("events", store.events().size)
        })
    }
}
