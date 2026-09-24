package org.chrono.workbench.case

import org.chrono.workbench.model.ConvertStatus
import org.chrono.workbench.model.Measurement
import org.chrono.workbench.model.SourceRef
import org.chrono.workbench.util.Ids

data class ImportedRow(val measurement: Measurement, val errors: List<String>)

data class ImportResult(val accepted: List<ImportedRow>, val rejected: List<ImportedRow>) {
    val all: List<ImportedRow> get() = accepted + rejected
}

object ImportLayer {
    fun parseLaboratorySheet(csv: String, sourceName: String, locator: String): ImportResult {
        val accepted = mutableListOf<ImportedRow>()
        val rejected = mutableListOf<ImportedRow>()
        val lines = csv.trim().lines()
        require(lines.isNotEmpty()) { "empty laboratory sheet" }
        val header = lines.first().split(",").map { it.trim().lowercase() }
        val expected = listOf("labcode", "c14age", "sigma", "unit", "material")
        if (header.size < 5) {
            throw IllegalArgumentException(
                "format error: expected header ${expected.joinToString(",")} but got ${lines.first()}"
            )
        }
        expected.forEachIndexed { i, col ->
            if (!header[i].startsWith(col)) {
                throw IllegalArgumentException("format error: column ${i + 1} must be '$col', found '${header.getOrNull(i)}'")
            }
        }
        lines.drop(1).filter { it.isNotBlank() }.forEachIndexed { idx, line ->
            val seq = idx + 1
            val cells = line.split(",", limit = 5).map { it.trim() }
            val errors = mutableListOf<String>()
            if (cells.size < 5) errors += "row has ${cells.size} columns, expected 5"
            val lab = cells.getOrNull(0).orEmpty()
            val ageRaw = cells.getOrNull(1).orEmpty()
            val sigmaRaw = cells.getOrNull(2).orEmpty()
            val unit = cells.getOrNull(3).orEmpty()
            val material = cells.getOrNull(4).orEmpty()
            if (lab.isEmpty()) errors += "missing lab code"
            if (unit.isNotEmpty() && unit != "14C BP" && unit != "BP") errors += "unit '$unit' is not a recognised radiocarbon unit"
            val age = ageRaw.toDoubleOrNull()
            val sigma = sigmaRaw.toDoubleOrNull()
            if (age == null) errors += "age '$ageRaw' is not numeric"
            if (sigma == null) errors += "sigma '$sigmaRaw' is not numeric"
            if (sigma != null && sigma <= 0.0) errors += "sigma must be positive, got $sigma"
            val id = Ids.measurement(lab.ifEmpty { "ROW$seq" }, ageRaw, sigmaRaw, locator, seq)
            val measurement = Measurement(
                id = id,
                labCode = lab.ifEmpty { "ROW$seq" },
                ageRaw = ageRaw,
                sigmaRaw = sigmaRaw,
                unit = unit.ifEmpty { "unconverted" },
                material = material,
                ageBP = if (errors.isEmpty()) age else null,
                sigmaBP = if (errors.isEmpty()) sigma else null,
                convertStatus = if (errors.isEmpty()) ConvertStatus.OK.name else ConvertStatus.UNCONVERTIBLE.name,
                diagnostics = errors,
                source = SourceRef("radiocarbon-measurement", sourceName, locator, line),
                seq = seq
            )
            val row = ImportedRow(measurement, errors)
            if (errors.isEmpty()) accepted += row else rejected += row
        }
        return ImportResult(accepted, rejected)
    }
}
