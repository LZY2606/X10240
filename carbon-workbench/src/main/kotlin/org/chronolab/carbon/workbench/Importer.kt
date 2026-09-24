package org.chronolab.carbon.workbench

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.chronolab.carbon.calib.SyntheticCurves
import org.chronolab.carbon.model.Diagnostic
import org.chronolab.carbon.model.Origin
import org.chronolab.carbon.model.SourceKind

/** 导入错误：带稳定 code，明确指出字段；样例导入必须能复现这些错误。 */
data class ImportException(val errors: List<Diagnostic>) : RuntimeException(errors.joinToString("; ") { it.message })

/**
 * 导入层。原始表示保留在 rawRepresentations，规范化字段在 fields；
 * 无法转换的值不删除，作为诊断留在原来源节点。
 */
object Importer {
    val json = Json { ignoreUnknownKeys = false }

    data class ParsedSample(
        val kind: SourceKind,
        val logicalId: String,
        val eraSystem: String,
        val origin: Origin,
        val fields: JsonObject,
        val raw: Map<String, String>,
        val diagnostics: List<Diagnostic>
    )

    fun parseBundle(text: String): List<ParsedSample> {
        val root = try {
            json.parseToJsonElement(text).jsonObject
        } catch (e: Exception) {
            throw ImportException(listOf(Diagnostic("BUNDLE_NOT_JSON", "ERROR", "导入包不是合法 JSON：${e.message}")))
        }
        val items = root["samples"]?.jsonArray
            ?: throw ImportException(listOf(Diagnostic("BUNDLE_NO_SAMPLES", "ERROR", "导入包缺少 samples 数组")))
        val errors = mutableListOf<Diagnostic>()
        val parsed = items.mapIndexed { i, el ->
            runCatching { parseOne(el.jsonObject, i) }.getOrElse { e ->
                if (e is ImportException) errors += e.errors else errors += Diagnostic("ITEM_$i", "ERROR", e.message ?: "未知错误")
                null
            }
        }
        if (errors.any { it.severity == "ERROR" }) throw ImportException(errors)
        return parsed.filterNotNull()
    }

    private fun parseOne(o: JsonObject, index: Int): ParsedSample {
        val errors = mutableListOf<Diagnostic>()
        val warnings = mutableListOf<Diagnostic>()
        val type = o.str("type") ?: { errors += Diagnostic("MISSING_TYPE", "ERROR", "samples[$index] 缺少 type"); null }()
        val logicalId = o.str("logicalId") ?: { errors += Diagnostic("MISSING_ID", "ERROR", "samples[$index] 缺少 logicalId"); "item-$index" }()
        val originObj = o["origin"]?.jsonObject
        if (originObj == null) errors += Diagnostic("MISSING_ORIGIN", "ERROR", "$logicalId 缺少 origin（citation/suppliedBy/receivedOn/fragmentRef）")
        val origin = originObj?.let {
            Origin(
                it.str("citation") ?: "".also { },
                it.str("suppliedBy") ?: "",
                it.str("receivedOn") ?: "",
                it.str("fragmentRef") ?: ""
            )
        } ?: Origin("", "", "", "")
        if (errors.isNotEmpty()) throw ImportException(errors)
        val rawMap = mutableMapOf<String, String>()
        val fields = when (type) {
            "measurement" -> parseMeasurement(o, logicalId, errors, warnings, rawMap)
            "reservoir" -> parseReservoir(o, logicalId, errors, warnings, rawMap)
            "sequence" -> parseSequence(o, logicalId, errors, warnings, rawMap)
            "exclusion" -> parseExclusion(o, logicalId, errors, warnings, rawMap)
            "curve" -> parseCurve(o, logicalId, errors, warnings, rawMap)
            else -> { errors += Diagnostic("UNKNOWN_TYPE", "ERROR", "$logicalId 的 type=$type 不受支持"); buildJsonObject {} }
        }
        if (errors.isNotEmpty()) throw ImportException(errors)
        val era = when (type) {
            "measurement" -> org.chronolab.carbon.model.ERA_BP
            "curve" -> "${org.chronolab.carbon.model.ERA_CALBP} + ${org.chronolab.carbon.model.ERA_BP}"
            "reservoir" -> "deltaR years (modern-reservoir correction)"
            "sequence" -> org.chronolab.carbon.model.ERA_CALBP
            else -> org.chronolab.carbon.model.ERA_CALBP
        }
        return ParsedSample(SourceKind.valueOf(
            when (type) {
                "measurement" -> "MEASUREMENT"; "reservoir" -> "RESERVOIR_MODEL"
                "sequence" -> "SEQUENCE_LINKS"; "exclusion" -> "EXCLUSION"; "curve" -> "CALIBRATION_CURVE"
                else -> "GENERIC"
            }
        ), logicalId, era, origin, fields, rawMap, warnings)
    }

