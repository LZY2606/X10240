package rgsb.ingest

import kotlinx.serialization.json.Json
import rgsb.model.CasePack
import rgsb.model.ImportDiagnostic

/**
 * Accepts the small, copyable offline domain case pack and reports explicit format
 * errors. Original representations are preserved; values that cannot be normalized stay
 * in place with an UNCONVERTED diagnostic instead of being dropped.
 */
object CasePackValidator {
    val lenientJson: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }

    fun parsePack(text: String): Pair<CasePack?, List<ImportDiagnostic>> {
        val diags = mutableListOf<ImportDiagnostic>()
        val pack = try {
            lenientJson.decodeFromString(CasePack.serializer(), text)
        } catch (e: Exception) {
            diags += ImportDiagnostic("ERROR", "pack", "<root>",
                "case pack is not valid JSON for packFormat rgsb-casepack-1: ${e.message?.take(200)}")
            return null to diags
        }
        if (pack.packFormat != "rgsb-casepack-1") {
            diags += ImportDiagnostic("ERROR", "pack", "packFormat",
                "unsupported packFormat '${pack.packFormat}', expected 'rgsb-casepack-1'", pack.packFormat)
        }
        if (pack.curves.isEmpty()) diags += ImportDiagnostic("ERROR", "curves", "$", "at least one calibration curve is required")
        if (pack.measurements.isEmpty()) diags += ImportDiagnostic("ERROR", "measurements", "$", "at least one measurement is required")

        val curveIds = mutableSetOf<String>()
        for ((i, c) in pack.curves.withIndex()) {
            if (!curveIds.add(c.id)) diags += ImportDiagnostic("ERROR", "curves", "[$i].id", "duplicate curve id ${c.id}", c.id)
            if (c.points.size < 4) diags += ImportDiagnostic("ERROR", "curves", "${c.id}.points", "curve needs >= 4 points", "${c.points.size}")
            val sorted = c.points.zipWithNext().all { (x, y) -> x.calYear < y.calYear }
            if (!sorted) diags += ImportDiagnostic("ERROR", "curves", "${c.id}.points", "points must be strictly increasing by calYear")
            if (c.version.isBlank()) diags += ImportDiagnostic("ERROR", "curves", "${c.id}.version", "curve version is required")
            requireSource(diags, "curves", c.id, c.source, c.sourceLabel)
            for (p in c.points) {
                if (p.uncertainty < 0) diags += ImportDiagnostic("ERROR", "curves", "${c.id}.points", "negative uncertainty", "${p.uncertainty}")
            }
        }
        val measIds = mutableSetOf<String>()
        for ((i, m) in pack.measurements.withIndex()) {
            if (!measIds.add(m.id)) diags += ImportDiagnostic("ERROR", "measurements", "[$i].id", "duplicate measurement id ${m.id}", m.id)
            requirePositiveDouble(diags, "measurements", "${m.id}.age", m.age.raw)
            requirePositiveDouble(diags, "measurements", "${m.id}.sigma", m.sigma.raw)
            if (m.sigma.unit !in setOf("14C yr", "14C years", "yr BP", "BP"))
                diags += ImportDiagnostic("WARNING", "measurements", "${m.id}.sigma.unit", "unexpected sigma unit '${m.sigma.unit}', kept verbatim", m.sigma.unit)
            requireSource(diags, "measurements", m.id, m.age.source, m.age.sourceLabel)
        }
        for ((i, o) in pack.offsets.withIndex()) {
            if (o.components.isEmpty()) diags += ImportDiagnostic("ERROR", "offsets", "${o.id}.components", "offset requires at least one component")
            val wsum = o.components.sumOf { it.weight }
            if (wsum <= 0) diags += ImportDiagnostic("ERROR", "offsets", "${o.id}.components", "weights must sum > 0", "$wsum")
            else if (kotlin.math.abs(wsum - 1.0) > 1e-6)
                diags += ImportDiagnostic("WARNING", "offsets", "${o.id}.components", "weights sum to $wsum, will be renormalized in derived layer", "$wsum")
            requireSource(diags, "offsets", o.id, o.source, o.sourceLabel)
        }
        requireSource(diags, "era", pack.era.id, pack.era.source, pack.era.sourceLabel)
        for (s in pack.sequences) {
            requireSource(diags, "sequences", s.id, s.source, s.sourceLabel)
            for (l in s.links) {
                if (l.before !in measIds) diags += ImportDiagnostic("ERROR", "sequences", "${s.id}.links", "unknown measurement ${l.before}", l.before)
                if (l.after !in measIds) diags += ImportDiagnostic("ERROR", "sequences", "${s.id}.links", "unknown measurement ${l.after}", l.after)
            }
        }
        for (e in pack.exclusions) {
            requireSource(diags, "exclusions", e.id, e.source, e.sourceLabel)
            if (e.calendarYearFrom > e.calendarYearTo)
                diags += ImportDiagnostic("ERROR", "exclusions", e.id, "calendarYearFrom > calendarYearTo", "${e.calendarYearFrom}..${e.calendarYearTo}")
        }
        return pack to diags
    }

    private fun requirePositiveDouble(diags: MutableList<ImportDiagnostic>, section: String, loc: String, raw: String) {
        val v = raw.replace(",", "").trim().toDoubleOrNull()
        when {
            v == null -> diags += ImportDiagnostic("UNCONVERTED", section, loc, "cannot normalize numeric value; kept verbatim at source", raw)
            v < 0 -> diags += ImportDiagnostic("ERROR", section, loc, "value must be non-negative", raw)
        }
    }

    private fun requireSource(diags: MutableList<ImportDiagnostic>, section: String, id: String, source: String, label: String) {
        if (source.isBlank()) diags += ImportDiagnostic("ERROR", section, "$id.source", "source citation is required")
        if (label.isBlank()) diags += ImportDiagnostic("WARNING", section, "$id.sourceLabel", "human source label is empty")
    }
}
