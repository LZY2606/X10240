package gsb

import kotlin.math.max

private fun esc(s: String): String =
    s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

private val COLORS = listOf("#1f6feb", "#d1242f", "#1a7f37", "#8250df", "#bf8700", "#0e7490")

private data class Scale(val x0: Double, val y0: Double, val w: Double, val h: Double,
                         val xMin: Double, val xMax: Double, val yMax: Double) {
    fun x(v: Double) = x0 + (v - xMin) / (xMax - xMin) * w
    fun y(v: Double) = y0 + h - v / yMax * h
}

private fun axes(sb: StringBuilder, s: Scale, ticks: Int, presentYear: Int = 1950) {
    sb.append("<rect x='${s.x0}' y='${s.y0}' width='${s.w}' height='${s.h}' fill='white' stroke='#888'/>")
    for (i in 0..ticks) {
        val v = s.xMin + (s.xMax - s.xMin) * i / ticks
        val px = s.x(v)
        sb.append("<line x1='$px' y1='${s.y0 + s.h}' x2='$px' y2='${s.y0 + s.h + 4}' stroke='#444'/>")
        sb.append("<text x='$px' y='${s.y0 + s.h + 18}' font-size='10' text-anchor='middle' fill='#333'>${esc(Calibration.eraLabel(v, presentYear))}</text>")
        val bp = v.toInt()
        sb.append("<text x='$px' y='${s.y0 + s.h + 30}' font-size='9' text-anchor='middle' fill='#777'>${bp} calBP</text>")
    }
}

/** Calibration curve(s). x = cal BP, y = radiocarbon age. */
fun curveSvg(curves: List<Pair<CurveData, String>>, width: Int = 900, height: Int = 360): String {
    require(curves.isNotEmpty())
    val all = curves.flatMap { it.first.points }
    val xMin = all.minOf { it.calBp }
    val xMax = all.maxOf { it.calBp }
    val yMax = all.maxOf { it.c14Age }
    val s = Scale(60.0, 30.0, width - 120.0, height - 80.0, xMin, xMax, yMax * 1.05)
    val sb = StringBuilder()
    sb.append("<svg width='$width' height='$height' xmlns='http://www.w3.org/2000/svg' font-family='sans-serif'>")
    axes(sb, s, 6)
    sb.append("<text x='15' y='${s.y0 + s.h / 2}' font-size='11' transform='rotate(-90 15,${s.y0 + s.h / 2})' text-anchor='middle'>14C yr BP</text>")
    curves.forEachIndexed { idx, (curve, label) ->
        val color = COLORS[idx % COLORS.size]
        val path = curve.points.joinToString(" ") { "${s.x(it.calBp)},${s.y(it.c14Age)}" }
        sb.append("<polyline fill='none' stroke='$color' stroke-width='1.6' points='$path'/>")
        val ly = s.y0 + 8 + idx * 16
        sb.append("<rect x='${s.x0 + s.w - 150}' y='$ly' width='12' height='12' fill='$color'/>")
        sb.append("<text x='${s.x0 + s.w - 134}' y='${ly + 10}' font-size='11' fill='#333'>${esc(label)}</text>")
    }
    sb.append("</svg>")
    return sb.toString()
}

/** Probability densities (possibly several overlaid, e.g. offset assumptions),
 *  with peaks and 68.2% / 95.4% interval bars. */
