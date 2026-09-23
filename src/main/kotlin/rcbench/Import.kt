package rcbench

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Serializable
data class ImportReport(
    val imported: MutableList<String> = mutableListOf(),
    val revised: MutableList<String> = mutableListOf(),
    val unchanged: MutableList<String> = mutableListOf(),
    val diagnostics: MutableList<Diagnostic> = mutableListOf(),
)

object Importer {

    private fun provOf(obj: JsonObject, stage: String, id: String, diags: MutableList<Diagnostic>): Provenance? {
        val src = obj["source"] as? JsonObject
        val s = (src?.get("source") as? JsonPrimitive)?.content
        if (s.isNullOrBlank()) {
            diags.add(Diagnostic(stage, "$id.source", "缺少来源(source)，已跳过该条", obj.toString().take(120)))
            return null
        }
        val d = (src["detail"] as? JsonPrimitive)?.content ?: ""
        return Provenance(s, d)
    }

    private fun num(obj: JsonObject, key: String, stage: String, id: String, diags: MutableList<Diagnostic>): Double? {
        val el = obj[key] ?: run {
            diags.add(Diagnostic(stage, "$id.$key", "缺少必填数值字段", obj.toString().take(120)))
            return null
        }
        val raw = (el as? JsonPrimitive)?.content ?: el.toString()
        val v = (el as? JsonPrimitive)?.doubleOrNull ?: raw.toDoubleOrNull()
        if (v == null) {
            diags.add(Diagnostic(stage, "$id.$key", "无法转换为数值，原值保留在诊断中，不丢入派生层", raw))
            return null
        }
        return v
    }

    private fun str(obj: JsonObject, key: String): String? = (obj[key] as? JsonPrimitive)?.content

