package rgsb.web

import rgsb.domain.CalendarConventions
import rgsb.model.CalibrationCurve
import rgsb.model.CalibrationResult
import rgsb.model.SequenceVerdict
import kotlin.math.max

/**
 * Server-side SVG rendering. Every view is SVG with stable element ids so a reviewer can
 * expand any interval probability and read the integral evidence back from the archive.
 */
object SvgCharts {
    private const val W = 920
    private const val H = 330
    private const val ML = 66.0
    private const val MR = 28.0
    private const val MT = 34.0
    private const val MB = 48.0

    private data class Scale(val x0: Double, val x1: Double, val y0: Double, val y1: Double) {
        fun x(v: Double) = ML + (v - x0) / (x1 - x0) * (W - ML - MR)
        fun y(v: Double) = H - MB - (v - y0) / (y1 - y0) * (H - MT - MB)
    }

    fun calibrationCurve(curves: List<CalibrationCurve>, highlight: IntRange? = null): String {
        val xs = curves.flatMap { listOf(it.points.first().calYear.toDouble(), it.points.last().calYear.toDouble()) }
        val ys = curves.flatMap { c -> c.points.flatMap { listOf(it.carbonAge - it.uncertainty, it.carbonAge + it.uncertainty) } }
        val sc = Scale(xs.min(), xs.max(), max(0.0, ys.min()), ys.max())
        val colors = listOf("#2b6cb0", "#c05621", "#2f855a", "#6b46c1")
        return buildSvg("curve-chart") {
            text(ML, 20.0, "Calibration curve — calendar year (astronomical) vs 14C age BP", 13.0, "#1a202c", "bold")
            axes(sc, xs.min().toInt(), xs.max().toInt(), ys.min(), ys.max())
            highlight?.let { r ->
                rect(sc.x(r.first.toDouble()), MT, sc.x(r.last.toDouble()) - sc.x(r.first.toDouble()),
                    H - MT - MB, "#fff5b7", 0.55)
            }
            curves.forEachIndexed { i, c ->
                val col = colors[i % colors.size]
                val top = c.points.joinToString(" ") { "${sc.x(it.calYear.toDouble())},${sc.y(it.carbonAge + it.uncertainty)}" }
                val bottom = c.points.asReversed().joinToString(" ") { "${sc.x(it.calYear.toDouble())},${sc.y(it.carbonAge - it.uncertainty)}" }
                polygon("$top $bottom", col, 0.10)
                polyline(c.points.joinToString(" ") { "${sc.x(it.calYear.toDouble())},${sc.y(it.carbonAge)}" }, col, 2.0)
            }
            legend(curves.mapIndexed { i, c -> "${c.name} ${c.version}" to colors[i % colors.size] })
        }
    }

    fun posterior(result: CalibrationResult): String {
        if (result.grid.isEmpty()) {
            return buildSvg("posterior-${result.resultId}") {
                text(ML, H / 2.0, "No posterior — status ${result.status}: ${result.unknownReason ?: ""}", 13.0, "#c53030", "bold")
            }
        }
        val years = result.grid.map { it.year }
        val maxW = result.grid.maxOf { it.weight }
        val sc = Scale(years.min().toDouble(), years.max().toDouble(), 0.0, maxW * 1.15)
        return buildSvg("posterior-${result.resultId}") {
            text(ML, 20.0, "Calibrated posterior · ${result.measurementId} · ${result.curveId}@${result.curveVersion} · modes=${result.modeCount}", 12.5, "#1a202c", "bold")
            axes(sc, years.min(), years.max(), 0.0, maxW)
            if (result.crossesBceCe) {
                line(sc.x(0.0), MT, sc.x(0.0), H - MB, "#d53f8c", 1.5, "4 3")
                text(sc.x(0.0) + 4, MT + 12, "BCE/CE boundary", 10.0, "#d53f8c", "bold")
            }
            val bw = max(1.0, (W - ML - MR) / result.grid.size - 0.4)
            for (g in result.grid) {
                rect(sc.x(g.year.toDouble()) - bw / 2, sc.y(g.weight), bw,
                    sc.y(0.0) - sc.y(g.weight), "#2b6cb0", 0.72)
            }
            for (level in listOf(0.95, 0.68)) {
                val col = if (level == 0.68) "#2f855a" else "#dd6b20"
                result.intervals.filter { it.hpdLevel == level }.forEach { iv ->
                    bracket(sc, iv.yearFrom.toDouble(), iv.yearTo.toDouble(), col,
                        "HPD ${(level * 100).toInt()}%: ${"%.1f".format(iv.probability * 100)}%")
                }
            }
            text(ML, H - 8.0, "calendar year (negative = BCE); full multi-modal distribution kept — never collapsed to a mean year", 10.5, "#4a5568")
        }
    }

    fun sequence(verdicts: List<SequenceVerdict>): String {
        val height = 70 + verdicts.size * 62
        val sb = StringBuilder(svgOpen("sequence-chart", W, height))
        sb.append(textXml(ML, 28.0, "Stratigraphic order check (conflicts propose candidate adjustments; measurements stay unchanged)", 13.5, "#1a202c", "bold"))
        verdicts.forEachIndexed { i, v ->
            val y = 62.0 + i * 62
            val col = if (v.satisfied) "#2f855a" else "#c53030"
            sb.append(rectXml(ML, y - 18, 14.0, 14.0, col, 1.0, col))
            sb.append(textXml(ML + 24, y - 6,
                "${v.link.before} older-than ${v.link.after} — " + if (v.satisfied) "satisfied" else "CONFLICT",
                12.5, col, "bold"))
            sb.append(textXml(ML + 24, y + 12, v.evidence, 10.5, "#4a5568"))
            if (!v.satisfied) {
                sb.append(textXml(ML + 24, y + 28,
                    "${v.candidateAdjustments.size} legal candidate adjustment(s) retained for review", 10.5, "#9c4221"))
            }
        }
        sb.append("</svg>")
        return sb.toString()
    }

