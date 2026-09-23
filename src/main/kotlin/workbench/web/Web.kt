package workbench.web

import workbench.*

fun esc(s: String): String = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

fun page(title: String, body: String): String = """<!DOCTYPE html>
<html lang="zh"><head><meta charset="utf-8"><title>${esc(title)} · 碳年候选席</title>
<style>
body{font-family:-apple-system,"Helvetica Neue",Arial,sans-serif;margin:24px;color:#222;max-width:1180px}
h1{font-size:20px} h2{font-size:16px;margin-top:28px;border-bottom:1px solid #ccc;padding-bottom:4px}
table{border-collapse:collapse;margin:8px 0} td,th{border:1px solid #bbb;padding:3px 8px;font-size:13px;text-align:left}
code,.id{font-family:ui-monospace,Menlo,monospace;font-size:12px;background:#f2f2f2;padding:1px 4px;border-radius:3px}
.badge{display:inline-block;padding:1px 7px;border-radius:8px;font-size:11px;color:#fff}
.current{background:#2a7d32}.stale{background:#b26a00}.conflict{background:#b3261e}.accepted{background:#2a7d32}.rejected{background:#777}
.card{border:1px solid #ccc;border-radius:6px;padding:10px 14px;margin:8px 0}
a{color:#0b57a4} details{margin:4px 0} summary{cursor:pointer;color:#0b57a4}
form.inline{display:inline} input,select,textarea{font-size:13px;padding:2px 4px;margin:2px}
button{font-size:13px;padding:3px 10px;cursor:pointer}
.muted{color:#666;font-size:12px}
</style></head><body>
<div><a href="/">总览</a> · <a href="/stratigraphy">层序关系</a> · <a href="/events">事件史</a> · <a href="/export">导出档案</a></div>
$body
</body></html>"""

fun fmtP(p: Double): String = "%.4f".format(p)

/** Probability density plot: multi-modal structure preserved, peaks marked,
 *  HPD intervals drawn as bars, era labels on the axis. Older calBP on the left. */
