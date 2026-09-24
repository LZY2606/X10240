package org.gsb.radiocarbon.casepack

import org.gsb.radiocarbon.model.CalibrationCurve
import org.gsb.radiocarbon.model.CurvePoint

/** Parser/serializer for the offline `.rccurve` text format, with line-scoped errors. */
object CurveFile {
    class ParseException(message: String) : RuntimeException(message)

    fun parse(text: String, sourceRef: String): CalibrationCurve {
        val meta = mutableMapOf<String, String>()
        val points = ArrayList<CurvePoint>()
        var dataStarted = false
        text.lineSequence().forEachIndexed { idx, rawLine ->
            val lineNo = idx + 1
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed
            try {
                if (!dataStarted && line.startsWith("calBP", ignoreCase = true)) {
                    dataStarted = true
                    return@forEachIndexed
                }
                if (!dataStarted) {
                    val k = line.substringBefore(":").trim()
                    val v = line.substringAfter(":").trim()
                    if (":" !in line || v.isEmpty()) {
                        throw ParseException("expected 'key: value' header or 'calBP c14BP sigma' row")
                    }
                    meta[k] = v
                } else {
                    val parts = line.split(Regex("\\s+"))
                    if (parts.size != 3) throw ParseException("data row must have 3 columns: calBP c14BP sigma")
                    val calBP = parts[0].toInt()
                    val c14 = parts[1].toDouble()
                    val sigma = parts[2].toDouble()
                    points += CurvePoint(calBP, c14, sigma)
                }
            } catch (e: NumberFormatException) {
                throw ParseException("line $lineNo: numeric parse failure ('${e.message}') in: $line")
            } catch (e: IllegalArgumentException) {
                throw ParseException("line $lineNo: ${e.message} in: $line")
            }
        }
        val required = listOf("curve_id", "version", "published_label")
        val missing = required.filter { it !in meta }
        if (missing.isNotEmpty()) throw ParseException("missing required header(s): ${missing.joinToString(", ")}")
        if (points.isEmpty()) throw ParseException("curve contains no data points")
        val sorted = points.sortedBy { it.calBP }
        sorted.zipWithNext().forEach { (a, b) ->
            if (a.calBP == b.calBP) throw ParseException("duplicate calBP value ${a.calBP}")
        }
        return CalibrationCurve(
            curveId = meta.getValue("curve_id"),
            version = meta.getValue("version"),
            publishedLabel = meta.getValue("published_label"),
            points = sorted,
            sourceRef = meta["source_ref"] ?: sourceRef
        )
    }

    fun render(curve: CalibrationCurve): String = buildString {
        appendLine("# radiocarbon calibration curve (offline workbench format)")
        appendLine("# data columns: calBP  c14BP(14C years BP)  sigma(14C years)")
        appendLine("curve_id: ${curve.curveId}")
        appendLine("version: ${curve.version}")
        appendLine("published_label: ${curve.publishedLabel}")
        appendLine("source_ref: ${curve.sourceRef}")
        appendLine("calBP c14BP sigma")
        curve.points.forEach { appendLine("${it.calBP} ${f(it.c14Age)} ${f(it.sigma)}") }
    }

    private fun f(d: Double): String =
        if (d == d.toLong().toDouble()) d.toLong().toString() else "%.4f".format(d).trimEnd('0').trimEnd('.')
}
