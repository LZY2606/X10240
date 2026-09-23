package gsb.persist

import gsb.domain.*
import kotlinx.serialization.json.*

/**
 * 导入层：接受可复制的小型样例与明确的格式错误。
 *  - 原测量值/标准差、曲线版本、偏移分布、年代系统、层序约束、排除决定各自必须有来源；
 *  - 原始表示逐字保留（RawValue.raw），派生层才规范化；
 *  - 无法转换的值留在原位置并附诊断，不为画图丢弃；
 *  - 致命错误导致整包拒绝；行级问题保留为诊断，对象标记 unusable。
 */
object CaseImporter {
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    data class ImportDiagnostic(val severity: String, val path: String, val message: String)
    data class ImportedCase(
        val project: Project,
        val curves: List<CalibrationCurve>,
        val measurements: List<Measurement>,
        val reservoirs: List<ReservoirAssumption>,
        val sequences: List<SequenceConstraint>,
        val exclusions: List<ExclusionDecision>,
        val diagnostics: List<ImportDiagnostic>
    ) {
        val hasFatal: Boolean get() = diagnostics.any { it.severity == "FATAL" }
    }

    fun parse(text: String, now: String): ImportedCase {
        val diags = mutableListOf<ImportDiagnostic>()
        val root = try {
            json.parseToJsonElement(text).jsonObject
        } catch (t: Throwable) {
            return fatal("root", "不是合法 JSON：${t.message}")
        }

        val projObj = root.obj("project") { diags += it }
        val project = if (projObj != null) parseProject(projObj, now, diags) else null
        if (project == null) return ImportedCase(
            Project("?", "?", "era:astronomical/BCE-CE", SourceRef("missing", now)),
            emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), diags
        )

        val curves = root.arr("curves") { diags += it }.mapIndexed { i, o ->
            parseCurve(o.jsonObject, "curves[$i]", now, diags)
        }
        val reservoirs = root.arr("reservoirs") { diags += it }.mapIndexed { i, o ->
            parseReservoir(o.jsonObject, "reservoirs[$i]", now, diags)
        }
        val measurements = root.arr("measurements") { diags += it }.mapIndexed { i, o ->
            parseMeasurement(o.jsonObject, "measurements[$i]", now, diags)
        }
        val sequences = root.arr("sequences") { diags += it }.mapIndexed { i, o ->
            parseSequence(o.jsonObject, "sequences[$i]", now, diags)
        }
        val exclusions = root.arr("exclusions") { diags += it }.mapIndexed { i, o ->
            parseExclusion(o.jsonObject, "exclusions[$i]", now, diags)
        }

        // 引用完整性（行级）
        val curveIds = curves.map { it.id }.toSet()
        val resIds = reservoirs.map { it.id }.toSet()
        val sampleIds = measurements.map { it.sampleId }.toSet()
        measurements.forEach { m ->
            if (m.reservoirId != null && m.reservoirId !in resIds)
                diags += ImportDiagnostic("ERROR", "measurement:${m.id}", "引用了不存在的 reservoir ${m.reservoirId}")
        }
        sequences.forEach { q ->
            q.members.forEach { mem ->
                if (mem.sampleId !in sampleIds)
                    diags += ImportDiagnostic("ERROR", "sequence:${q.id}", "层序成员 ${mem.sampleId} 没有对应测量")
            }
            val orders = q.members.map { it.order }
            if (orders.toSet().size != orders.size)
                diags += ImportDiagnostic("ERROR", "sequence:${q.id}", "层序 order 重复")
        }
        exclusions.forEach { e ->
            if (e.sampleId !in sampleIds)
                diags += ImportDiagnostic("ERROR", "exclusion:${e.id}", "排除决定引用了不存在的样品 ${e.sampleId}")
        }
        root["activeCurveId"]?.let { el ->
            val id = el.jsonPrimitive.content
            if (id !in curveIds) diags += ImportDiagnostic("ERROR", "activeCurveId", "活动曲线 $id 未在 curves 中注册")
        }
        if (curves.isEmpty()) diags += ImportDiagnostic("FATAL", "curves", "至少需要一条校准曲线")
        if (measurements.isEmpty()) diags += ImportDiagnostic("FATAL", "measurements", "至少需要一个测量")