fun densitySvg(cal: CalibrationPayload, derivedId: String, width: Int = 1000, height: Int = 320): String {
    val marginL = 50; val marginB = 46; val marginT = 14
    val plotW = width - marginL - 16; val plotH = height - marginT - marginB
    val x0 = cal.gridStartBp; val x1 = cal.gridEndBp
    fun sx(bp: Double) = marginL + (x1 - bp).toDouble() / (x1 - x0) * plotW
    val maxD = cal.densities.max()
    fun sy(d: Double) = marginT + plotH - d / maxD * plotH
    val sb = StringBuilder()
    sb.append("""<svg width="$width" height="$height" xmlns="http://www.w3.org/2000/svg" style="border:1px solid #ccc;background:#fff">""")
    // density filled path
    val pts = StringBuilder()
    cal.densities.forEachIndexed { i, d ->
        val bp = x0 + i * cal.gridStep
        pts.append("${"%.1f".format(sx(bp.toDouble()))},${"%.1f".format(sy(d))} ")
    }
    sb.append("""<polygon points="${"%.1f".format(sx(x1.toDouble()))},${sy(0.0)} $pts${"%.1f".format(sx(x0.toDouble()))},${sy(0.0)}" fill="#9ec4e8" stroke="#1c5d99" stroke-width="1.2"/>""")
    // peaks
    for (p in cal.peaks) {
        sb.append("""<circle cx="${"%.1f".format(sx(p.calBp.toDouble()))}" cy="${"%.1f".format(sy(p.height))}" r="4" fill="#b3261e"><title>peak at ${p.calBp} calBP</title></circle>""")
    }
    // interval bars
    val barY1 = marginT + plotH + 12; val barY2 = marginT + plotH + 24
    for (iv in cal.intervals) {
        val y = if (iv.level == 0.682) barY1 else barY2
        val color = if (iv.level == 0.682) "#2a7d32" else "#b26a00"
        sb.append("""<rect id="$derivedId-${iv.id}" x="${"%.1f".format(sx(iv.toBp.toDouble()))}" y="$y" width="${"%.1f".format(kotlin.math.max(2.0, sx(iv.fromBp.toDouble()) - sx(iv.toBp.toDouble())))}" height="7" fill="$color"><title>${iv.id}: ${iv.fromBp}–${iv.toBp} calBP, P=${fmtP(iv.probability)}</title></rect>""")
    }
    // era boundary line at 1950 calBP
    if (1950 in x0..x1) {
        sb.append("""<line x1="${"%.1f".format(sx(1950.0))}" y1="$marginT" x2="${"%.1f".format(sx(1950.0))}" y2="${marginT + plotH}" stroke="#b3261e" stroke-dasharray="4 3"/>""")
        sb.append("""<text x="${"%.1f".format(sx(1950.0))}" y="${marginT + 10}" font-size="10" fill="#b3261e">BCE/CE</text>""")
    }
    // axis ticks with era labels
    val step = niceStep((x1 - x0) / 8)
    var t = (x0 / step) * step
    while (t <= x1) {
        sb.append("""<line x1="${"%.1f".format(sx(t.toDouble()))}" y1="${marginT + plotH}" x2="${"%.1f".format(sx(t.toDouble()))}" y2="${marginT + plotH + 4}" stroke="#333"/>""")
        sb.append("""<text x="${"%.1f".format(sx(t.toDouble()))}" y="${marginT + plotH + 40}" font-size="10" text-anchor="middle">${Era.label(t)}</text>""")
        sb.append("""<text x="${"%.1f".format(sx(t.toDouble()))}" y="${marginT + plotH + 14}" font-size="9" text-anchor="middle" fill="#666">$t</text>""")
        t += step
    }
    sb.append("""<text x="6" y="${marginT + 10}" font-size="10" fill="#333">P</text>""")
    sb.append("</svg>")
    return sb.toString()
}

fun niceStep(raw: Int): Int {
    val candidates = listOf(10, 25, 50, 100, 200, 250, 500, 1000)
    return candidates.firstOrNull { it >= raw } ?: ((raw / 1000) + 1) * 1000
}

/** Calibration curve plot with ±1σ band; multiple versions overlaid. */
fun curveSvg(curves: List<Pair<String, CurvePayload>>, width: Int = 1000, height: Int = 360): String {
    if (curves.isEmpty()) return "<p>no curve</p>"
    val marginL = 60; val marginB = 40; val marginT = 14
    val plotW = width - marginL - 16; val plotH = height - marginT - marginB
    val allPts = curves.flatMap { it.second.points }
    val x0 = allPts.minOf { it.calBp }; val x1 = allPts.maxOf { it.calBp }
    val y0 = allPts.minOf { it.c14Age - it.sigma }; val y1 = allPts.maxOf { it.c14Age + it.sigma }
    fun sx(bp: Double) = marginL + (x1 - bp) / (x1 - x0) * plotW
    fun sy(a: Double) = marginT + (y1 - a) / (y1 - y0) * plotH
    val colors = listOf("#1c5d99", "#b26a00", "#2a7d32")
    val sb = StringBuilder("""<svg width="$width" height="$height" xmlns="http://www.w3.org/2000/svg" style="border:1px solid #ccc;background:#fff">""")
    curves.forEachIndexed { ci, (label, curve) ->
        val color = colors[ci % colors.size]
        val pts = curve.points.sortedBy { it.calBp }
        val band = (pts.joinToString(" ") { "${"%.1f".format(sx(it.calBp.toDouble()))},${"%.1f".format(sy(it.c14Age + it.sigma))}" } +
            " " + pts.reversed().joinToString(" ") { "${"%.1f".format(sx(it.calBp.toDouble()))},${"%.1f".format(sy(it.c14Age - it.sigma))}" })
        sb.append("""<polygon points="$band" fill="$color" opacity="0.12"/>""")
        sb.append("""<polyline points="${pts.joinToString(" ") { "${"%.1f".format(sx(it.calBp.toDouble()))},${"%.1f".format(sy(it.c14Age))}" }}" fill="none" stroke="$color" stroke-width="1.6"/>""")
        sb.append("""<text x="${marginL + 10}" y="${marginT + 14 + ci * 14}" font-size="11" fill="$color">${esc(label)}</text>""")
    }
    if (1950 in x0..x1) sb.append("""<line x1="${"%.1f".format(sx(1950.0))}" y1="$marginT" x2="${"%.1f".format(sx(1950.0))}" y2="${marginT + plotH}" stroke="#b3261e" stroke-dasharray="4 3"/>""")
    val step = niceStep((x1 - x0) / 8)
    var t = (x0 / step) * step
    while (t <= x1) {
        sb.append("""<text x="${"%.1f".format(sx(t.toDouble()))}" y="${marginT + plotH + 16}" font-size="9" text-anchor="middle" fill="#666">$t</text>""")
        t += step
    }
    sb.append("""<text x="8" y="${marginT + 10}" font-size="10">14C age BP</text>""")
    sb.append("""<text x="${width / 2}" y="${height - 4}" font-size="10" text-anchor="middle">calBP (老 ← → 新)</text>""")
    sb.append("</svg>")
    return sb.toString()
}

