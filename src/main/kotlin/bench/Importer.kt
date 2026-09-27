package bench

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** 导入层：接受可复制的小型样例；格式错误逐条报告；无法转换的值留在原位置并附诊断。 */
object Importer {

    private fun JsonObject.num(key: String): Double? {
        val p = this[key] as? JsonPrimitive ?: return null
        if (p.isString) return null
        return p.contentOrNull?.toDoubleOrNull()
    }

    fun import(wb: Workbench, text: String): ImportReport {
        val archive = try {
            JsonFmt.decodeFromString(Archive.serializer(), text)
        } catch (e: Exception) {
            return ImportReport(false, listOf("JSON 解析失败：${e.message ?: e.javaClass.simpleName}"), 0, 0)
        }
        if (archive.format != wb.FORMAT)
            return ImportReport(false,
                listOf("format 必须为 ${wb.FORMAT}，实际为 “${archive.format}”"), 0, 0)
        val errors = mutableListOf<String>()
        val before = wb.events.size
        when {
            !archive.events.isNullOrEmpty() -> importEventLog(wb, archive.events, errors)
            !archive.steps.isNullOrEmpty() -> importSteps(wb, archive.steps, errors)
            else -> errors += "档案为空：events 与 steps 均缺失"
        }
        if (errors.isNotEmpty()) return ImportReport(false, errors, 0, 0)
        return ImportReport(true, emptyList(), wb.events.size - before, wb.state.quarantines.size)
    }

    private fun importEventLog(wb: Workbench, evs: List<EventEnvelope>, errors: MutableList<String>) {
        if (!wb.isEmpty()) {
            errors += "目标项目非空：事件日志只能导入空项目（请先重置/删除本地数据库）"
            return
        }
        val seen = mutableSetOf<String>()
        evs.forEachIndexed { idx, e ->
            val pos = idx + 1
            if (e.seq != pos) errors += "第 $pos 条事件 seq=${e.seq}：必须从 1 连续编号"
            if (!seen.add(e.id)) errors += "事件 id 重复：${e.id}"
            if (e.type !in ET.ALL) errors += "事件 ${e.id} 类型未知：${e.type}"
        }
        if (errors.isEmpty()) {
            val s = ProjectState()
            for (e in evs) {
                validateReferences(s, e, errors)
                applyEvent(s, e)
            }
        }
        if (errors.isEmpty()) evs.forEach { wb.append(it.type, it.payload, it.id, it.ts) }
    }

    private fun validateReferences(s: ProjectState, e: EventEnvelope, errors: MutableList<String>) {
        try {
            when (e.type) {
                ET.MEASUREMENT_REVISED -> {
                    val p = JsonFmt.decodeFromJsonElement(MeasurementRevised.serializer(), e.payload)
                    val head = s.headMeasurement(p.measurementId)
                    if (head == null) errors += "事件 ${e.id}：修订的测量不存在 ${p.measurementId}"
                    else if (p.version != head.version + 1)
                        errors += "事件 ${e.id}：${p.measurementId} 版本必须接续 ${head.version}，收到 ${p.version}"
                }
                ET.MEASUREMENT_IMPORTED -> {
                    val p = JsonFmt.decodeFromJsonElement(MeasurementImported.serializer(), e.payload)
                    if (p.measurementId in s.measurements) errors += "事件 ${e.id}：测量 id 已存在 ${p.measurementId}"
                }
                ET.CONSTRAINT_ADDED -> {
                    val p = JsonFmt.decodeFromJsonElement(ConstraintAdded.serializer(), e.payload)
                    if (s.headMeasurement(p.olderId) == null) errors += "事件 ${e.id}：olderId 不存在 ${p.olderId}"
                    if (s.headMeasurement(p.youngerId) == null) errors += "事件 ${e.id}：youngerId 不存在 ${p.youngerId}"
                }
                ET.CALIBRATION_COMPUTED -> {
                    val p = JsonFmt.decodeFromJsonElement(CalibrationComputed.serializer(), e.payload)
                    val m = s.measurements[p.measurementId]?.firstOrNull { it.version == p.measurementVersion }
                    if (m == null) errors += "事件 ${e.id}：测量版本不存在 ${p.measurementId}@${p.measurementVersion}"
                    if (s.curves[p.curveId]?.none { it.version == p.curveVersion } != false)
                        errors += "事件 ${e.id}：曲线版本不存在 ${p.curveId}@${p.curveVersion}"
                    if (s.offsets[p.offsetId]?.none { it.version == p.offsetVersion } != false)
                        errors += "事件 ${e.id}：偏移版本不存在 ${p.offsetId}@${p.offsetVersion}"
                }
                ET.DECISION_MADE -> {
                    val p = JsonFmt.decodeFromJsonElement(DecisionMade.serializer(), e.payload)
                    if (p.verdict !in setOf("accept", "reject", "adjust")) errors += "事件 ${e.id}：verdict 非法 ${p.verdict}"
                    val ok = when (p.targetType) {
                        "calibration" -> p.targetId in s.calibrations
                        "candidate" -> p.targetId in s.candidates
                        "measurement" -> s.headMeasurement(p.targetId) != null
                        else -> false
                    }
                    if (!ok) errors += "事件 ${e.id}：裁定目标不存在 ${p.targetType}/${p.targetId}"
                }
                ET.DECISION_REVERTED -> {
                    val p = JsonFmt.decodeFromJsonElement(DecisionReverted.serializer(), e.payload)
                    if (p.decisionId !in s.decisions) errors += "事件 ${e.id}：撤销的裁定不存在 ${p.decisionId}"
                }
                ET.EXCLUSION_DECIDED -> {
                    val p = JsonFmt.decodeFromJsonElement(ExclusionDecided.serializer(), e.payload)
                    if (s.headMeasurement(p.measurementId) == null) errors += "事件 ${e.id}：排除决定的测量不存在 ${p.measurementId}"
                }
                ET.CANDIDATE_GENERATED -> {
                    JsonFmt.decodeFromJsonElement(CandidateGenerated.serializer(), e.payload)
                }
                else -> JsonFmt.decodeFromJsonElement(JsonObject.serializer(), e.payload)
            }
        } catch (ex: Exception) {
            errors += "事件 ${e.id}（${e.type}）载荷无法解码：${ex.message ?: ex.javaClass.simpleName}"
        }
    }