        return ImportedCase(project, curves, measurements, reservoirs, sequences, exclusions, diags)
    }

    private fun fatal(path: String, msg: String) = ImportedCase(
        Project("?", "?", "era:astronomical/BCE-CE", SourceRef("missing", "?")),
        emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
        listOf(ImportDiagnostic("FATAL", path, msg))
    )

    private fun parseProject(o: JsonObject, now: String, d: MutableList<ImportDiagnostic>): Project? {
        val id = o.req("id", "project", d) ?: return null
        val name = o.req("name", "project", d) ?: "未命名项目"
        val era = o["eraSystem"]?.jsonPrimitive?.content
        if (era == null) d += ImportDiagnostic("ERROR", "project.eraSystem", "缺少年代系统（必须声明 BCE/CE 与天文年转换基准）")
        val src = parseSource(o["source"], "project.source", now, d)
        return Project(id, name, era ?: "era:astronomical/BCE-CE", src,
            activeCurveId = o["activeCurveId"]?.jsonPrimitive?.content,
            createdAt = now)
    }

    private fun parseCurve(o: JsonObject, path: String, now: String, d: MutableList<ImportDiagnostic>): CalibrationCurve {
        val id = o.req("id", path, d) ?: "$path#missing-id"
        val version = o.req("version", path, d) ?: "unknown"
        val label = o["label"]?.jsonPrimitive?.content ?: id
        val src = parseSource(o["source"], "$path.source", now, d)
        val pointsArr = o.arr("points") { d += it }
        val points = pointsArr.mapIndexed { i, el ->
            val p = el.jsonObject
            val calBp = p.num("calBp", "$path.points[$i]", d)
            val c14 = p.num("c14Bp", "$path.points[$i]", d)
            val sig = p.num("sigma", "$path.points[$i]", d)
            val psrc = parseSource(p["source"], "$path.points[$i].source", now, d,
                defaultOrigin = src.origin)
            CurvePoint(
                calBp = (calBp ?: 0.0).toInt(),
                c14Bp = c14 ?: Double.NaN,
                sigma = sig ?: Double.NaN,
                source = psrc
            ).also {
                if (c14 == null || sig == null)
                    d += ImportDiagnostic("FATAL", "$path.points[$i]", "曲线点缺少 c14Bp/sigma")
            }
        }.sortedBy { it.calBp }
        if (points.size < 2) d += ImportDiagnostic("FATAL", "$path.points", "曲线至少需要 2 个点")
        return CalibrationCurve(id, label, version, points, src,
            supersedes = o["supersedes"]?.jsonPrimitive?.content)
    }

    private fun parseMeasurement(o: JsonObject, path: String, now: String, d: MutableList<ImportDiagnostic>): Measurement {
        val id = o.req("id", path, d) ?: "$path#missing"
        val sampleId = o.req("sampleId", path, d) ?: id
        val lab = o["labCode"]?.jsonPrimitive?.content
        if (lab == null) d += ImportDiagnostic("WARN", "$path.labCode", "缺少实验室编号")
        val age = rawValue(o["c14Age"], "$path.c14Age", UnitTag.C14_BP, d)
        val sig = rawValue(o["c14Sigma"], "$path.c14Sigma", UnitTag.C14_BP, d)
        val src = parseSource(o["source"], "$path.source", now, d)
        val era = o["eraSystem"]?.jsonPrimitive?.content
        if (era == null) d += ImportDiagnostic("ERROR", "$path.eraSystem", "测量必须声明年代系统")
        return Measurement(
            id = id, sampleId = sampleId, labCode = lab ?: "UNKNOWN-LAB",
            c14Age = age, c14Sigma = sig,
            material = o["material"]?.jsonPrimitive?.content ?: "unspecified",
            reservoirId = o["reservoirId"]?.jsonPrimitive?.content,
            eraSystem = era ?: "era:astronomical/BCE-CE",
            source = src,
            parentId = o["parentId"]?.jsonPrimitive?.content
        )
    }

    private fun parseReservoir(o: JsonObject, path: String, now: String, d: MutableList<ImportDiagnostic>): ReservoirAssumption {
        val id = o.req("id", path, d) ?: "$path#missing"
        val dr = rawValue(o["deltaR"], "$path.deltaR", UnitTag.DELTA_R, d)
        val sr = rawValue(o["deltaRSigma"], "$path.deltaRSigma", UnitTag.DELTA_R, d)
        val src = parseSource(o["source"], "$path.source", now, d)
        return ReservoirAssumption(
            id = id,
            label = o["label"]?.jsonPrimitive?.content ?: id,
            deltaR = dr, deltaRSigma = sr, source = src,
            parentId = o["parentId"]?.jsonPrimitive?.content
        )
    }

    private fun parseSequence(o: JsonObject, path: String, now: String, d: MutableList<ImportDiagnostic>): SequenceConstraint {
        val id = o.req("id", path, d) ?: "$path#missing"
        val src = parseSource(o["source"], "$path.source", now, d)
        val members = o.arr("members") { d += it }.mapIndexedNotNull { i, el ->
            val mo = el.jsonObject
            val sid = mo.req("sampleId", "$path.members[$i]", d) ?: return@mapIndexedNotNull null
            val order = mo.num("order", "$path.members[$i]", d)
            SequenceMember(sid, (order ?: 0.0).toInt())
        }
        return SequenceConstraint(id, o["label"]?.jsonPrimitive?.content ?: id, members, src,
            parentId = o["parentId"]?.jsonPrimitive?.content)
    }

    private fun parseExclusion(o: JsonObject, path: String, now: String, d: MutableList<ImportDiagnostic>): ExclusionDecision {
        val id = o.req("id", path, d) ?: "$path#missing"
        val sid = o.req("sampleId", path, d) ?: "?"
        val reason = o["reason"]?.jsonPrimitive?.content
        if (reason == null) d += ImportDiagnostic("ERROR", "$path.reason", "排除决定必须给出原因（拒绝原因是一等数据）")
        val src = parseSource(o["source"], "$path.source", now, d)
        return ExclusionDecision(
            id = id, sampleId = sid,
            targetKind = o["targetKind"]?.jsonPrimitive?.content ?: "INTERVAL",
            intervalKey = o["intervalKey"]?.jsonPrimitive?.content,
            reason = reason ?: "", source = src,
            parentId = o["parentId"]?.jsonPrimitive?.content
        )
    }

    private fun rawValue(el: JsonElement?, path: String, unit: UnitTag, d: MutableList<ImportDiagnostic>): RawValue {
        if (el == null) {
            d += ImportDiagnostic("ERROR", path, "缺少数值（原始表示缺失）")
            return RawValue("", null, unit.symbol, "missing")
        }
        return when (el) {
            is JsonPrimitive -> {
                val raw = el.content
                val n = raw.trim().replace(Regex("[^0-9.\\-]"), "").toDoubleOrNull()
                if (n == null) {
                    d += ImportDiagnostic("WARN", path, "无法规范化的原始值 '$raw'，保留原位，不参与画图/计算")
                    RawValue(raw, null, unit.symbol, "unparseable:$raw")
                } else RawValue(raw, n, unit.symbol)
            }
            is JsonObject -> {
                val raw = el["raw"]?.jsonPrimitive?.content ?: ""
                val n = (el["value"]?.jsonPrimitive?.contentOrNullSafe() ?: raw.trim()).toDoubleOrNull()
                if (n == null) {
                    d += ImportDiagnostic("WARN", path, "无法规范化的原始值 '$raw'，保留原位")
                    RawValue(raw, null, el["unit"]?.jsonPrimitive?.content ?: unit.symbol, "unparseable:$raw")
                } else RawValue(raw, n, el["unit"]?.jsonPrimitive?.content ?: unit.symbol)
            }
            else -> RawValue("", null, unit.symbol, "unsupported-json")
        }
    }

    private fun parseSource(el: JsonElement?, path: String, now: String,
                           d: MutableList<ImportDiagnostic>, defaultOrigin: String? = null): SourceRef {
        if (el == null) {
            if (defaultOrigin != null) return SourceRef(defaultOrigin, now)
            d += ImportDiagnostic("ERROR", path, "缺少来源（每类输入都必须可溯源）")
            return SourceRef("UNSOURCED", now, "missing source")
        }
        val o = el.jsonObject
        val origin = o["origin"]?.jsonPrimitive?.content
        if (origin == null) d += ImportDiagnostic("ERROR", "$path.origin", "来源缺少 origin")
        return SourceRef(origin ?: "UNSOURCED",
            o["retrievedAt"]?.jsonPrimitive?.content ?: now,
            o["note"]?.jsonPrimitive?.content ?: "")
    }

    private fun JsonObject.req(key: String, path: String, d: MutableList<ImportDiagnostic>): String? {
        val v = this[key]?.jsonPrimitive?.content
        if (v.isNullOrBlank()) d += ImportDiagnostic("FATAL", "$path.$key", "缺少必填字段 $key")
        return v?.takeIf { it.isNotBlank() }
    }

    private fun JsonObject.num(key: String, path: String, d: MutableList<ImportDiagnostic>): Double? {
        val el = this[key] ?: return null
        val raw = el.jsonPrimitive.content
        return raw.trim().toDoubleOrNull() ?: run {
            d += ImportDiagnostic("FATAL", "$path.$key", "数值无法解析：'$raw'")
            null
        }
    }

    private fun JsonObject.obj(key: String, onErr: (ImportDiagnostic) -> Unit): JsonObject? =
        runCatching { this[key]?.jsonObject }.getOrElse { onErr(ImportDiagnostic("FATAL", key, "$key 必须是对象")); null }

    private fun JsonObject.arr(key: String, onErr: (ImportDiagnostic) -> Unit): List<JsonElement> =
        runCatching { (this[key] as? JsonArray)?.toList() ?: emptyList() }
            .getOrElse { onErr(ImportDiagnostic("FATAL", key, "$key 必须是数组")); emptyList() }

    private fun JsonPrimitive.contentOrNullSafe(): String? = content
}
