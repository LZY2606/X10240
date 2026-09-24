package workbench

import kotlin.math.max

object SvgCharts {
    fun workbench(state: ProjectState): String {
        val width = 1120
        val height = 620
        val left = 70
        val right = 1060
        val top = 40
        val curveBottom = 250
        val densityBottom = 560
        val curve = state.curves.values.maxByOrNull { it.points.maxOf { point -> point.calendarYear } } ?: return emptySvg()
        val minYear = curve.points.minOf { it.calendarYear }
        val maxYear = curve.points.maxOf { it.calendarYear }
        val minAge = curve.points.minOf { it.calendarAgeSafe() }
        val maxAge = curve.points.maxOf { it.calendarAgeSafe() }
        fun x(year: Int): Double = left + (year - minYear).toDouble() / (maxYear - minYear) * (right - left)
        fun yAge(age: Double): Double = top + (maxAge - age) / (maxAge - minAge) * (curveBottom - top)
        val curvePath = curve.points.joinToString(" ") { point -> "${if (point == curve.points.first()) "M" else "L"}${fmt(x(point.calendarYear))},${fmt(yAge(point.calendarAgeSafe()))}" }
        val oldCurve = curve.parentId?.let { state.curves[it] }
        val oldPath = oldCurve?.points?.joinToString(" ") { point -> "${if (point == oldCurve.points.first()) "M" else "L"}${fmt(x(point.calendarYear))},${fmt(yAge(point.calendarAgeSafe()))}" }
        val colors = listOf("#2563eb", "#dc2626", "#7c3aed", "#059669", "#64748b")
        val density = state.calibrations.filter { it.status != "stale" && state.measurements[it.measurementSeriesId]?.excluded != true }.groupBy { it.measurementSeriesId }.map { it.value.last() }
        val maxProb = density.flatMap { it.probabilities }.maxOfOrNull { it.p } ?: 1.0
        fun yProb(probability: Double, lane: Int): Double {
            val laneHeight = 64
            val laneTop = 300 + lane * 58
            return laneTop + laneHeight - probability / maxProb * laneHeight
        }
        val densityPaths = density.mapIndexed { lane, calibration ->
            calibration.probabilities.joinToString(" ") { point ->
                "${if (point == calibration.probabilities.first()) "M" else "L"}${fmt(x(point.calendarYear))},${fmt(yProb(point.p, lane))}"
            } to lane
        }
        val sequenceLines = state.sequences.values.mapIndexed { index, sequence ->
            val older = density.lastOrNull { it.measurementSeriesId == sequence.olderId }
            val younger = density.lastOrNull { it.measurementSeriesId == sequence.youngerId }
            if (older == null || younger == null) "" else
                """<line x1="${fmt(x(older.modes.firstOrNull() ?: minYear))}" y1="${fmt(yProb(0.0, density.indexOf(older)) - 8)}" x2="${fmt(x(younger.modes.firstOrNull() ?: minYear))}" y2="${fmt(yProb(0.0, density.indexOf(younger)) - 8)}" stroke="#f59e0b" stroke-width="2" marker-end="url(#arrow)"/>
                  <text x="${fmt(x((older.modes.firstOrNull() ?: minYear)))}" y="${if (index == 0) 286 else 575}" class="small">${sequence.id} older→younger</text>"""
        }.joinToString("")
        val legend = state.measurements.values.filter { !it.excluded }.mapIndexed { index, measurement ->
            val reservoir = state.reservoirs[measurement.reservoirSeriesId]
            """<g transform="translate(${74 + index * 250},592)"><rect width="12" height="12" fill="${colors[index % colors.size]}"/><text x="18" y="11" class="small">${measurement.seriesId} · ΔR ${reservoir?.mean ?: 0}±${reservoir?.sigma ?: 0} ${reservoir?.unit ?: ""}</text></g>"""
        }.joinToString("")
        return """<svg xmlns="http://www.w3.org/2000/svg" width="$width" height="$height" viewBox="0 0 $width $height" role="img" aria-label="Calibration curves, multimodal probability densities and sequences">
<style>.small{font:11px ui-sans-serif,system-ui;fill:#334155}.axis{stroke:#94a3b8;stroke-width:1}.grid{stroke:#e2e8f0}.title{font:700 15px ui-sans-serif,system-ui;fill:#0f172a}</style>
<defs><marker id="arrow" markerWidth="8" markerHeight="8" refX="7" refY="3" orient="auto"><path d="M0,0 L0,6 L8,3 z" fill="#f59e0b"/></marker></defs>
<text x="70" y="22" class="title">Fixed calibration curve version: ${curve.id}</text>
<line x1="$left" y1="$curveBottom" x2="$right" y2="$curveBottom" class="axis"/><line x1="$left" y1="$top" x2="$left" y2="$densityBottom" class="axis"/>
${(-100..400 step 100).joinToString("") { year -> """<line x1="${fmt(x(year))}" y1="$top" x2="${fmt(x(year))}" y2="$densityBottom" class="grid"/><text x="${fmt(x(year)-12)}" y="575" class="small">${if (year < 0) "${-year} BCE" else if (year == 0) "0" else "$year CE"}</text>""" }}
<text x="535" y="608" text-anchor="middle" class="small">astronomical calendar year</text>
<text transform="translate(24 155) rotate(-90)" text-anchor="middle" class="small">14C age BP</text>
${if (oldPath != null) """<path d="$oldPath" fill="none" stroke="#94a3b8" stroke-width="2" stroke-dasharray="5 4"/>""" else ""}
<path d="$curvePath" fill="none" stroke="#0f766e" stroke-width="2.5"/>
<text x="920" y="24" class="small">solid: ${curve.id}${if (oldCurve != null) " · dashed: ${oldCurve.id}" else ""}</text>
<text x="70" y="282" class="title">Normalized calendar probability densities (no average year)</text>
${density.mapIndexed { index, calibration -> val color = colors[index % colors.size]; """<path d="${densityPaths.single { it.second == index }.first}" fill="none" stroke="$color" stroke-width="2"/>""" + calibration.modes.joinToString("") { year -> """<circle cx="${fmt(x(year))}" cy="${fmt(yProb(calibration.probabilities.single { it.calendarYear == year }.p, index))}" r="3.5" fill="$color"/>""" } }.joinToString("")}
$sequenceLines
$legend
</svg>"""
    }

    private fun CurvePoint.calendarAgeSafe(): Double = radiocarbonAge
    private fun fmt(value: Double): String = String.format("%.2f", value)
    private fun emptySvg(): String = """<svg xmlns="http://www.w3.org/2000/svg" width="800" height="200"><text x="20" y="40">No project imported</text></svg>"""
}