    private fun JsonObject.str(k: String) = this[k]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }

    private fun num(o: JsonObject, k: String, id: String, errors: MutableList<Diagnostic>, raw: MutableMap<String, String>): Double? {
        val el = o[k]
        if (el == null || el is JsonNull) {
            errors += Diagnostic("MISSING_$k", "ERROR", "$id 缺少数值字段 $k"); return null
        }
        val text = el.jsonPrimitive.content
        raw[k] = text
        val cleaned = text.replace("±", "").replace("BP", "").replace(",", "").trim()
        return cleaned.toDoubleOrNull() ?: run {
            errors += Diagnostic("UNCONVERTIBLE_$k", "ERROR", "$id 的 $k='$text' 无法转换为数值；原值保留在 rawRepresentations")
            null
        }
    }

    private fun parseMeasurement(o: JsonObject, id: String, errors: MutableList<Diagnostic>, warnings: MutableList<Diagnostic>, raw: MutableMap<String, String>): JsonObject {
        val age = num(o, "ageBP", id, errors, raw)
        val sigma = num(o, "sigmaBP", id, errors, raw)
        if (sigma != null && sigma <= 0) errors += Diagnostic("SIGMA_NOT_POSITIVE", "ERROR", "$id sigmaBP 必须为正")
        if (age != null && (age < 0 || age > 60000)) warnings += Diagnostic("AGE_RANGE", "WARNING", "$id ageBP 超出常用 0–60000 BP 范围")
        val reservoir = o.str("reservoirModel")
        return buildJsonObject {
            if (age != null) put("ageBP", age)
            if (sigma != null) put("sigmaBP", sigma)
            put("ageUnit", "14C years BP")
            put("sigmaUnit", "14C years (1 sigma)")
            if (reservoir != null) put("reservoirModel", reservoir)
            o["sampleCode"]?.let { put("sampleCode", it.jsonPrimitive.content) }
            o["material"]?.let { put("material", it.jsonPrimitive.content) }
        }
    }

    private fun parseReservoir(o: JsonObject, id: String, errors: MutableList<Diagnostic>, warnings: MutableList<Diagnostic>, raw: MutableMap<String, String>): JsonObject {
        val compsEl = o["components"]?.jsonArray
            ?: run { errors += Diagnostic("MISSING_COMPONENTS", "ERROR", "$id 缺少 components 混合数组"); return buildJsonObject {} }
        var totalWeight = 0.0
        val comps = JsonArray(compsEl.mapIndexed { i, c ->
            val co = c.jsonObject
            val mean = num(co, "meanYears", "$id.components[$i]", errors, mutableMapOf<String, String>().also { })
            val sd = num(co, "sigmaYears", "$id.components[$i]", errors, mutableMapOf())
            val wText = co["weight"]?.jsonPrimitive?.content ?: "0"
            val w = wText.toDoubleOrNull()
            if (w == null) errors += Diagnostic("UNCONVERTIBLE_weight", "ERROR", "$id.components[$i].weight 非数值") else totalWeight += w
            if (sd != null && sd < 0) errors += Diagnostic("SIGMA_NEGATIVE", "ERROR", "$id.components[$i].sigmaYears 为负")
            buildJsonObject {
                if (mean != null) put("meanYears", mean)
                if (sd != null) put("sigmaYears", sd)
                if (w != null) put("weight", w)
            }
        })
        if (totalWeight !in 0.99..1.01) errors += Diagnostic("WEIGHTS_NOT_NORMALIZED", "ERROR", "$id components 权重之和=$totalWeight，必须为 1")
        val marine = o["marineReservoirYears"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0
        return buildJsonObject {
            put("label", o.str("label") ?: id)
            put("marineReservoirYears", marine)
            put("components", comps)
        }
    }

    private fun parseSequence(o: JsonObject, id: String, errors: MutableList<Diagnostic>, warnings: MutableList<Diagnostic>, raw: MutableMap<String, String>): JsonObject {
        val linksEl = o["links"]?.jsonArray
            ?: run { errors += Diagnostic("MISSING_LINKS", "ERROR", "$id 缺少 links 数组"); return buildJsonObject {} }
        val links = JsonArray(linksEl.mapIndexed { i, l ->
            val lo = l.jsonObject
            val older = lo.str("older") ?: run { errors += Diagnostic("LINK_OLDER", "ERROR", "$id.links[$i] 缺少 older"); "?" }
            val younger = lo.str("younger") ?: run { errors += Diagnostic("LINK_YOUNGER", "ERROR", "$id.links[$i] 缺少 younger"); "?" }
            if (older == younger) errors += Diagnostic("LINK_SELF", "ERROR", "$id.links[$i] 自反关系：$older")
            buildJsonObject {
                put("linkId", lo.str("linkId") ?: "link-$i")
                put("older", older); put("younger", younger)
                lo["justification"]?.let { put("justification", it.jsonPrimitive.content) }
            }
        })
        return buildJsonObject { put("links", links) }
    }

    private fun parseExclusion(o: JsonObject, id: String, errors: MutableList<Diagnostic>, warnings: MutableList<Diagnostic>, raw: MutableMap<String, String>): JsonObject {
        val ids = o["excludedLogicalIds"]?.jsonArray?.map { it.jsonPrimitive.content }
            ?: run { errors += Diagnostic("MISSING_EXCLUSIONS", "ERROR", "$id 缺少 excludedLogicalIds"); return buildJsonObject {} }
        val reasons = o["reasons"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
        if (ids.isEmpty()) warnings += Diagnostic("EMPTY_EXCLUSION", "WARNING", "$id 排除集合为空")
        return buildJsonObject {
            put("excludedLogicalIds", JsonArray(ids.map { kotlinx.serialization.json.JsonPrimitive(it) }))
            put("reasons", JsonArray(reasons.map { kotlinx.serialization.json.JsonPrimitive(it) }))
        }
    }

    private fun parseCurve(o: JsonObject, id: String, errors: MutableList<Diagnostic>, warnings: MutableList<Diagnostic>, raw: MutableMap<String, String>): JsonObject {
        val builtin = o.str("builtin")
        if (builtin != null) {
            val curve = when (builtin) {
                "synthetic-v1" -> SyntheticCurves.v1()
                "synthetic-v2" -> SyntheticCurves.v2()
                else -> { errors += Diagnostic("UNKNOWN_CURVE_BUILTIN", "ERROR", "$id 内建曲线 $builtin 未知"); return buildJsonObject {} }
            }
            return buildJsonObject {
                put("curveId", curve.curveId)
                put("version", curve.version)
                put("description", curve.description)
                put("points", JsonArray(curve.points.map { p ->
                    buildJsonObject {
                        put("calBP", p.calBP); put("radiocarbonBP", p.radiocarbonBP); put("sigma", p.sigma)
                    }
                }))
            }
        }
        errors += Diagnostic("MISSING_CURVE", "ERROR", "$id 曲线来源必须使用 builtin (synthetic-v1/synthetic-v2)，离线包不含外部表")
        return buildJsonObject {}
    }
}
