package workbench

import kotlinx.serialization.json.*

/** 服务层：把领域算法与事件存储连起来。所有状态变化都是事件。 */
class Workbench(val store: Store) {

    val json = Json { prettyPrint = false }

    // ---------- 源输入 ----------

    data class ParsedSource(val diagnostics: List<String>)

    fun validate(kind: SourceKind, payload: JsonObject): List<String> {
        val diags = mutableListOf<String>()
        fun num(field: String): Double? {
            val el = payload[field] ?: run { diags += "missing-field:$field"; return null }
            val v = el.jsonPrimitive.doubleOrNull
            if (v == null) diags += "unconvertible-value:$field kept-as-raw:${el}"
            return v
        }
        fun str(field: String): String? {
            val el = payload[field]
            if (el == null) { diags += "missing-field:$field"; return null }
            val v = el.jsonPrimitive.contentOrNull
            if (v == null) diags += "unconvertible-value:$field"
            return v
        }
        when (kind) {
            SourceKind.MEASUREMENT -> {
                str("labCode"); num("ageBp"); num("sigma")
                if ((payload["sigma"]?.jsonPrimitive?.doubleOrNull ?: 1.0) <= 0) diags += "non-positive-sigma"
            }
            SourceKind.CURVE -> {
                str("name")
                val pts = payload["points"]?.jsonArray
                if (pts == null || pts.isEmpty()) diags += "curve-without-points"
                else pts.forEachIndexed { i, p ->
                    val o = p.jsonObjectOrNull
                    if (o == null) diags += "curve-point[$i]-not-object"
                    else listOf("calBp", "c14Age", "sigma").forEach { f ->
                        if (o[f]?.jsonPrimitive?.doubleOrNull == null) diags += "curve-point[$i].$f-unconvertible"
                    }
                }
            }
            SourceKind.RESERVOIR_OFFSET -> {
                num("mean"); num("sigma")
                payload["samples"]?.jsonArrayOrNull?.forEachIndexed { i, s ->
                    if (s.jsonPrimitive.doubleOrNull == null) diags += "offset-sample[$i]-unconvertible"
                }
            }
            SourceKind.ERA_SYSTEM -> { str("system"); num("referenceYear") }
            SourceKind.SEQUENCE_CONSTRAINT -> { str("before"); str("after") }
            SourceKind.EXCLUSION_DECISION -> { str("targetLabCode"); str("reason") }
        }
        return diags
    }

    fun submitSource(
        kind: SourceKind, payload: JsonObject, provenance: String, unit: String,
        family: String? = null, parentId: String? = null
    ): String {
        val diags = validate(kind, payload)
        val version = if (parentId == null) 1 else {
            val parent = store.state().sources[parentId]
                ?: throw ImportException(listOf("parent source $parentId 不存在"))
            parent.version + 1
        }
        val fam = family ?: parentId?.let { store.state().sourceFamily[it] } ?: "pending"
        val evt = store.append(EventTypes.SOURCE_SUBMITTED) { seq ->
            val id = Ids.of("src", seq)
            val resolvedFamily = if (fam == "pending") id else fam
            Json.encodeToJsonElement(SourcePayload(
                id = id, family = resolvedFamily, kind = kind, version = version,
                parentId = parentId, provenance = provenance,
                payload = Json.encodeToString(JsonObject.serializer(), payload),
                unit = unit, diagnostics = diags
            )).jsonObject
        }
        val newId = evt.payload["id"]!!.jsonPrimitive.content
        stalenessSweep()
        return newId
    }

    fun reviseSource(parentId: String, payload: JsonObject, provenance: String): String {
        val st = store.state()
        val parent = st.sources[parentId] ?: throw ImportException(listOf("parent $parentId 不存在"))
        return submitSource(parent.kind, payload, provenance, parent.unit,
            family = st.sourceFamily[parentId], parentId = parentId)
    }

    // ---------- 解析视图 ----------

    fun parseMeasurement(src: SourceRecord): Measurement? {
        val o = runCatching { Json.parseToJsonElement(src.payload).jsonObject }.getOrNull() ?: return null
        val lab = o["labCode"]?.jsonPrimitive?.contentOrNull ?: return null
        val age = o["ageBp"]?.jsonPrimitive?.doubleOrNull ?: return null
        val sig = o["sigma"]?.jsonPrimitive?.doubleOrNull ?: return null
        val mat = o["material"]?.jsonPrimitive?.contentOrNull ?: "unknown"
        return Measurement(lab, age, sig, mat)
    }

