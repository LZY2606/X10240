package workbench

object Svg {
    private fun fmt(v: Double) = "%.1f".format(v)

    fun densityChart(cal: CalibrationResult, width: Int = 860, height: Int = 300): String {
        val grid = cal.grid()
        val d = cal.density
        val padL = 60.0; val padR = 20.0; val padT = 20.0; val padB = 40.0
        val x0 = grid.first(); val x1 = grid.last()
        val yMax = (d.maxOrNull() ?: 1.0) * 1.1
        fun sx(t: Double) = padL + (t - x0) / (x1 - x0) * (width - padL - padR)
        fun sy(p: Double) = height - padB - p / yMax * (height - padT - padB)
        val sb = StringBuilder()
        sb.append("<svg width='$width' height='$height' xmlns='http://www.w3.org/2000/svg' style='background:#fff;border:1px solid #ccc'>")
        val colors = mapOf(0.6827 to "rgba(46,134,222,0.35)", 0.9545 to "rgba(46,134,222,0.15)")
        for (iv in cal.intervals.sortedByDescending { it.level }) {
            val x = sx(iv.fromCalBP); val w = sx(iv.toCalBP) - x
            sb.append("<rect x='${fmt(x)}' y='$padT' width='${fmt(w)}' height='${fmt(height - padT - padB)}' fill='${colors[iv.level] ?: "rgba(0,0,0,0.1)"}'/>")
        }
        val path = StringBuilder("M ${fmt(sx(grid[0]))} ${fmt(sy(0.0))}")
        for (i in grid.indices) path.append(" L ${fmt(sx(grid[i]))} ${fmt(sy(d[i]))}")
        path.append(" L ${fmt(sx(grid.last()))} ${fmt(sy(0.0))} Z")
        sb.append("<path d='$path' fill='rgba(46,134,222,0.25)' stroke='#2e86de' stroke-width='1.5'/>")
        var t = Math.ceil(x0 / 500) * 500
        while (t <= x1) {
            sb.append("<line x1='${fmt(sx(t))}' y1='${fmt(height - padB)}' x2='${fmt(sx(t))}' y2='${fmt(height - padB + 5)}' stroke='#333'/>")
            sb.append("<text x='${fmt(sx(t))}' y='${fmt(height - padB + 18)}' font-size='11' text-anchor='middle'>${t.toInt()}</text>")
            t += 500
        }
        val boundary = 1950.0
        if (boundary in x0..x1) {
            sb.append("<line x1='${fmt(sx(boundary))}' y1='$padT' x2='${fmt(sx(boundary))}' y2='${fmt(height - padB)}' stroke='#c0392b' stroke-dasharray='4,3'/>")
            sb.append("<text x='${fmt(sx(boundary))}' y='${fmt(padT - 5)}' font-size='10' text-anchor='middle' fill='#c0392b'>BCE/CE</text>")
        }
        sb.append("<text x='${fmt(width / 2.0)}' y='${fmt(height - 5.0)}' font-size='12' text-anchor='middle'>calBP (older →)</text>")
        sb.append("</svg>")
        return sb.toString()
    }