    fun offsetHypotheses(results: List<CalibrationResult>): String {
        val nonEmpty = results.filter { it.grid.isNotEmpty() }
        if (nonEmpty.isEmpty()) {
            return buildSvg("offset-compare") { text(ML, H / 2.0, "No posteriors to compare", 13.0, "#4a5568") }
        }
        val y0 = nonEmpty.minOf { it.grid.first().year }.toDouble()
        val y1 = nonEmpty.maxOf { it.grid.last().year }.toDouble()
        val maxW = nonEmpty.maxOf { r -> r.grid.maxOf { it.weight } }
        val sc = Scale(y0, y1, 0.0, maxW * 1.15)
        val colors = listOf("#2b6cb0", "#c05621", "#2f855a", "#6b46c1", "#b7791f")
        return buildSvg("offset-compare") {
            text(ML, 20.0, "Reservoir offset hypotheses overlaid", 13.0, "#1a202c", "bold")
            axes(sc, y0.toInt(), y1.toInt(), 0.0, maxW)
            nonEmpty.forEachIndexed { i, r ->
                val col = colors[i % colors.size]
                polyline(r.grid.joinToString(" ") { "${sc.x(it.year.toDouble())},${sc.y(it.weight)}" }, col, 1.8, 0.9)
            }
            legend(nonEmpty.mapIndexed { i, r ->
                "${r.offsetId?.let { "offset=$it" } ?: "no offset"} · ${r.curveId}@${r.curveVersion} · modes=${r.modeCount}" to colors[i % colors.size]
            })
        }
    }

    private class Ctx(val sb: StringBuilder) {
        fun text(x: Double, y: Double, t: String, size: Double = 11.0, color: String = "#000", weight: String = "normal") =
            sb.append(textXml(x, y, t, size, color, weight))
        fun raw(xml: String) = sb.append(xml)
        fun line(x1: Double, y1: Double, x2: Double, y2: Double, color: String, width: Double = 1.0, dash: String? = null) {
            sb.append("""<line x1="$x1" y1="$y1" x2="$x2" y2="$y2" stroke="$color" stroke-width="$width" ${dash?.let { "stroke-dasharray=\"$it\"" } ?: ""}/>""")
        }
        fun rect(x: Double, y: Double, w: Double, hh: Double, fill: String, opacity: Double = 1.0, stroke: String = "none") =
            sb.append(rectXml(x, y, w, hh, fill, opacity, stroke))
        fun polyline(points: String, color: String, width: Double = 1.5, opacity: Double = 1.0) {
            sb.append("""<polyline points="$points" fill="none" stroke="$color" stroke-width="$width" opacity="$opacity"/>""")
        }
        fun polygon(points: String, fill: String, opacity: Double = 1.0) {
            sb.append("""<polygon points="$points" fill="$fill" fill-opacity="$opacity" stroke="none"/>""")
        }
        fun bracket(sc: Scale, from: Double, to: Double, color: String, label: String) {
            val y = MT + 6
            val x1 = sc.x(from); val x2 = sc.x(to)
            line(x1, y, x2, y, color, 2.0); line(x1, y - 4, x1, y + 4, color, 2.0); line(x2, y - 4, x2, y + 4, color, 2.0)
            text((x1 + x2) / 2 - 24, y - 6, label, 9.5, color, "bold")
        }
        fun legend(items: List<Pair<String, String>>) {
            items.take(4).forEachIndexed { i, (label, color) ->
                val y = MT + 16 + i * 15
                rect(W - MR - 210.0, y - 10, 11.0, 11.0, color)
                text(W - MR - 194.0, y, label.take(30), 9.5, "#333")
            }
        }
    }

    private fun buildSvg(id: String, h: Int = H, block: Ctx.() -> Unit): String {
        val sb = StringBuilder(svgOpen(id, W, h))
        Ctx(sb).block()
        sb.append("</svg>")
        return sb.toString()
    }

    private fun svgOpen(id: String, w: Int, h: Int) =
        """<svg id="${escape(id)}" xmlns="http://www.w3.org/2000/svg" width="$w" height="$h" viewBox="0 0 $w $h" font-family="Helvetica,Arial,sans-serif">"""

    private fun Ctx.axes(sc: Scale, xmin: Int, xmax: Int, ymin: Double, ymax: Double) {
        line(ML, H - MB, W - MR, H - MB, "#333")
        line(ML, MT, ML, H - MB, "#333")
        for (i in 0..6) {
            val v = xmin + (xmax - xmin) * i / 6
            val px = sc.x(v.toDouble())
            line(px, H - MB, px, H - MB + 4, "#333")
            text(px - 24, H - MB + 18, CalendarConventions.tick(v), 9.5, "#333")
        }
        for (i in 0..4) {
            val v = ymin + (ymax - ymin) * i / 4
            val py = sc.y(v)
            line(ML - 4, py, ML, py, "#333")
            text(8.0, py + 3, "%.0f".format(v), 9.5, "#333")
        }
    }

    private fun textXml(x: Double, y: Double, t: String, size: Double, color: String, weight: String) =
        """<text x="$x" y="$y" font-size="$size" fill="$color" font-weight="$weight">${escape(t)}</text>"""

    private fun rectXml(x: Double, y: Double, w: Double, hh: Double, fill: String, opacity: Double, stroke: String) =
        """<rect x="$x" y="$y" width="$w" height="$hh" fill="$fill" fill-opacity="$opacity" stroke="$stroke" stroke-width="0.6" rx="1"/>"""

    private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