fun densitySvg(results: List<CalibrationResult>, width: Int = 900, height: Int = 400): String {
    require(results.isNotEmpty())
    val xMin = results.minOf { it.gridFrom }
    val xMax = results.maxOf { it.gridTo }
    val yMax = results.maxOf { r -> r.points.maxOf { it.density } }
    val s = Scale(60.0, 24.0, width - 120.0, height - 110.0, xMin, xMax, yMax * 1.08)
    val sb = StringBuilder()
    sb.append("<svg width='$width' height='$height' xmlns='http://www.w3.org/2000/svg' font-family='sans-serif'>")
    axes(sb, s, 6, results.first().let {
        // present year recovered from era labels is constant 1950 in this pack
        1950
    })
    sb.append("<text x='15' y='${s.y0 + s.h / 2}' font-size='11' transform='rotate(-90 15,${s.y0 + s.h / 2})' text-anchor='middle'>probability density</text>")
    results.forEachIndexed { idx, r ->
        val color = COLORS[idx % COLORS.size]
        val pts = r.points
        val area = StringBuilder()
        area.append("M${s.x(pts.first().calBp)},${s.y0 + s.h} ")
        pts.forEach { area.append("L${s.x(it.calBp)},${s.y(it.density)} ") }
        area.append("L${s.x(pts.last().calBp)},${s.y0 + s.h} Z")
        sb.append("<path d='$area' fill='$color' fill-opacity='0.18' stroke='$color' stroke-width='1.4'/>")
        r.peaks.forEach { p ->
            sb.append("<circle cx='${s.x(p.calBp)}' cy='${s.y(p.density)}' r='3.5' fill='$color'/>")
        }
        // interval bars under the curve: 95.4 (outer), 68.2 (inner)
        val baseY = s.y0 + s.h + 42 + idx * 14
        r.intervals954.forEach { iv ->
            sb.append("<line x1='${s.x(iv.fromCalBp)}' y1='$baseY' x2='${s.x(iv.toCalBp)}' y2='$baseY' stroke='$color' stroke-width='8' stroke-opacity='0.25'/>")
        }
        r.intervals682.forEach { iv ->
            sb.append("<line x1='${s.x(iv.fromCalBp)}' y1='$baseY' x2='${s.x(iv.toCalBp)}' y2='$baseY' stroke='$color' stroke-width='8' stroke-opacity='0.6'/>")
        }
        val ly = s.y0 + 6 + idx * 16
        sb.append("<rect x='${s.x0 + s.w - 150}' y='$ly' width='12' height='12' fill='$color'/>")
        sb.append("<text x='${s.x0 + s.w - 134}' y='${ly + 10}' font-size='11' fill='#333'>${esc(r.labCode + (r.offsetLabel?.let { " | " + it } ?: " | no offset"))}</text>")
    }
    val barY = s.y0 + s.h + 42 + results.size * 14 + 2
    sb.append("<text x='${s.x0}' y='$barY' font-size='10' fill='#555'>bars: inner = 68.2% HDR interval(s), outer = 95.4%; multiple disjoint bars =并列 calendar ranges</text>")
    sb.append("</svg>")
    return sb.toString()
}

/** Simple sequence diagram: two boxes per constraint with an arrow, coloured by status. */
fun sequenceSvg(checks: List<SequenceCheckResult>, labs: Map<String, String>, width: Int = 900): String {
    val rowH = 72
    val height = max(100, 40 + checks.size * rowH)
    val sb = StringBuilder()
    sb.append("<svg width='$width' height='$height' xmlns='http://www.w3.org/2000/svg' font-family='sans-serif'>")
    checks.forEachIndexed { i, c ->
        val y = 20 + i * rowH
        val color = if (c.conflict) "#d1242f" else "#1a7f37"
        val b1 = esc(labs[c.beforeSourceId] ?: c.beforeSourceId)
        val b2 = esc(labs[c.afterSourceId] ?: c.afterSourceId)
        sb.append("<rect x='80' y='$y' width='220' height='40' rx='6' fill='#f6f8fa' stroke='$color' stroke-width='2'/>")
        sb.append("<text x='190' y='${y + 25}' font-size='13' text-anchor='middle' fill='#222'>$b1 (deeper/older)</text>")
        sb.append("<line x1='310' y1='${y + 20}' x2='580' y2='${y + 20}' stroke='$color' stroke-width='2' marker-end='url(#arrow$i)'/>")
        sb.append("<defs><marker id='arrow$i' markerWidth='8' markerHeight='8' refX='6' refY='3' orient='auto'><path d='M0,0 L0,6 L7,3 z' fill='$color'/></marker></defs>")
        sb.append("<text x='445' y='${y + 12}' font-size='11' text-anchor='middle' fill='$color'>P(older order)=${"%.3f".format(c.pOrder)} · ${c.status}</text>")
        sb.append("<rect x='590' y='$y' width='220' height='40' rx='6' fill='#f6f8fa' stroke='#444' stroke-width='1.5'/>")
        sb.append("<text x='700' y='${y + 25}' font-size='13' text-anchor='middle' fill='#222'>$b2 (shallower/younger)</text>")
    }
    sb.append("</svg>")
    return sb.toString()
}
