package org.chrono.web

import org.chrono.calib.CalibrationResult
import org.chrono.calib.CredibleInterval
import org.chrono.domain.*
import kotlin.math.max
import kotlin.math.min

/**
 * 纯 Kotlin 生成内联 SVG（无外部资源、离线）。
 * 每个图形元素都带稳定 id：值 -> measurement/interval/grid 坐标，可展开积分证据。
 */
object SvgCharts {
    private const val W = 760
    private const val H = 240
    private const val PAD_L = 52
    private const val PAD_R = 16
    private const val PAD_T = 18
    private const val PAD_B = 34

    private fun xScale(lo: Double, hi: Double) = { x: Double ->
        PAD_L + (x - lo) / (hi - lo) * (W - PAD_L - PAD_R)
    }
    private fun yScale(max: Double) = { y: Double ->
        H - PAD_B - (if (max == 0.0) 0.0 else y / max * (H - PAD_T - PAD_B))
    }

    fun calibrationCurve(curves: List<CalibrationCurve>, highlightYear: Double? = null): String {
        val lo = curves.minOf { it.minCal }.toDouble()
        val hi = curves.maxOf { it.maxCal }.toDouble()
        val xs = xScale(lo, hi)
        val muMin = curves.minOf { c -> c.points.minOf { it.c14Age } }
        val muMax = curves.maxOf { c -> c.points.maxOf { it.c14Age } }
        val ys = yScaleManual(muMin - 20, muMax + 20)
        val sb = StringBuilder()
        sb.append(svgOpen("curve"))
        axes(sb, xs, ys, lo, hi, muMin, muMax)
        val colors = listOf("#2563eb", "#dc2626")
        curves.forEachIndexed { i, c ->
            val d = c.points.joinToString(" ") { p -> "${xs(p.calYearAstro.toDouble()).f()},${ys(p.c14Age).f()}" }
            sb.append("<polyline id=\"curve-${c.curveId}-${c.version}\" fill=\"none\" stroke=\"${colors[i % colors.size]}\" stroke-width=\"2\" points=\"$d\"/>")
        }
        if (highlightYear != null) {
            sb.append("<line x1=\"${xs(highlightYear).f()}\" y1=\"$PAD_T\" x2=\"${xs(highlightYear).f()}\" y2=\"${H - PAD_B}\" stroke=\"#059669\" stroke-dasharray=\"4 3\"/>")
        }
        eraAxis(sb, xs, lo, hi)
        sb.append(legend(curves.mapIndexed { i, c -> (if (i == 0) "● " else "● ") + "${c.curveId}@${c.version}" }, colors))
        sb.append("</svg>")
        return sb.toString()
    }

    /** 多峰密度：95% HPD 并列区间分色，逐点 path，峰值标注；绝无平均年竖线。 */
    fun density(entry: ViewEntry): String {
        val r = entry.current ?: return "<svg id=\"density-${entry.measurement.measurementId}\"></svg>"
        val lo = r.grid.first().year
        val hi = r.grid.last().year
        val xs = xScale(lo, hi)
        val maxD = r.grid.maxOf { it.density }
        val ys = yScale(maxD)
        val sb = StringBuilder()
        sb.append(svgOpen("density-${entry.measurement.measurementId}"))
        axes(sb, xs, ys, lo, hi, 0.0, maxD)
        // interval bands
        val palette = listOf("#dbeafe", "#fde68a", "#dcfce7", "#fce7f3")
        r.intervals95.forEachIndexed { idx, iv ->
            val x1 = xs(iv.fromAstro); val x2 = xs(iv.toAstro)
            sb.append("<rect id=\"ivband-${r.measurementId}-$idx\" data-from=\"${iv.fromAstro.f()}\" data-to=\"${iv.toAstro.f()}\" " +
                "x=\"${x1.f()}\" y=\"$PAD_T\" width=\"${(x2 - x1).f()}\" height=\"${H - PAD_T - PAD_B}\" fill=\"${palette[idx % palette.size]}\" opacity=\"0.65\"/>")
        }
        val pts = r.grid.joinToString(" ") { "${xs(it.year).f()},${ys(it.density).f()}" }
        sb.append("<polyline fill=\"none\" stroke=\"#7c3aed\" stroke-width=\"2\" points=\"$pts\"/>")
        r.peaks.forEachIndexed { i, p ->
            sb.append("<circle id=\"peak-${r.measurementId}-$i\" cx=\"${xs(p.yearAstro).f()}\" cy=\"${ys(p.density).f()}\" r=\"3.5\" fill=\"#7c3aed\"/>")
            sb.append("<text x=\"${(xs(p.yearAstro)+4).f()}\" y=\"${(ys(p.density)-2).f()}\" font-size=\"10\" fill=\"#4c1d95\">${EraScale.label(p.yearAstro)}</text>")
        }
        eraAxis(sb, xs, lo, hi)
        sb.append("</svg>")
        return sb.toString()
    }

