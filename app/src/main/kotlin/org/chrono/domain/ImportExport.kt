package org.chrono.domain

import kotlinx.serialization.json.*

data class ImportDiagnostic(val path: String, val message: String)
class ImportException(val diagnostics: List<ImportDiagnostic>) :
    RuntimeException("import failed:\n" + diagnostics.joinToString("\n") { "  ${it.path}: ${it.message}" })

/**
 * 导入层：
 *  1) 完整事件束（导出再导入，保持身份/顺序/来源关系，经状态机校验）
 *  2) 可复制的小型测量样例 TSV/文本（明确的格式错误，带行号）
 * 原始输入不可转换时生成带诊断的 Measurement（留在原位置），而不是丢样本。
 */
object ImportExport {
    val json = Json { classDiscriminator = "type"; ignoreUnknownKeys = true; encodeDefaults = true }

    fun parseEventBundle(text: String): List<DomainEvent> {
        val diags = ArrayList<ImportDiagnostic>()
        val arr = try {
            Json.parseToJsonElement(text).jsonArray
        } catch (t: Throwable) {
            throw ImportException(listOf(ImportDiagnostic("$", "bundle must be a JSON array of events: ${t.message}")))
        }
        val out = ArrayList<DomainEvent>()
        arr.forEachIndexed { idx, el ->
            try {
                out += json.decodeFromJsonElement(DomainEvent.serializer(), el)
            } catch (t: Throwable) {
                diags += ImportDiagnostic("/$idx", t.message ?: "invalid event")
            }
        }
        if (diags.isNotEmpty()) throw ImportException(diags)
        return out
    }

    /**
     * 小型样例格式（制表符或逗号分隔，首行表头），用于人工复制粘贴：
     * measurementId  labCode  material  ageRaw  sigmaRaw  reservoirMean reservoirSd  sourceRef
     */
    fun parseSampleSheet(text: String, curveId: String, curveVersion: String, projectId: String, actor: String, at: String): List<DomainEvent> {
        val diags = ArrayList<ImportDiagnostic>()
        val lines = text.lines().filter { it.isNotBlank() }
        require(lines.size >= 2) { throw ImportException(listOf(ImportDiagnostic("$", "sheet needs a header row and >=1 data row"))) }
        val header = split(lines[0]).map { it.trim().lowercase() }
        val need = listOf("measurementid", "age", "sigma")
        for (n in need) if (n !in header) diags += ImportDiagnostic("/header", "missing column '$n'")
        if (diags.isNotEmpty()) throw ImportException(diags)
        val events = ArrayList<DomainEvent>()
        for (li in 1 until lines.size) {
            val row = split(lines[li])
            val base = "/line/${li + 1}"
            if (row.size < 3) { diags += ImportDiagnostic(base, "expected at least id, age, sigma columns"); continue }
            fun col(name: String): String? = header.indexOf(name).let { if (it >= 0 && it < row.size) row[it].trim().ifBlank { null } else null }
            val id = col("measurementid") ?: run { diags += ImportDiagnostic("$base/measurementId", "missing"); continue }
            val ageRaw = col("age")!!
            val sigRaw = col("sigma")!!
            val ageD = parseC14(ageRaw)
            val sigD = parseC14(sigRaw)
            val mDiags = ArrayList<String>()
            val ageQ = if (ageD != null) Quantity(ageD, Units.BP, raw = ageRaw)
            else Quantity(Double.NaN, "?", raw = ageRaw, diagnostic = "ERROR: cannot parse 14C age '$ageRaw'").also { mDiags += "ERROR: cannot parse 14C age '$ageRaw'" }
            val sigQ = if (sigD != null) Quantity(sigD, Units.BP, raw = sigRaw)
            else Quantity(Double.NaN, "?", raw = sigRaw, diagnostic = "ERROR: cannot parse sigma '$sigRaw'").also { mDiags += "ERROR: cannot parse sigma '$sigRaw'" }
            val drMean = col("reservoirdelta")?.toDoubleOrNull()
            val drSd = (col("reservoirsd") ?: "0").toDoubleOrNull() ?: 0.0
            val reservoir = if (drMean != null) ReservoirModel.Gaussian(drMean, drSd, "sheet:$base/reservoirDelta") else null
            val m = Measurement(id, 1, null,
                col("labcode") ?: id, col("material") ?: "unspecified",
                ageQ, sigQ, curveId, curveVersion, reservoir,
                col("sourceref") ?: "sheet:$base", mDiags)
            events += DomainEvent.MeasurementSubmitted("ev-sheet-$li", projectId, m, actor, at, null, m.sourceRef)
        }
        if (diags.isNotEmpty()) throw ImportException(diags)
        return events
    }

    private fun split(line: String): List<String> =
        if (line.contains('\t')) line.split('\t') else line.split(',')

    private fun parseC14(s: String): Double? {
        val cleaned = s.replace("BP", "").replace("±", "").replace("+/-", "").trim()
        return cleaned.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }
    }
}