    fun parseCurve(src: SourceRecord): CurveData? {
        val o = runCatching { Json.parseToJsonElement(src.payload).jsonObject }.getOrNull() ?: return null
        val name = o["name"]?.jsonPrimitive?.contentOrNull ?: return null
        val pts = o["points"]?.jsonArrayOrNull ?: return null
        val list = pts.mapNotNull {
            val p = it.jsonObjectOrNull ?: return@mapNotNull null
            val a = p["calBp"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
            val b = p["c14Age"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
            val c = p["sigma"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
            CurvePoint(a, b, c)
        }
        if (list.size < 2) return null
        return CurveData(name, list)
    }

    fun parseOffset(src: SourceRecord): ReservoirOffset? {
        val o = runCatching { Json.parseToJsonElement(src.payload).jsonObject }.getOrNull() ?: return null
        val mean = o["mean"]?.jsonPrimitive?.doubleOrNull ?: return null
        val sig = o["sigma"]?.jsonPrimitive?.doubleOrNull ?: return null
        val samples = o["samples"]?.jsonArrayOrNull?.mapNotNull { it.jsonPrimitive.doubleOrNull } ?: emptyList()
        return ReservoirOffset(mean, sig, samples)
    }

    fun parseConstraint(src: SourceRecord): SequenceConstraint? {
        val o = runCatching { Json.parseToJsonElement(src.payload).jsonObject }.getOrNull() ?: return null
        val b = o["before"]?.jsonPrimitive?.contentOrNull ?: return null
        val a = o["after"]?.jsonPrimitive?.contentOrNull ?: return null
        return SequenceConstraint(b, a, o["note"]?.jsonPrimitive?.contentOrNull ?: "")
    }

    // ---------- 派生计算 ----------

    fun recordCalibration(
        measurementSourceId: String, curveSourceId: String, offsetSourceId: String?,
        gridLo: Double? = null, gridHi: Double? = null, step: Double = 1.0
    ): String {
        val st = store.state()
        val mSrc = st.sources[measurementSourceId] ?: throw ImportException(listOf("measurement source 不存在"))
        val cSrc = st.sources[curveSourceId] ?: throw ImportException(listOf("curve source 不存在"))
        val m = parseMeasurement(mSrc)
        val c = parseCurve(cSrc)
        val oSrc = offsetSourceId?.let { st.sources[it] ?: throw ImportException(listOf("offset source 不存在")) }
        val r = oSrc?.let { parseOffset(it) }
        val result: CalibrationResult = if (m == null || c == null || (oSrc != null && r == null)) {
            CalibrationResult(CALIB_ALGORITHM_VERSION, "uncomputable", measurementSourceId, curveSourceId,
                offsetSourceId, "unavailable", 0.0, 0.0, step, emptyList(), emptyList(), emptyList(), emptyList(),
                listOf("source-unconvertible:kept-raw-with-diagnostics"), unknown = true,
                unknownReason = "direct source has unconvertible values; see source diagnostics")
        } else {
            Calibrator.calibrate(measurementSourceId, m, curveSourceId, c, offsetSourceId, r, gridLo, gridHi, step)
        }
        val evt = store.append(EventTypes.DERIVED_RECORDED) { seq ->
            Json.encodeToJsonElement(DerivedPayload(
                id = Ids.of("der", seq), kind = "calibration",
                algorithmVersion = result.algorithmVersion,
                paramFingerprint = result.paramFingerprint,
                sourceIds = listOfNotNull(measurementSourceId, curveSourceId, offsetSourceId),
                result = json.parseToJsonElement(json.encodeToString(CalibrationResult.serializer(), result)).jsonObject,
                recordedAtEvent = seq
            )).jsonObject
        }
        return evt.payload["id"]!!.jsonPrimitive.content
    }

    fun recordSequenceCheck(constraintSourceId: String, beforeDerivedId: String, afterDerivedId: String): String {
        val st = store.state()
        val cSrc = st.sources[constraintSourceId] ?: throw ImportException(listOf("constraint source 不存在"))
        val c = parseConstraint(cSrc) ?: throw ImportException(listOf("constraint 无法解析"))
        fun calOf(id: String): Pair<String, CalibrationResult> {
            val rec = st.derived[id] ?: throw ImportException(listOf("derived $id 不存在"))
            val res = json.decodeFromString(CalibrationResult.serializer(), rec.payload)
            val lab = st.sources[res.measurementSourceId]?.let { parseMeasurement(it)?.labCode } ?: "?"
            return lab to res
        }
        val bPair = calOf(beforeDerivedId)
        val aPair = calOf(afterDerivedId)
        val result = SequenceChecker.check(constraintSourceId, c, bPair.first, bPair, aPair.first, aPair)
        val evt = store.append(EventTypes.DERIVED_RECORDED) { seq ->
            Json.encodeToJsonElement(DerivedPayload(
                id = Ids.of("der", seq), kind = "sequence-check",
                algorithmVersion = result.algorithmVersion,
                paramFingerprint = result.paramFingerprint,
                sourceIds = listOf(constraintSourceId, beforeDerivedId, afterDerivedId),
                result = json.parseToJsonElement(json.encodeToString(SequenceCheckResult.serializer(), result)).jsonObject,
                recordedAtEvent = seq
            )).jsonObject
        }
        return evt.payload["id"]!!.jsonPrimitive.content
    }

    /** 局部过期：只有（传递）依赖被取代来源的派生结果才过期；过期结果仍可查看。
     *  迭代到不动点，使级联依赖在同一次修订内完成传播。 */
    fun stalenessSweep(): List<String> {
        val newlyStale = mutableListOf<String>()
        while (true) {
            val st = store.state()
            var changed = false
            for ((id, d) in st.derived) {
                if (d.status != DerivedStatus.CURRENT) continue
                val staleSrc = d.sourceIds.firstOrNull { sid ->
                    val src = st.sources[sid]
                    if (src != null) {
                        val fam = st.sourceFamily[sid] ?: sid
                        st.latestInFamily(fam)?.id != sid
                    } else {
                        // 来源是另一条派生结果，其本身已过期
                        st.derived[sid]?.status == DerivedStatus.STALE
                    }
                }
                if (staleSrc != null) {
                    store.append(EventTypes.DERIVED_STALENESS) { seq ->
                        Json.encodeToJsonElement(StalenessPayload(id,
                            "direct-source-superseded:$staleSrc", seq)).jsonObject
                    }
                    newlyStale += id
                    changed = true
                }
            }
            if (!changed) break
        }
        return newlyStale
    }

    // ---------- 人工决定 ----------

    fun decide(targetType: String, targetId: String, verdict: String, reason: String, author: String,
               labCode: String? = null, adjustPayload: JsonObject? = null, adjustProvenance: String? = null,
               reviseSourceId: String? = null): String {
        var newSourceId: String? = null
        if (verdict == "ADJUST") {
            require(adjustPayload != null) { "ADJUST 需要新的参数 payload" }
            val st = store.state()
            // 对派生结果做参数调整时，必须明确修订它的哪一个直接来源；派生结果本身不可改写
            val parentId = when {
                st.sources.containsKey(targetId) -> targetId
                reviseSourceId != null && st.sources.containsKey(reviseSourceId) -> reviseSourceId
                else -> throw ImportException(listOf(
                    "ADJUST 对派生结果必须指定要修订的直接来源（reviseSourceId）；测量不会被直接改写"))
            }
            newSourceId = reviseSource(parentId, adjustPayload, adjustProvenance ?: "parameter adjustment by $author")
        }
        val evt = store.append(EventTypes.DECISION_MADE) { seq ->
            Json.encodeToJsonElement(DecisionPayload(
                id = Ids.of("dec", seq), targetType = targetType, targetId = targetId,
                labCode = labCode, verdict = verdict, reason = reason, author = author,
                newSourceId = newSourceId
            )).jsonObject
        }
        stalenessSweep()
        return evt.payload["id"]!!.jsonPrimitive.content
    }

    fun voidDecision(decisionId: String, reason: String): String {
        val evt = store.append(EventTypes.DECISION_VOIDED) { _ ->
            Json.encodeToJsonElement(VoidPayload(decisionId, reason)).jsonObject
        }
        return evt.id
    }

    // ---------- 导入 ----------

    /** 导入一段样例（JSON 数组，元素含 kind/provenance/unit/payload）。格式错误整体拒绝并逐条报告。 */
    fun importEntries(jsonText: String): List<String> {
        val errors = mutableListOf<String>()
        val arr = try { Json.parseToJsonElement(jsonText).jsonArray }
        catch (e: Exception) { throw ImportException(listOf("不是合法 JSON 数组: ${e.message}")) }
        data class Entry(val kind: SourceKind, val prov: String, val unit: String, val payload: JsonObject, val family: String?)
        val entries = mutableListOf<Entry>()
        arr.forEachIndexed { i, el ->
            val o = el.jsonObjectOrNull ?: run { errors += "entry[$i] 不是对象"; return@forEachIndexed }
            val kind = o["kind"]?.jsonPrimitive?.contentOrNull?.let {
                runCatching { SourceKind.valueOf(it) }.getOrNull()
            } ?: run { errors += "entry[$i].kind 缺失或不认识"; return@forEachIndexed }
            val prov = o["provenance"]?.jsonPrimitive?.contentOrNull
                ?: run { errors += "entry[$i].provenance 缺失（每个源都必须有来源）"; return@forEachIndexed }
            val unit = o["unit"]?.jsonPrimitive?.contentOrNull
                ?: run { errors += "entry[$i].unit 缺失（领域量必须带单位/坐标基准）"; return@forEachIndexed }
            val payload = o["payload"]?.jsonObjectOrNull
                ?: run { errors += "entry[$i].payload 缺失或不是对象"; return@forEachIndexed }
            entries += Entry(kind, prov, unit, payload, o["family"]?.jsonPrimitive?.contentOrNull)
        }
        if (errors.isNotEmpty()) throw ImportException(errors)
        return entries.map { submitSource(it.kind, it.payload, it.prov, it.unit, family = it.family) }
    }
}