    /** 不同储库偏移假设对比：同一测量在两套假设下的密度叠加。 */
    fun reservoirCompare(primary: CalibrationResult, alternatives: List<Pair<String, CalibrationResult>>): String {
        val all = listOf(primary) + alternatives.map { it.second }
        val lo = all.minOf { it.grid.first().year }; val hi = all.maxOf { it.grid.last().year }
        val xs = xScale(lo, hi); val maxD = all.maxOf { it.grid.maxOf { g -> g.density } }; val ys = yScale(maxD)
        val sb = StringBuilder(); sb.append(svgOpen("reservoir-compare"))
        axes(sb, xs, ys, lo, hi, 0.0, maxD)
        val cols = listOf("#7c3aed", "#ea580c", "#0891b2")
        fun line(r: CalibrationResult, col: String, width: Int) {
            val pts = r.grid.joinToString(" ") { "${xs(it.year).f()},${ys(it.density).f()}" }
            sb.append("<polyline fill=\"none\" stroke=\"$col\" stroke-width=\"$width\" points=\"$pts\"/>")
        }
        line(primary, cols[0], 2)
        alternatives.forEachIndexed { i, (_, r) -> line(r, cols[(i + 1) % cols.size], 1) }
        eraAxis(sb, xs, lo, hi)
        sb.append(legend(listOf("current") + alternatives.map { it.first }, cols))
        sb.append("</svg>")
        return sb.toString()
    }

    /** 层序关系图：节点按后验中位数定位，状态着色；矛盾边红色虚线。 */
    fun sequence(view: ProjectView): String {
        val sb = StringBuilder()
        val w = 760; val h = 90 + view.state.edges.size * 46
        sb.append("<svg id=\"sequence-graph\" width=\"$w\" height=\"$h\" xmlns=\"http://www.w3.org/2000/svg\">")
        sb.append("<rect width=\"$w\" height=\"$h\" fill=\"#ffffff\"/>")
        val byId = view.entries.associateBy { it.measurement.measurementId }
        val positions = HashMap<String, Pair<Int, Int>>()
        view.entries.forEachIndexed { i, e ->
            val x = 70 + i * 165
            positions[e.measurement.measurementId] = x to 40
            val status = if (view.state.exclusions[e.measurement.measurementId]?.exclude == true) "#9ca3af" else "#111827"
            sb.append("<circle id=\"seq-node-${e.measurement.measurementId}\" cx=\"$x\" cy=\"40\" r=\"7\" fill=\"$status\"/>")
            sb.append("<text x=\"$x\" y=\"24\" text-anchor=\"middle\" font-size=\"11\" fill=\"$status\">${e.measurement.measurementId}</text>")
        }
        view.assessments.forEachIndexed { i, a ->
            val y = 100 + i * 46
            val p1 = positions[a.olderId] ?: 70 to 40
            val p2 = positions[a.youngerId] ?: 200 to 40
            val col = when (a.status.name) {
                "CONSISTENT" -> "#059669"
                "AMBIGUOUS" -> "#d97706"
                "CONTRADICTION" -> "#dc2626"
                else -> "#6b7280"
            }
            sb.append("<line x1=\"${p1.first}\" y1=\"48\" x2=\"${p2.first}\" y2=\"48\" stroke=\"$col\" stroke-width=\"2\" stroke-dasharray=\"5 3\" marker-end=\"url(#arrow)\" opacity=\"0.35\"/>")
            val label = "${a.edgeId}: older=${a.olderId} → younger=${a.youngerId} | P=${a.orderingProbability?.let { "%.3f".format(it) } ?: "?"} | ${a.status}"
            sb.append("<line x1=\"30\" y1=\"$y\" x2=\"730\" y2=\"$y\" stroke=\"$col\" stroke-width=\"1.5\"/>")
            sb.append("<text id=\"seq-edge-${a.edgeId}\" x=\"36\" y=\"${y - 6}\" font-size=\"11\" fill=\"$col\">$label</text>")
            a.note?.let { sb.append("<text x=\"36\" y=\"${y + 12}\" font-size=\"10\" fill=\"#6b7280\">$it</text>") }
        }
        sb.append("<defs><marker id=\"arrow\" markerWidth=\"8\" markerHeight=\"8\" refX=\"6\" refY=\"3\" orient=\"auto\"><path d=\"M0,0 L6,3 L0,6 Z\" fill=\"#374151\"/></marker></defs>")
        sb.append("</svg>")
        return sb.toString()
    }

