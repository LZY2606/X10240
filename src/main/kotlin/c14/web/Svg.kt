package c14.web

import c14.domain.*
import kotlin.math.max
import kotlin.math.min

/** Deterministic server-side SVG rendering (no external chart libraries). */
object Svg {
    private const val W = 760
    private const val H = 260
    private const val PAD_L = 56
    private const val PAD_R = 16
    private const val PAD_T = 18
    private const val PAD_B = 34

    private fun esc(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    /** Calibration curve with the measurement's 1-sigma band and wiggle crossings. */
    fun curve(curve: CalibrationCurve, measurement: Measurement?): String {
        val interp = CurveInterpolation(curve)
        val xs = curve.knots.map { it.calBp }
        val ys = curve.knots.map { it.c14AgeBp }
        val xMin = xs.min().toDouble(); val xMax = xs.max().toDouble()
        var yMin = ys.min(); var yMax = ys.max()
        if (measurement != null) {
            yMin = min(yMin, measurement.ageBp - 3 * measurement.sigmaBp)
            yMax = max(yMax, measurement.ageBp + 3 * measurement.sigmaBp)
        }
        fun px(bp: Double) = PAD_L + (bp - xMin) / (xMax - xMin) * (W - PAD_L - PAD_R)
        fun py(v: Double) = PAD_T + (yMax - v) / (yMax - yMin) * (H - PAD_T - PAD_B)

        val path = curve.knots.joinToString(" ") { k ->
            "${if (k === curve.knots.first()) "M" else "L"}${"%.1f".format(px(k.calBp.toDouble()))},${"%.1f".format(py(k.c14AgeBp))}"
        }
        val knots = curve.knots.joinToString("") { k ->
            "<circle cx='${"%.1f".format(px(k.calBp.toDouble()))}' cy='${"%.1f".format(py(k.c14AgeBp))}' r='1.6' fill='#4a6fa5' data-id='knot:${curve.id}:${k.calBp}'/>"
        }
        var band = ""
        if (measurement != null) {
            val top = py(measurement.ageBp + measurement.sigmaBp)
            val bot = py(measurement.ageBp - measurement.sigmaBp)
            band = "<rect x='$PAD_L' y='${"%.1f".format(top)}' width='${W - PAD_L - PAD_R}' height='${"%.1f".format(bot - top)}' fill='#f2c14e' fill-opacity='0.25'/>" +
                "<line x1='$PAD_L' x2='${W - PAD_R}' y1='${"%.1f".format(py(measurement.ageBp))}' y2='${"%.1f".format(py(measurement.ageBp))}' stroke='#d98e04' stroke-dasharray='4 3'/>"
        }
        val ticks = ticks(xMin, xMax, 6)
        val tickMarks = ticks.joinToString("") { t ->
            val x = "%.1f".format(px(t))
            "<line x1='$x' x2='$x' y1='${H - PAD_B}' y2='${H - PAD_B + 5}' stroke='#888'/>" +
                "<text x='$x' y='${H - PAD_B + 18}' font-size='10' text-anchor='middle' fill='#555'>${Era.label(t.toInt())}</text>"
        }
        return """
        <svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 $W $H' font-family='sans-serif'>
          <text x='$PAD_L' y='12' font-size='12' fill='#222'>${esc(curve.name)} ${esc(curve.version)} <tspan fill='#777' font-size='10'>(${esc(curve.ageUnit)} vs ${esc(curve.calAxis)})</tspan></text>
          $band
          <path d='$path' fill='none' stroke='#2b4f7a' stroke-width='1.8'/>
          $knots
          $tickMarks
          <text x='${(W / 2)}' y='${H - 4}' font-size='10' text-anchor='middle' fill='#555'>cal BP axis (older → younger is left → right)</text>
        </svg>
        """.trimIndent()
    }

    /** Posterior density with 68% (dark) and 95% (light) HPD intervals. */
    fun density(result: CalibrationResult, measurement: Measurement?): String {
        val cells = result.cells
        val xMin = cells.first().calBp.toDouble(); val xMax = cells.last().calBp.toDouble()
        val yMax = cells.maxOf { it.density }
        fun px(bp: Double) = PAD_L + (bp - xMin) / (xMax - xMin) * (W - PAD_L - PAD_R)
        fun py(v: Double) = PAD_T + (yMax - v) / yMax * (H - PAD_T - PAD_B)

        fun band(ivls: List<Interval>, color: String, opacity: Double): String = ivls.joinToString("") { iv ->
            val x = px(iv.fromBp.toDouble())
            val w = px(iv.toBp.toDouble()) - x
            val stale = ""
            "<rect x='${"%.1f".format(x)}' y='$PAD_T' width='${"%.1f".format(w)}' height='${H - PAD_T - PAD_B}' fill='$color' fill-opacity='$opacity' data-ivl='${iv.id}'>" +
                "<title>${esc(iv.id)}  P=${"%.3f".format(iv.probability)}  ${Era.label(iv.toBp)}…${Era.label(iv.fromBp)}</title></rect>$stale"
        }
        val path = cells.indices.joinToString(" ") { i ->
            val c = cells[i]
            "${if (i == 0) "M" else "L"}${"%.1f".format(px(c.calBp.toDouble()))},${"%.1f".format(py(c.density))}"
        }
        val ticks = ticks(xMin, xMax, 7)
        val tickMarks = ticks.joinToString("") { t ->
            val x = "%.1f".format(px(t))
            "<line x1='$x' x2='$x' y1='${H - PAD_B}' y2='${H - PAD_B + 5}' stroke='#888'/>" +
                "<text x='$x' y='${H - PAD_B + 18}' font-size='10' text-anchor='middle' fill='#555'>${Era.label(t.toInt())}</text>"
        }
        val modeMarks = result.modes.joinToString("") { m ->
            "<circle cx='${"%.1f".format(px(m.calBp.toDouble()))}' cy='${"%.1f".format(py(m.density))}' r='3' fill='#b3322c'><title>mode ${esc(m.label)}</title></circle>"
        }
        val trunc = if (result.boundaryTruncated)
            "<text x='${W - PAD_R}' y='12' font-size='10' text-anchor='end' fill='#b3322c'>boundary truncated: outside mass ${"%.3f".format(result.outsideMass)}</text>" else ""
        val era = if (result.eraCross)
            "<text x='$PAD_L' y='12' font-size='10' fill='#1d6f42'>posterior crosses BCE/CE boundary (year 0 = 1 BCE)</text>" else ""
        return """
        <svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 $W $H' font-family='sans-serif'>
          ${band(result.intervals95, "#7fa8d1", 0.28)}
          ${band(result.intervals68, "#2b4f7a", 0.30)}
          <path d='$path L${"%.1f".format(px(xMax))},${py(0.0)} L${"%.1f".format(px(xMin))},${py(0.0)} Z' fill='#2b4f7a' fill-opacity='0.12' stroke='#2b4f7a' stroke-width='1.4'/>
          $modeMarks
          $tickMarks
          $trunc$era
        </svg>
        """.trimIndent()
    }

    /** Sequence relations: samples on the cal BP axis with ordering arrows. */
    fun sequence(snap: ProjectSnapshot): String {
        val results = snap.results.filter { !it.excludedSample }
        if (snap.links.isEmpty()) return "<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 $W 120' font-family='sans-serif'><text x='20' y='40' fill='#888' font-size='12'>No sequence constraints declared.</text></svg>"
        val allBp = results.flatMap { rv -> rv.result.intervals95.flatMap { listOf(it.fromBp, it.toBp) } }
        val xMin = (allBp.minOrNull() ?: 1900).toDouble() - 40
        val xMax = (allBp.maxOrNull() ?: 100).toDouble() + 40
        fun px(bp: Double) = PAD_L + (bp - xMin) / (xMax - xMin) * (W - PAD_L - PAD_R)
        val sampleRow = snap.links.flatMap { listOf(it.olderSample, it.youngerSample) }.distinct().withIndex().associate { it.value to (54 + it.index * 46) }
        val samples = sampleRow.entries.joinToString("") { (sample, y) ->
            val rv = results.lastOrNull { it.result.sampleId == sample }
            val ivls = rv?.result?.intervals95.orEmpty()
            val bars = ivls.joinToString("") { iv ->
                val x = px(iv.fromBp.toDouble()); val w = px(iv.toBp.toDouble()) - x
                "<rect x='${"%.1f".format(x)}' y='${y - 8}' width='${"%.1f".format(w)}' height='16' rx='3' fill='#7fa8d1' fill-opacity='0.6' data-ivl='${iv.id}'><title>${esc(iv.id)} ${"%.3f".format(iv.probability)}</title></rect>"
            }
            val label = "<text x='${PAD_L - 6}' y='${y + 4}' font-size='11' text-anchor='end' fill='#333'>${esc(sample)}</text>"
            bars + label
        }
        val latest = snap.latestAnalyses.firstOrNull()
        val arrows = snap.links.joinToString("") { link ->
            val y1 = sampleRow[link.olderSample] ?: 60
            val y2 = sampleRow[link.youngerSample] ?: 100
            val pair = latest?.pairProbabilities?.firstOrNull { it.linkId == link.id }
            val ok = pair?.consistent ?: true
            val color = if (ok) "#1d6f42" else "#b3322c"
            val x1 = px((results.lastOrNull { it.result.sampleId == link.olderSample }?.result?.intervals95?.minOfOrNull { it.toBp } ?: xMin).toDouble())
            val x2 = px((results.lastOrNull { it.result.sampleId == link.youngerSample }?.result?.intervals95?.maxOfOrNull { it.fromBp } ?: xMax).toDouble())
            "<path d='M${"%.1f".format(x1)},$y1 C${"%.1f".format((x1 + x2) / 2)},$y1 ${"%.1f".format((x1 + x2) / 2)},$y2 ${"%.1f".format(x2)},$y2' fill='none' stroke='$color' stroke-width='2' marker-end='url(#arrow-${if (ok) "ok" else "bad"})'/>" +
                "<text x='${"%.1f".format((x1 + x2) / 2)}' y='${(y1 + y2) / 2 - 4}' font-size='10' fill='$color' text-anchor='middle'>${if (ok) "consistent" else "CONTRADICTION"} P=${pair?.let { "%.2f".format(it.probability) } ?: "?"}</text>"
        }
        return """
        <svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 $W ${maxOf(120, sampleRow.size * 46 + 40)}' font-family='sans-serif'>
          <defs>
            <marker id='arrow-ok' markerWidth='8' markerHeight='8' refX='7' refY='3' orient='auto'><path d='M0,0 L7,3 L0,6 Z' fill='#1d6f42'/></marker>
            <marker id='arrow-bad' markerWidth='8' markerHeight='8' refX='7' refY='3' orient='auto'><path d='M0,0 L7,3 L0,6 Z' fill='#b3322c'/></marker>
          </defs>
          $samples
          $arrows
        </svg>
        """.trimIndent()
    }

    private fun ticks(min: Double, max: Double, count: Int): List<Double> {
        val step = ((max - min) / count)
        return (0..count).map { min + step * it }
    }
}