    fun curveChart(curve: CurveData, measurement: Measurement?, deltaR: Double, width: Int = 860, height: Int = 300): String {
        val padL = 60.0; val padR = 20.0; val padT = 20.0; val padB = 40.0
        val x0 = curve.points.minOf { it.calBP }; val x1 = curve.points.maxOf { it.calBP }
        val y0 = curve.points.minOf { it.c14Age }; val y1 = curve.points.maxOf { it.c14Age }
        fun sx(t: Double) = padL + (t - x0) / (x1 - x0) * (width - padL - padR)
        fun sy(c: Double) = height - padB - (c - y0) / (y1 - y0) * (height - padT - padB)
        val sb = StringBuilder()
        sb.append("<svg width='$width' height='$height' xmlns='http://www.w3.org/2000/svg' style='background:#fff;border:1px solid #ccc'>")
        val pts = curve.points.joinToString(" ") { "${fmt(sx(it.calBP))},${fmt(sy(it.c14Age))}" }
        sb.append("<polyline points='$pts' fill='none' stroke='#27ae60' stroke-width='1.5'/>")
        if (measurement != null) {
            val adj = measurement.c14Age - deltaR
            val band = measurement.sigma
            sb.append("<rect x='$padL' y='${fmt(sy(adj + band))}' width='${fmt(width - padL - padR)}' height='${fmt(sy(adj - band) - sy(adj + band))}' fill='rgba(231,76,60,0.15)'/>")
            sb.append("<line x1='$padL' y1='${fmt(sy(adj))}' x2='${fmt(width - padR)}' y2='${fmt(sy(adj))}' stroke='#e74c3c' stroke-width='1.5'/>")
            sb.append("<text x='${fmt(width - padR - 5)}' y='${fmt(sy(adj) - 5)}' font-size='11' text-anchor='end' fill='#e74c3c'>${measurement.sampleId} ${measurement.c14Age}±${measurement.sigma} BP (ΔR=$deltaR)</text>")
        }
        var t = Math.ceil(x0 / 1000) * 1000
        while (t <= x1) {
            sb.append("<text x='${fmt(sx(t))}' y='${fmt(height - padB + 18)}' font-size='11' text-anchor='middle'>${t.toInt()}</text>")
            t += 1000
        }
        sb.append("<text x='${fmt(width / 2.0)}' y='${fmt(height - 5.0)}' font-size='12' text-anchor='middle'>calBP</text>")
        sb.append("<text x='15' y='${fmt(height / 2.0)}' font-size='12' transform='rotate(-90 15 ${fmt(height / 2.0)})' text-anchor='middle'>¹⁴C age BP</text>")
        sb.append("</svg>")
        return sb.toString()
    }

    fun sequenceChart(state: ProjectState, width: Int = 860): String {
        val samples = state.measurements.keys.toList()
        if (samples.isEmpty()) return "<p>无样品</p>"
        val rowH = 46; val padL = 110.0; val padR = 30.0; val padT = 30.0
        val height = padT + samples.size * rowH + 40
        val cals = samples.mapNotNull { state.latestCalibration(it) }
        val x0 = (cals.minOfOrNull { it.gridStart } ?: 0.0)
        val x1 = (cals.maxOfOrNull { it.gridStart + it.gridStep * (it.density.size - 1) } ?: 6000.0)
        fun sx(t: Double) = padL + (t - x0) / (x1 - x0) * (width - padL - padR)
        val sb = StringBuilder()
        sb.append("<svg width='$width' height='${height.toInt()}' xmlns='http://www.w3.org/2000/svg' style='background:#fff;border:1px solid #ccc'>")
        val rowY = samples.withIndex().associate { (i, s) -> s to (padT + i * rowH + rowH / 2) }
        for ((s, y) in rowY) {
            sb.append("<text x='${fmt(padL - 8)}' y='${fmt(y + 4)}' font-size='12' text-anchor='end'>$s</text>")
            val cal = state.latestCalibration(s) ?: continue
            val stale = cal.calibrationId in state.staleCalibrations
            for (iv in cal.intervals.filter { it.level > 0.9 }) {
                sb.append("<rect x='${fmt(sx(iv.fromCalBP))}' y='${fmt(y - 7)}' width='${fmt(sx(iv.toCalBP) - sx(iv.fromCalBP))}' height='14' fill='${if (stale) "rgba(150,150,150,0.4)" else "rgba(46,134,222,0.5)"}'/>")
            }
        }
        for (c in state.constraints) {
            val yo = rowY[c.olderSampleId]; val yy = rowY[c.youngerSampleId]
            if (yo == null || yy == null) continue
            val conflict = state.conflicts.values.any { it.constraintId == c.constraintId }
            val color = if (conflict) "#e74c3c" else "#7f8c8d"
            val xo = sx(state.latestCalibration(c.olderSampleId)?.intervals?.firstOrNull()?.fromCalBP ?: x0)
            val xy = sx(state.latestCalibration(c.youngerSampleId)?.intervals?.firstOrNull()?.fromCalBP ?: x0)
            sb.append("<line x1='${fmt(xo)}' y1='${fmt(yo)}' x2='${fmt(xy)}' y2='${fmt(yy)}' stroke='$color' stroke-width='2' marker-end='url(#arr)'/>")
        }
        sb.insert(sb.indexOf(">", sb.indexOf("<svg")) + 1, "<defs><marker id='arr' markerWidth='8' markerHeight='8' refX='7' refY='4' orient='auto'><path d='M0,0 L8,4 L0,8 Z' fill='#7f8c8d'/></marker></defs>")
        sb.append("</svg>")
        return sb.toString()
    }
}