    private fun yScaleManual(lo: Double, hi: Double): (Double) -> Double = { y ->
        H - PAD_B - (y - lo) / (hi - lo) * (H - PAD_T - PAD_B)
    }

    private fun svgOpen(id: String) =
        "<svg id=\"$id\" width=\"$W\" height=\"$H\" xmlns=\"http://www.w3.org/2000/svg\"><rect width=\"$W\" height=\"$H\" fill=\"#fcfcfd\"/>"

    private fun axes(sb: StringBuilder, xs: (Double) -> Double, ys: (Double) -> Double,
                     lo: Double, hi: Double, ylo: Double, yhi: Double) {
        sb.append("<line x1=\"$PAD_L\" y1=\"${H - PAD_B}\" x2=\"${W - PAD_R}\" y2=\"${H - PAD_B}\" stroke=\"#9ca3af\"/>")
        sb.append("<line x1=\"$PAD_L\" y1=\"$PAD_T\" x2=\"$PAD_L\" y2=\"${H - PAD_B}\" stroke=\"#9ca3af\"/>")
        sb.append("<text x=\"${(PAD_L - 8)}\" y=\"$PAD_T\" font-size=\"10\" fill=\"#6b7280\">${if (yhi > 100) "14C BP" else "density"}</text>")
    }

    private fun eraAxis(sb: StringBuilder, xs: (Double) -> Double, lo: Double, hi: Double) {
        val ticks = sortedSetOf(lo.toInt(), 0, 1, hi.toInt())
        for (t in ticks) {
            if (t < lo || t > hi) continue
            val x = xs(t.toDouble())
            sb.append("<line x1=\"${x.f()}\" y1=\"${H - PAD_B}\" x2=\"${x.f()}\" y2=\"${H - PAD_B + 4}\" stroke=\"#9ca3af\"/>")
            sb.append("<text x=\"${x.f()}\" y=\"${H - PAD_B + 16}\" text-anchor=\"middle\" font-size=\"10\" fill=\"#6b7280\">${EraScale.label(t.toDouble())}</text>")
        }
        sb.append("<text x=\"${W - PAD_R}\" y=\"${H - 8}\" text-anchor=\"end\" font-size=\"10\" fill=\"#6b7280\">calendar years (astronomical; negative=BCE)</text>")
    }

    private fun legend(labels: List<String>, colors: List<String>): String {
        val sb = StringBuilder()
        labels.forEachIndexed { i, l ->
            val x = PAD_L + i * 210
            sb.append("<rect x=\"$x\" y=\"4\" width=\"10\" height=\"10\" fill=\"${colors[i % colors.size]}\"/>")
            sb.append("<text x=\"${x + 14}\" y=\"13\" font-size=\"10\" fill=\"#374151\">$l</text>")
        }
        return sb.toString()
    }

    private fun Double.f(): String = Math.round(this * 100.0).toDouble() / 100.0
}
