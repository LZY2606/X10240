package workbench

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** 导入层：接受可复制的小型样例；格式错误逐条明确报告；原始输入不可覆盖。 */
object Importer {
    val KINDS = setOf("measurement", "curve", "offset-dist", "era-system", "sequence", "exclusion")

    data class ImportResult(
        val ok: Boolean,
        val errors: List<String>,
        val registered: List<SourceRecord>,
        val diagnostics: List<String>
    )

    val SAMPLE = """
{
  "sources": [
    {"kind": "measurement", "origin": "实验室编号 Lab-X-001", "raw": "2450 ± 30 BP",
     "data": {"c14Age": 2450, "c14Sigma": 30, "unit": "BP", "datum": "calBP(1950)"}},
    {"kind": "offset-dist", "origin": "区域储库数据集 R-2024", "raw": "ΔR=150±40",
     "data": {"assumptions": [{"offset": 150, "weight": 1.0, "label": "ΔR=150"}]}},
    {"kind": "era-system", "origin": "历史纪年约定", "raw": "no year zero",
     "data": {"datum": "historical-era(no-year-zero)", "epoch": 1950}}
  ]
}
""".trimIndent()

    fun import(store: Store, text: String): ImportResult {
        val errors = mutableListOf<String>()
        val diags = mutableListOf<String>()
        val root = try {
            JsonFmt.parseToJsonElement(text) as? JsonObject
        } catch (e: Exception) { null }
            ?: return ImportResult(false, listOf("格式错误：不是合法 JSON 对象；请粘贴完整样例（见下方示例）"), emptyList(), emptyList())
        val arr = root["sources"]?.jsonArray
            ?: return ImportResult(false, listOf("格式错误：缺少顶层字段 \"sources\" 数组"), emptyList(), emptyList())

        data class Pending(val kind: String, val origin: String, val raw: String, val data: JsonObject, val d: List<String>)
        val pending = mutableListOf<Pending>()
        arr.forEachIndexed { i, el ->
            val o = el as? JsonObject
            if (o == null) { errors.add("第 ${i + 1} 条：不是对象"); return@forEachIndexed }
            val kind = o["kind"]?.jsonPrimitive?.content
            if (kind == null || kind !in KINDS) { errors.add("第 ${i + 1} 条：kind 缺失或不受支持（支持：${KINDS.joinToString()}）"); return@forEachIndexed }
            val origin = o["origin"]?.jsonPrimitive?.content
            if (origin.isNullOrBlank()) { errors.add("第 ${i + 1} 条：缺少 origin（每条来源必须注明出处）"); return@forEachIndexed }
            val raw = o["raw"]?.jsonPrimitive?.content
            if (raw == null) { errors.add("第 ${i + 1} 条：缺少 raw（原始表示必须保留）"); return@forEachIndexed }
            val data = o["data"] as? JsonObject
            if (data == null) { errors.add("第 ${i + 1} 条：缺少 data 对象"); return@forEachIndexed }
            val dd = mutableListOf<String>()
            if (kind == "measurement") {
                val age = data["c14Age"]?.jsonPrimitive?.content?.toDoubleOrNull()
                val sig = data["c14Sigma"]?.jsonPrimitive?.content?.toDoubleOrNull()
                if (age == null) { dd.add("c14Age 无法转换为数值，保留原始表示 \"${data["c14Age"]}\" 在原位置") }
                if (sig == null || sig <= 0) { dd.add("c14Sigma 缺失或非正数，保留原始表示 \"${data["c14Sigma"]}\" 在原位置") }
                if (data["unit"]?.jsonPrimitive?.content != "BP") dd.add("unit 非 BP：\"${data["unit"]}\"，未做单位换算，保留原值")
            }
            pending.add(Pending(kind, origin, raw, data, dd))
        }
        if (errors.isNotEmpty()) return ImportResult(false, errors, emptyList(), diags)

        val registered = pending.map { p ->
            diags.addAll(p.d)
            store.registerSource(p.kind, p.origin, p.raw, p.data, p.d)
        }
        return ImportResult(true, emptyList(), registered, diags)
    }
}