/** Stratigraphy diagram: samples as boxes, constraints as arrows (older above younger). */
fun stratSvg(
    constraints: List<StratConstraint>,
    labels: Map<String, String>,
    conflictPairs: Set<Pair<String, String>>
): String {
    if (constraints.isEmpty()) return "<p class=\"muted\">无层序约束</p>"
    val width = 560; val rowH = 90
    val height = constraints.size * rowH + 60
    val sb = StringBuilder("""<svg width="$width" height="$height" xmlns="http://www.w3.org/2000/svg" style="border:1px solid #ccc;background:#fff">""")
    constraints.forEachIndexed { i, c ->
        val y1 = 30 + i * rowH; val y2 = y1 + 44
        val isConflict = (c.olderStreamId to c.youngerStreamId) in conflictPairs
        val color = if (isConflict) "#b3261e" else "#2a7d32"
        sb.append("""<rect x="60" y="$y1" width="180" height="26" rx="4" fill="#e8eef7" stroke="#1c5d99"/>""")
        sb.append("""<text x="150" y="${y1 + 17}" font-size="11" text-anchor="middle">${esc(labels[c.olderStreamId] ?: c.olderStreamId)} (老)</text>""")
        sb.append("""<rect x="60" y="$y2" width="180" height="26" rx="4" fill="#e8eef7" stroke="#1c5d99"/>""")
        sb.append("""<text x="150" y="${y2 + 17}" font-size="11" text-anchor="middle">${esc(labels[c.youngerStreamId] ?: c.youngerStreamId)} (新)</text>""")
        sb.append("""<line x1="150" y1="${y1 + 26}" x2="150" y2="$y2" stroke="$color" stroke-width="2" marker-end="url(#arr)"/>""")
        if (isConflict) sb.append("""<text x="255" y="${(y1 + y2) / 2 + 16}" font-size="11" fill="#b3261e">冲突: 校准区间与层序矛盾</text>""")
        sb.append("""<text x="255" y="${(y1 + y2) / 2 + 4}" font-size="10" fill="#666">${esc(c.note)}</text>""")
    }
    sb.append("""<defs><marker id="arr" markerWidth="8" markerHeight="8" refX="7" refY="3" orient="auto"><path d="M0,0 L7,3 L0,6 z" fill="#555"/></marker></defs>""")
    sb.append("</svg>")
    return sb.toString()
}