    fun importDocument(store: Store, text: String, actor: String = "importer"): ImportReport {
        val report = ImportReport()
        val diags = report.diagnostics
        val root: JsonObject = try {
            val el = json.parseToJsonElement(text)
            el as? JsonObject ?: run {
                diags.add(Diagnostic("document", "root", "顶层必须是 JSON 对象", text.take(120)))
                return report
            }
        } catch (e: Exception) {
            diags.add(Diagnostic("document", "json", "JSON 解析失败: ${e.message}", text.take(120)))
            return report
        }
        val fmt = (root["format"] as? JsonPrimitive)?.content
        if (fmt != "rcbench-import/v1") {
            diags.add(Diagnostic("document", "format", "format 必须为 rcbench-import/v1，实际: ${fmt ?: "缺失"}"))
            return report
        }

        fun items(key: String): List<JsonObject> {
            val arr = root[key] ?: return emptyList()
            val list = arr as? kotlinx.serialization.json.JsonArray
            if (list == null) {
                diags.add(Diagnostic("document", key, "应为数组"))
                return emptyList()
            }
            return list.mapNotNull {
                (it as? JsonObject) ?: run {
                    diags.add(Diagnostic("document", key, "数组元素应为对象: ${it.toString().take(80)}"))
                    null
                }
            }
        }

        for (o in items("curves")) {
            val id = str(o, "id") ?: run { diags.add(Diagnostic("curve", "id", "缺少 id")); continue }
            val prov = provOf(o, "curve", id, diags) ?: continue
            val pts = mutableListOf<CurvePoint>()
            var ok = true
            val parr = o["points"] as? kotlinx.serialization.json.JsonArray
            if (parr == null) { diags.add(Diagnostic("curve", "$id.points", "缺少 points 数组")); continue }
            for (pe in parr) {
                val po = pe as? JsonObject ?: run { ok = false; diags.add(Diagnostic("curve", "$id.points", "点应为对象")); break }
                val cb = num(po, "calBP", "curve", id, diags)
                val c14 = num(po, "c14", "curve", id, diags)
                val sg = num(po, "sigma", "curve", id, diags)
                if (cb == null || c14 == null || sg == null) { ok = false; break }
                pts.add(CurvePoint(cb, c14, sg))
            }
            if (!ok) continue
            val name = str(o, "name") ?: id
            putEntity(store, report, id, "Curve",
                fingerprintOf = { Calibration.fingerprint("curve", name, pts.joinToString(";") { "${it.calBP},${it.c14},${it.sigma}" }) },
                currentFp = { cur -> Calibration.fingerprint("curve", cur.name, cur.points.joinToString(";") { "${it.calBP},${it.c14},${it.sigma}" }) },
                current = { store.projection.currentCurve(id) },
                make = { v, parent -> CalibrationCurve(id, v, parent, name, pts, prov) },
            )
        }

        for (o in items("offsets")) {
            val id = str(o, "id") ?: run { diags.add(Diagnostic("offset", "id", "缺少 id")); continue }
            val prov = provOf(o, "offset", id, diags) ?: continue
            val mean = num(o, "mean", "offset", id, diags) ?: continue
            val sd = num(o, "sd", "offset", id, diags) ?: continue
            putEntity(store, report, id, "Offset",
                fingerprintOf = { Calibration.fingerprint("offset", "$mean", "$sd") },
                currentFp = { cur -> Calibration.fingerprint("offset", "${cur.mean}", "${cur.sd}") },
                current = { store.projection.currentOffset(id) },
                make = { v, parent -> ReservoirOffset(id, v, parent, mean, sd, prov) },
            )
        }

        for (o in items("eras")) {
            val id = str(o, "id") ?: run { diags.add(Diagnostic("era", "id", "缺少 id")); continue }
            val prov = provOf(o, "era", id, diags) ?: continue
            val conv = str(o, "convention") ?: run { diags.add(Diagnostic("era", "$id.convention", "缺少 convention")); continue }
            putEntity(store, report, id, "Era",
                fingerprintOf = { Calibration.fingerprint("era", conv) },
                currentFp = { cur -> Calibration.fingerprint("era", cur.convention) },
                current = { store.projection.currentEra(id) },
                make = { v, parent -> EraSystem(id, v, parent, conv, prov) },
            )
        }

        for (o in items("measurements")) {
            val id = str(o, "id") ?: run { diags.add(Diagnostic("measurement", "id", "缺少 id")); continue }
            val prov = provOf(o, "measurement", id, diags) ?: continue
            val rawAge = (o["ageBP"] as? JsonPrimitive)?.content ?: ""
            val rawSigma = (o["sigma"] as? JsonPrimitive)?.content ?: ""
            val age = num(o, "ageBP", "measurement", id, diags) ?: continue
            val sigma = num(o, "sigma", "measurement", id, diags) ?: continue
            if (sigma <= 0.0) {
                diags.add(Diagnostic("measurement", "$id.sigma", "标准差必须为正", rawSigma))
                continue
            }
            val lab = str(o, "labCode") ?: id
            val mat = str(o, "material") ?: ""
            val ctx = str(o, "context") ?: ""
            val raw = o.toString()
            putEntity(store, report, id, "Measurement",
                fingerprintOf = { Calibration.fingerprint("meas", lab, "$age", "$sigma", mat, ctx) },
                currentFp = { cur -> Calibration.fingerprint("meas", cur.labCode, "${cur.ageBP}", "${cur.sigma}", cur.material, cur.context) },
                current = { store.projection.currentMeasurement(id) },
                make = { v, parent -> Measurement(id, v, parent, lab, age, sigma, mat, ctx, raw, prov) },
            )
        }

        for (o in items("constraints")) {
            val id = str(o, "id") ?: run { diags.add(Diagnostic("constraint", "id", "缺少 id")); continue }
            val prov = provOf(o, "constraint", id, diags) ?: continue
            val before = str(o, "before") ?: run { diags.add(Diagnostic("constraint", "$id.before", "缺少 before")); continue }
            val after = str(o, "after") ?: run { diags.add(Diagnostic("constraint", "$id.after", "缺少 after")); continue }
            val note = str(o, "note") ?: ""
            putEntity(store, report, id, "Constraint",
                fingerprintOf = { Calibration.fingerprint("constraint", before, after, note) },
                currentFp = { cur -> Calibration.fingerprint("constraint", cur.beforeId, cur.afterId, cur.note) },
                current = { store.projection.currentConstraint(id) },
                make = { v, parent -> SequenceConstraint(id, v, parent, before, after, note, prov) },
            )
        }

        for (o in items("exclusions")) {
            val id = str(o, "id") ?: run { diags.add(Diagnostic("exclusion", "id", "缺少 id")); continue }
            val prov = provOf(o, "exclusion", id, diags) ?: continue
            val mid = str(o, "measurementId") ?: run { diags.add(Diagnostic("exclusion", "$id.measurementId", "缺少 measurementId")); continue }
            val excluded = (o["excluded"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull()
                ?: run { diags.add(Diagnostic("exclusion", "$id.excluded", "excluded 必须为 true/false")); continue }
            val reason = str(o, "reason") ?: ""
            putEntity(store, report, id, "Exclusion",
                fingerprintOf = { Calibration.fingerprint("excl", mid, "$excluded", reason) },
                currentFp = { cur -> Calibration.fingerprint("excl", cur.measurementId, "${cur.excluded}", cur.reason) },
                current = { store.projection.exclusions[id]?.maxByOrNull { it.version } },
                make = { v, parent -> ExclusionDecision(id, v, parent, mid, excluded, reason, prov) },
            )
        }

        for (o in items("decisions")) {
            val id = str(o, "id") ?: run { diags.add(Diagnostic("decision", "id", "缺少 id")); continue }
            val target = str(o, "targetId") ?: run { diags.add(Diagnostic("decision", "$id.targetId", "缺少 targetId")); continue }
            val action = str(o, "action") ?: run { diags.add(Diagnostic("decision", "$id.action", "缺少 action")); continue }
            val rationale = str(o, "rationale") ?: ""
            val who = str(o, "actor") ?: actor
            if (store.projection.decisions.containsKey(id)) {
                report.unchanged.add("decision:$id")
                continue
            }
            val d = ReviewDecision(id, target, action, rationale, who, store.clock.now())
            store.append("Decision", d, who)
            report.imported.add("decision:$id")
        }

        return report
    }

    private inline fun <reified T> putEntity(
        store: Store,
        report: ImportReport,
        id: String,
        type: String,
        crossinline fingerprintOf: () -> String,
        crossinline currentFp: (T) -> String,
        crossinline current: () -> T?,
        crossinline make: (Int, String?) -> T,
    ) where T : Any {
        val cur = current()
        val newFp = fingerprintOf()
        if (cur == null) {
            store.append(type, make(1, null))
            report.imported.add("$type:$id:v1")
        } else if (currentFp(cur) == newFp) {
            report.unchanged.add("$type:$id")
        } else {
            val v = versionOf(cur) + 1
            store.append(type, make(v, "$id:${versionOf(cur)}"))
            report.revised.add("$type:$id:v$v")
        }
    }

    private fun versionOf(e: Any): Int = when (e) {
        is Measurement -> e.version
        is CalibrationCurve -> e.version
        is ReservoirOffset -> e.version
        is EraSystem -> e.version
        is SequenceConstraint -> e.version
        is ExclusionDecision -> e.version
        else -> error("no version")
    }
}
