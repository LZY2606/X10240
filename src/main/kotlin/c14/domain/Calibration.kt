package c14.domain

import kotlin.math.*

/** Standard normal distribution helpers. */
object Gaussian {
    fun pdf(x: Double, mu: Double, sigma: Double): Double {
        val z = (x - mu) / sigma
        return exp(-0.5 * z * z) / (sigma * sqrt(2.0 * PI))
    }

    /** Abramowitz & Stegun 7.1.26 cumulative normal, |error| < 7.5e-8. */
    fun cdf(x: Double, mu: Double, sigma: Double): Double {
        val z = (x - mu) / sigma
        if (z < -8.0) return 0.0
        if (z > 8.0) return 1.0
        val sign = if (z < 0) -1.0 else 1.0
        val az = abs(z) / sqrt(2.0)
        val t = 1.0 / (1.0 + 0.3275911 * az)
        val y = 1.0 - (((((1.061405429 * t - 1.453152027) * t) + 1.421413741) * t - 0.284496736) * t + 0.254829592) * t * exp(-az * az)
        return 0.5 * (1.0 + sign * y)
    }
}

/** Piecewise-linear calibration curve evaluator. Knots must be sorted ascending. */
class CurveInterpolation(val curve: CalibrationCurve) {
    private val knots = curve.knots.sortedBy { it.calBp }
    val minBp: Int = knots.first().calBp
    val maxBp: Int = knots.last().calBp

    fun contains(bp: Int): Boolean = bp in minBp..maxBp

    /** Linear interpolation on (cal BP, 14C BP) knots. Exact at every knot. */
    fun ageAt(bp: Int): Double {
        if (bp <= minBp) return knots.first().c14AgeBp.toDouble()
        if (bp >= maxBp) return knots.last().c14AgeBp.toDouble()
        var lo = 0
        var hi = knots.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (knots[mid].calBp <= bp) lo = mid else hi = mid
        }
        val a = knots[lo]
        val b = knots[hi]
        val f = (bp - a.calBp).toDouble() / (b.calBp - a.calBp)
        return a.c14AgeBp + f * (b.c14AgeBp - a.c14AgeBp)
    }
}

data class CalibrationInputs(
    val measurement: Measurement,
    val curve: CalibrationCurve,
    val offset: OffsetModel?,
    val gridStep: Int = DEFAULT_GRID_STEP_YR
)

/**
 * Radiocarbon calibration.
 *
 * Produces the *whole* posterior distribution — never a single averaged year.
 * Deterministic: same inputs + algorithm version always give the same grid.
 */
class Calibrator(private val algoVersion: String = ALGO_VERSION) {
    fun calibrate(inputs: CalibrationInputs): CalibrationResult {
        val m = inputs.measurement
        val interp = CurveInterpolation(inputs.curve)
        val step = inputs.gridStep
        val points = (interp.minBp..interp.maxBp step step).toList()

        val raw = DoubleArray(points.size) { i ->
            likelihood(m.ageBp, m.sigmaBp, inputs.offset, interp.ageAt(points[i]))
        }

        // Trapezoid integral of the un-normalized posterior over the curve support.
        var gridMass = 0.0
        for (i in raw.indices) {
            if (i == 0) continue
            gridMass += 0.5 * (raw[i - 1] + raw[i]) * step
        }
        if (gridMass <= 0.0) gridMass = Double.MIN_VALUE

        val outside = outsideEdgeMass(m.ageBp, m.sigmaBp, inputs.offset, interp)
        val totalMass = gridMass + outside
        val supportMass = gridMass / totalMass
        val outsideMass = outside / totalMass

        val density = DoubleArray(points.size) { raw[it] / gridMass }
        val cellMass = trapezoidMasses(density, step)
        var cum = 0.0
        val cells = points.mapIndexed { i, bp ->
            val left = cum
            val own = cellMass[i]
            cum += own
            Cell(bp, density[i], left, (1.0 - left - own).coerceIn(0.0, 1.0))
        }

        val intervals95 = hpdIntervals(cells, cellMass, 0.95, m.id, 0)
        val intervals68 = hpdIntervals(cells, cellMass, 0.68, m.id, 100)
        val modes = findModes(cells)

        val eraCross = intervals95.any { Era.crossesBceCe(it.fromBp, it.toBp) } ||
            intervals68.any { Era.crossesBceCe(it.fromBp, it.toBp) }
        val truncated = outsideMass > 0.01

        val paramFingerprint = Fingerprint.of(
            mapOf(
                "algo" to algoVersion,
                "measurementId" to m.id,
                "curveId" to inputs.curve.id,
                "offsetId" to (inputs.offset?.id ?: "none"),
                "gridStep" to step
            )
        )
        val resultId = "cal:${m.id}@${inputs.curve.id}" +
            (inputs.offset?.let { ":$it" } ?: "") +
            ":$paramFingerprint".take(12).let { fp -> ":$fp" }

        val sources = buildList {
            add(m.sourceId)
            add(inputs.curve.sourceId)
            if (inputs.offset != null) add(inputs.offset.sourceId)
        }.distinct()

        return CalibrationResult(
            id = resultId,
            sampleId = m.sampleId,
            measurementId = m.id,
            curveId = inputs.curve.id,
            offsetId = inputs.offset?.id,
            algoVersion = algoVersion,
            gridStep = step,
            paramFingerprint = paramFingerprint,
            cells = cells,
            intervals68 = intervals68,
            intervals95 = intervals95,
            modes = modes,
            supportMass = supportMass,
            outsideMass = outsideMass,
            boundaryTruncated = truncated,
            eraCross = eraCross,
            directSources = sources,
            eventSeq = -1
        )
    }