    private fun importSteps(wb: Workbench, steps: List<JsonObject>, errors: MutableList<String>) {
        if (!wb.isEmpty()) {
            errors += "目标项目非空：案例包只能导入空项目"
            return
        }
        val startSeq = wb.events.size
        var quarantined = 0
        try {
            for ((idx, step) in steps.withIndex()) {
                val pos = idx + 1
                val op = (step["op"] as? JsonPrimitive)?.contentOrNull
                val ev = step["event"] as? JsonObject
                when {
                    ev != null -> quarantined += replayEventStep(wb, ev, pos, errors)
                    op == "calibrate" -> {
                        val mid = (step["measurement"] as JsonPrimitive).content
                        val oid = (step["offset"] as JsonPrimitive).content
                        val rid = (step["resultId"] as? JsonPrimitive)?.contentOrNull
                        wb.computeCalibration(mid, oid, rid)
                    }
                    op == "analyze-sequences" -> wb.analyzeSequences()
                    op == "decide" -> {
                        wb.decide(
                            targetType = (step["targetType"] as JsonPrimitive).content,
                            targetId = (step["targetId"] as JsonPrimitive).content,
                            verdict = (step["verdict"] as JsonPrimitive).content,
                            rationale = (step["rationale"] as JsonPrimitive).content,
                            reviewer = (step["reviewer"] as JsonPrimitive).content,
                            decisionId = (step["decisionId"] as? JsonPrimitive)?.contentOrNull
                        )
                    }
                    op == "revert-decision" -> {
                        wb.revertDecision(
                            (step["decisionId"] as JsonPrimitive).content,
                            (step["reason"] as JsonPrimitive).content
                        )
                    }
                    else -> throw WorkbenchException("第 $pos 步无法识别（既无 event 也无已知 op）")
                }
                if (errors.isNotEmpty()) throw WorkbenchException(errors.joinToString("; "))
            }
        } catch (ex: Exception) {
            errors += "案例包执行失败（已回滚）：${ex.message ?: ex.javaClass.simpleName}"
            wb.store.deleteFrom(startSeq)
            wb.reload()
        }
        if (errors.isEmpty() && quarantined > 0) errors += "__quarantined:$quarantined"
    }

    /** 直接来源事件回放；测量数值不可转换时不丢弃，留在隔离区并附诊断。 */
    private fun replayEventStep(wb: Workbench, ev: JsonObject, pos: Int, errors: MutableList<String>): Int {
        val id = (ev["id"] as? JsonPrimitive)?.contentOrNull
        val ts = (ev["ts"] as? JsonPrimitive)?.contentOrNull
        val type = (ev["type"] as? JsonPrimitive)?.contentOrNull
            ?: run { errors += "第 $pos 步 event 缺少 type"; return 0 }
        if (type !in ET.ALL) { errors += "第 $pos 步事件类型未知：$type"; return 0 }
        val payload = ev["payload"] as? JsonObject
            ?: run { errors += "第 $pos 步 event 缺少 payload"; return 0 }
        if (type == ET.MEASUREMENT_IMPORTED || type == ET.MEASUREMENT_REVISED) {
            val age = payload.num("ageBP")
            val sig = payload.num("sigma")
            if (age == null || sig == null) {
                val qid = wb.idGen.next("q")
                val diag = "无法转换为 14C yr BP 数值（ageBP=${payload["ageBP"]}, sigma=${payload["sigma"]}）；原始表示保留，未参与绘图/校准"
                wb.append(ET.QUARANTINED,
                    JsonFmt.encodeToJsonElement(Quarantined.serializer(),
                        Quarantined(qid, payload.toString(), diag)).let { it as JsonObject },
                    fixedId = id?.let { "$id-q" }, fixedTs = ts)
                return 1
            }
        }
        wb.append(type, payload, id, ts)
        return 0
    }
}
