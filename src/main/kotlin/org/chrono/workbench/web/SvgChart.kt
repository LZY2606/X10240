package org.chrono.workbench.web

import org.chrono.workbench.db.DerivedRecord
import org.chrono.workbench.domain.CalibrationResult
import org.chrono.workbench.domain.CalendarScales
import org.chrono.workbench.model.Curve
import org.chrono.workbench.model.Measurement
import org.chrono.workbench.model.SequenceConstraint
import kotlin.math.max

object SvgChart {
    private const val W = 920
    private const val H = 360
    private const val ML = 64
    private const val MR = 24
    private const val MT = 40
    private const val MB = 52

    private fun esc(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    fun calibrationCurveSvg(curve: Curve, highlight: List<DerivedRecord> = emptyList()): String {
        val xs = curve.points.map { it.calBP }
        val xMin = xs.min(); val xMax = xs.max()
        val yMin = curve.points.minOf { it.c14age } - 20
        val yMax = curve.points.maxOf { it.c14age } + 20
        fun px(x: Int) = ML + (xMax - x).toDouble() / (xMax - xMin) * (W - ML - MR)
        fun py(y: Double) = MT + (yMax - y) / (yMax - yMin) * (H - MT - MB)

        val path = curve.points.joinToString(" ") { p ->
            val lead = if (p == curve.points.first()) "M" else "L"
            "$lead${"%.1f".fmt(px(p.calBP))},${"%.1f".fmt(py(p.c14age))}"
        }
        val band = buildString {
            append("<path d=\"")
            append(curve.points.joinToString(" ") { p ->
                val lead = if (p == curve.points.first()) "M" else "L"
                "$lead${"%.1f".fmt(px(p.calBP))},${"%.1f".formatF(py(p.c14age + p.sigma))}"
            })
            append(curve.points.reversed().joinToString(" ") { p ->
                "L${"%.1f".fmt(px(p.calBP))},${"%.1f".formatF(py(p.c14age - p.sigma))}"
            })
            append(" Z\" fill=\"#c7d8f0\" fill-opacity=\"0.45\"/>")
        }
        val zero = 1950
        val ticks = (xMin..xMax step 50).map { x ->
            val lbl = CalendarScales.label(x).text
            "<g><line x1=\"${"%.1f".fmt(px(x))}\" y1=\"${H - MB}\" x2=\"${"%.1f".fmt(px(x))}\" y2=\"${H - MB + 5}\" stroke=\"#555\"/>" +
                "<text x=\"${"%.1f".fmt(px(x))}\" y=\"${H - MB + 18}\" font-size=\"11\" text-anchor=\"middle\" fill=\"#333\">$lbl</text></g>"
        }.joinToString("")
        val zeroLine =
            "<line x1=\"${"%.1f".fmt(px(zero))}\" y1=\"$MT\" x2=\"${"%.1f".fmt(px(zero))}\" y2=\"${H - MB}\" stroke=\"#b23b3b\" stroke-dasharray=\"4 4\"/>" +
                "<text x=\"${"%.1f".fmt(px(zero))}\" y=\"$MT\" font-size=\"11\" text-anchor=\"middle\" fill=\"#b23b3b\">BCE/CE</text>"
        val highlights = highlight.flatMap { rec ->
            listOf(rec.input.ageBP, rec.input.ageBP - rec.input.sigmaBP, rec.input.ageBP + rec.input.sigmaBP).mapIndexed { i, y ->
                val color = if (i == 0) "#0b6e4f" else "#9aa7b0"
                val dash = if (i == 0) "" else " stroke-dasharray=\"3 3\""
                "<line x1=\"$ML\" y1=\"${"%.1f".fmt(py(y))}\" x2=\"${W - MR}\" y2=\"${"%.1f".fmt(py(y))}\" stroke=\"$color\"$dash/>"
            }
        }.joinToString("")
        return wrap(
            "Calibration curve ${esc(curve.version)}",
            "$band<path d=\"$path\" fill=\"none\" stroke=\"#1f4e9c\" stroke-width=\"2\"/>$zeroLine$highlights$ticks" +
                axisLabels("calibrated calendar year (BCE/CE)", "14C age (BP)")
        )
    }

    fun posteriorSvg(record: DerivedRecord): String {
        val r = record.result
        val xs = r.gridCalBP
        val xMin = xs.min(); val xMax = xs.max()
        val yMax = max(r.density.maxOrNull() ?: 0.0, r.scenarios.flatMap { it.density }.maxOrNull() ?: 0.0)
        if (yMax <= 0.0) {
            return wrap(
                "Calibrated posterior ${esc(record.input.labCode)} — UNKNOWN (outside curve)",
                "<text x=\"$ML\" y=\"${H / 2}\" font-size=\"15\" fill=\"#b23b3b\">No calendar support on ${esc(record.input.curve.version)}; retained as UNKNOWN.</text>"
            )
        }
        fun px(x: Int) = ML + (xMax - x).toDouble() / (xMax - xMin) * (W - ML - MR)
        fun py(y: Double) = MT + (yMax - y) / yMax * (H - MT - MB)
        val area = "<path d=\"" +
            xs.indices.joinToString(" ") { i ->
                val lead = if (i == 0) "M" else "L"
                "$lead${"%.1f".fmt(px(xs[i]))},${"%.1f".fmt(py(r.density[i]))}"
            } + " L${"%.1f".fmt(px(xs.last()))},${H - MB} L${"%.1f".fmt(px(xs.first()))},${H - MB} Z\" fill=\"#1f4e9c\" fill-opacity=\"0.25\"/>" +
            "<path d=\"" + xs.indices.joinToString(" ") { i ->
                val lead = if (i == 0) "M" else "L"
                "$lead${"%.1f".fmt(px(xs[i]))},${"%.1f".fmt(py(r.density[i]))}"
            } + "\" fill=\"none\" stroke=\"#1f4e9c\" stroke-width=\"1.6\"/>"

        val ivs = r.intervals95.mapIndexed { i, iv ->
            val x1 = px(kotlin.math.ceil(iv.oldCalBP).toInt())
            val x2 = px(kotlin.math.floor(iv.youngCalBP).toInt())
            val tie = if (iv.tiedWith.isNotEmpty()) " [TIED]" else ""
            "<rect x=\"${"%.1f".fmt(kotlin.math.min(x1, x2))}\" y=\"$MT\" width=\"${"%.1f".fmt(kotlin.math.abs(x1 - x2).coerceAtLeast(2.0))}\" height=\"${H - MT - MB}\" fill=\"#0b6e4f\" fill-opacity=\"0.07\"/>" +
                "<text x=\"${"%.1f".fmt((x1 + x2) / 2)}\" y=\"${MT + 14 + (i % 2) * 13}\" font-size=\"10\" text-anchor=\"middle\" fill=\"#0b6e4f\">" +
                "${"%.1f".fmt(iv.probabilityMass * 100)}%$tie</text>"
        }.joinToString("")

        val zeroLine = if (xMin >= 1950 && xMax <= 1950) "" else
            "<line x1=\"${"%.1f".fmt(px(1950.coerceIn(xMin, xMax)))}\" y1=\"$MT\" x2=\"${"%.1f".fmt(px(1950.coerceIn(xMin, xMax)))}\" y2=\"${H - MB}\" stroke=\"#b23b3b\" stroke-dasharray=\"4 4\"/>"
        val stale = if (record.stale)
            "<text x=\"${W - MR}\" y=\"$MT\" font-size=\"12\" text-anchor=\"end\" fill=\"#b23b3b\">STALE: ${esc(record.staleReason ?: "")}</text>" else ""
        val title = "Calibrated posterior ${esc(record.input.labCode)} — ${r.modeCount} mode(s), ${r.intervals95.size} interval(s) [${esc(record.result.status)}]"
        return wrap(title, area + ivs + zeroLine + stale + axisTicks(xMin, xMax) + axisLabels("calibrated calendar year (BCE/CE)", "probability density"))
    }

    fun scenariosSvg(record: DerivedRecord): String {
        val r = record.result
        val colors = listOf("#1f4e9c", "#b07c0f", "#7a3fb0", "#0b6e4f")
        val xs = r.gridCalBP
        val xMin = xs.min(); val xMax = xs.max()
        val yMax = r.scenarios.flatMap { it.density }.maxOrNull()?.coerceAtLeast(1e-9) ?: 1e-9
        fun px(x: Int) = ML + (xMax - x).toDouble() / (xMax - xMin) * (W - ML - MR)
        fun py(y: Double) = MT + (yMax - y) / yMax * (H - MT - MB)
        val lines = r.scenarios.mapIndexed { i, s ->
            val c = colors[i % colors.size]
            val d = s.density.indices.joinToString(" ") { j ->
                val lead = if (j == 0) "M" else "L"
                "$lead${"%.1f".fmt(px(xs[j]))},${"%.1f".fmt(py(s.density[j]))}"
            }
            "<path d=\"$d\" fill=\"none\" stroke=\"$c\" stroke-width=\"1.6\"/>" +
                "<text x=\"${ML + 8}\" y=\"${MT + 16 + i * 15}\" font-size=\"11\" fill=\"$c\">${esc(s.label)} (w=${s.weight})</text>"
        }.joinToString("")
        return wrap("Reservoir scenarios ${esc(record.input.labCode)}", lines + axisTicks(xMin, xMax) +
            axisLabels("calibrated calendar year (BCE/CE)", "scenario probability density"))
    }

    fun sequenceSvg(measurements: List<Measurement>, records: Map<String, DerivedRecord>, constraints: List<SequenceConstraint>): String {
        val usable = measurements.filter { records[it.id] != null && records[it.id]!!.result.status != "OUTSIDE_CURVE_UNKNOWN" }
        val rows = usable.withIndex().joinToString("") { (i, m) ->
            val y = MT + 30 + i * 46
            val rec = records.getValue(m.id)
            val ivText = rec.result.intervals95.joinToString(" / ") { iv ->
                "${iv.oldEdgeLabel}–${iv.youngEdgeLabel} (${"%.1f".fmt(iv.probabilityMass * 100)}%)"
            }.ifEmpty { "—" }
            "<text x=\"$ML\" y=\"$y\" font-size=\"12\" fill=\"#222\">${esc(m.labCode)}</text>" +
                "<text x=\"${ML + 110}\" y=\"$y\" font-size=\"11\" fill=\"#555\">${esc(ivText)}</text>"
        }
        val rels = constraints.joinToString("") { c ->
            val ai = usable.indexOfFirst { it.id == c.olderMeasurementId }
            val bi = usable.indexOfFirst { it.id == c.youngerMeasurementId }
            if (ai < 0 || bi < 0) "" else {
                val y1 = MT + 30 + ai * 46 - 5
                val y2 = MT + 30 + bi * 46 - 5
                "<line x1=\"${ML + 700}\" y1=\"$y1\" x2=\"${ML + 700}\" y2=\"$y2\" stroke=\"#0b6e4f\" stroke-width=\"2\" marker-end=\"url(#arrow)\"/>"
            }
        }
        return wrap("Stratigraphic sequence (arrow: older-than)", rows +
            "<defs><marker id=\"arrow\" markerWidth=\"8\" markerHeight=\"8\" refX=\"6\" refY=\"3\" orient=\"auto\">" +
            "<path d=\"M0,0 L6,3 L0,6 Z\" fill=\"#0b6e4f\"/></marker></defs>$rels")
    }

    private fun axisTicks(xMin: Int, xMax: Int): String =
        (xMin..xMax step 50).map { x ->
            val px = ML + (xMax - x).toDouble() / (xMax - xMin) * (W - ML - MR)
            "<line x1=\"${"%.1f".fmt(px)}\" y1=\"${H - MB}\" x2=\"${"%.1f".fmt(px)}\" y2=\"${H - MB + 5}\" stroke=\"#555\"/>" +
                "<text x=\"${"%.1f".fmt(px)}\" y=\"${H - MB + 18}\" font-size=\"11\" text-anchor=\"middle\" fill=\"#333\">${CalendarScales.label(x).text}</text>"
        }.joinToString("")

    private fun axisLabels(xLabel: String, yLabel: String): String =
        "<text x=\"${(ML + W - MR) / 2}\" y=\"${H - 8}\" font-size=\"12\" text-anchor=\"middle\" fill=\"#222\">$xLabel</text>" +
            "<text transform=\"translate(16 ${(MT + H - MB) / 2}) rotate(-90)\" font-size=\"12\" text-anchor=\"middle\" fill=\"#222\">$yLabel</text>"

    private fun wrap(title: String, body: String): String =
        "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"$W\" height=\"$H\" viewBox=\"0 0 $W $H\" role=\"img\" aria-label=\"$title\">" +
            "<rect width=\"$W\" height=\"$H\" fill=\"white\"/>" +
            "<text x=\"$ML\" y=\"22\" font-size=\"15\" font-weight=\"bold\" fill=\"#111\">$title</text>$body</svg>"

    private fun String.fmt(v: Double): String = String.format(this, v)
    private fun String.formatF(v: Double): String = String.format("%.1f", v)
}