    private fun likelihood(age: Double, sigma: Double, offset: OffsetModel?, curveAge: Double): Double {
        if (offset == null || (!offset.discrete && offset.components.isEmpty())) {
            val mu = age - (offset?.meanBp ?: 0.0)
            val s = sqrt(sigma * sigma + (offset?.sigmaBp ?: 0.0).let { it * it })
            return Gaussian.pdf(curveAge, mu, s)
        }
        val wSum = offset.components.sumOf { it.weight }
        return offset.components.sumOf { c ->
            val w = c.weight / wSum
            val mu = age - c.shiftBp
            val s = sqrt(sigma * sigma + c.sigmaBp * c.sigmaBp)
            w * Gaussian.pdf(curveAge, mu, s)
        }
    }

    /** Probability mass beyond either curve edge, using the edge curve values. */
    private fun outsideEdgeMass(age: Double, sigma: Double, offset: OffsetModel?, interp: CurveInterpolation): Double {
        val yOld = interp.ageAt(interp.minBp)
        val yYoung = interp.ageAt(interp.maxBp)
        if (offset == null || (!offset.discrete && offset.components.isEmpty())) {
            val mu = age - (offset?.meanBp ?: 0.0)
            val s = sqrt(sigma * sigma + (offset?.sigmaBp ?: 0.0).let { it * it })
            // Older than oldest support: 14C age above yOld. Younger: below yYoung.
            return (1.0 - Gaussian.cdf(yOld, mu, s)) + Gaussian.cdf(yYoung, mu, s)
        }
        val wSum = offset.components.sumOf { it.weight }
        return offset.components.sumOf { c ->
            val w = c.weight / wSum
            val mu = age - c.shiftBp
            val s = sqrt(sigma * sigma + c.sigmaBp * c.sigmaBp)
            w * ((1.0 - Gaussian.cdf(yOld, mu, s)) + Gaussian.cdf(yYoung, mu, s))
        }
    }

    private fun trapezoidMasses(density: DoubleArray, step: Int): DoubleArray {
        val out = DoubleArray(density.size)
        for (i in density.indices) {
            val left = if (i > 0) 0.5 * (density[i - 1] + density[i]) * step else 0.5 * density[i] * step
            val right = if (i < density.size - 1) 0.0 else 0.5 * density[i] * step
            out[i] = left + right
        }
        var sum = out.sum()
        if (sum <= 0.0) sum = 1.0
        for (i in out.indices) out[i] /= sum
        return out
    }

    /**
     * Highest posterior density intervals: cells above the lowest threshold
     * whose trapezoidal mass reaches [level]. Contiguous runs become intervals,
     * so a multi-modal posterior is kept as multiple intervals.
     */
    private fun hpdIntervals(
        cells: List<Cell>,
        masses: DoubleArray,
        level: Double,
        measurementId: String,
        idOffset: Int
    ): List<Interval> {
        if (cells.isEmpty()) return emptyList()
        var threshold = 0.0
        val order = cells.indices.sortedByDescending { cells[it].density }
        var acc = 0.0
        for (idx in order) {
            threshold = cells[idx].density
            acc += masses[idx]
            if (acc >= level) break
        }
        val included = BooleanArray(cells.size) { cells[it].density >= threshold - 1e-15 }
        val ranges = mutableListOf<IntRange>()
        var start = -1
        for (i in cells.indices) {
            if (included[i] && start < 0) start = i
            if ((!included[i] || i == cells.size - 1) && start >= 0) {
                val end = if (included[i]) i else i - 1
                ranges.add(start..end)
                start = -1
            }
        }
        return ranges.mapIndexed { rank, range ->
            var prob = 0.0
            var modeIdx = range.first
            for (i in range) {
                prob += masses[i]
                if (cells[i].density > cells[modeIdx].density) modeIdx = i
            }
            val ivlRank = idOffset + rank
            Interval(
                id = "ivl:$measurementId:${(level * 100).toInt()}:$rank",
                fromBp = cells[range.first].calBp,
                toBp = cells[range.last].calBp,
                probability = prob,
                level = level,
                rank = ivlRank,
                modeBp = cells[modeIdx].calBp,
                evidenceFromIndex = range.first,
                evidenceToIndex = range.last,
                cells = cells.slice(range)
            )
        }.sortedByDescending { it.probability }.mapIndexed { displayRank, ivl ->
            ivl.copy(id = "ivl:$measurementId:${(level * 100).toInt()}:$displayRank", rank = idOffset + displayRank)
        }
    }

    private fun findModes(cells: List<Cell>): List<Mode> {
        if (cells.isEmpty()) return emptyList()
        val peak = cells.maxOf { it.density }
        val modes = mutableListOf<Mode>()
        var i = 0
        while (i < cells.size) {
            val localMax = (i == 0 || cells[i].density > cells[i - 1].density) &&
                (i == cells.size - 1 || cells[i].density >= cells[i + 1].density)
            if (localMax && cells[i].density >= 0.2 * peak) {
                var j = i
                while (j + 1 < cells.size && cells[j + 1].density == cells[i].density) j++
                val center = (i + j) / 2
                modes.add(Mode(cells[center].calBp, Era.label(cells[center].calBp), cells[center].density))
                i = j + 1
            } else i++
        }
        return modes
    }
}
