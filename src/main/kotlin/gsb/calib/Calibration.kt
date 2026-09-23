package gsb.calib

import gsb.model.*
import gsb.util.Ids
import kotlin.math.*

/**
 * Deterministic radiocarbon calibration.
 *
 * Algorithm version is pinned; every derived value carries a parameter
 * fingerprint so identical inputs always reproduce identical posteriors.
 * The posterior is a *multi-modal* density on a fixed calBP grid: it is never
 * collapsed to a mean year.
 */
object Calibrator {
    const val ALGORITHM_VERSION = "calib-grid-gaussian-mixture/v1.2.0"
    const val GRID_STEP_BP = 5.0

    /** Linear interpolation of a calibration curve at an arbitrary calBP age,
     *  i.e. boundary interpolation between knot points. */
    fun curveAt(curve: CalibrationCurve, tBP: Double): Pair<Double, Double> {
        require(curve.c14BP.isNotEmpty())
        if (tBP <= curve.anchorBP) return curve.c14BP.first() to curve.sigmaBP.first()
        if (tBP >= curve.maxBP) return curve.c14BP.last() to curve.sigmaBP.last()
        val pos = (tBP - curve.anchorBP) / curve.stepBP
        val i = floor(pos).toInt().coerceAtMost(curve.c14BP.size - 2)
        val f = pos - i
        val c = curve.c14BP[i] + f * (curve.c14BP[i + 1] - curve.c14BP[i])
        val s = curve.sigmaBP[i] + f * (curve.sigmaBP[i + 1] - curve.sigmaBP[i])
        return c to s
    }

    /** Weighted Gaussian-mixture likelihood for one calendar age.
     *  Mixture over reservoir scenarios plus measurement/curve uncertainty. */
    private fun likelihood(
        observed: Double, obsSigma: Double, curveC: Double, curveSigma: Double,
        scenarios: List<OffsetScenario>
    ): Double {
        var total = 0.0
        var wsum = 0.0
        for (sc in scenarios) {
            // effective 14C observation after subtracting reservoir offset DeltaR
            val mu = observed - sc.mean
            val variance = obsSigma * obsSigma + curveSigma * curveSigma + sc.sigma * sc.sigma
            val sd = sqrt(variance)
            val z = (mu - curveC) / sd
            total += sc.weight * exp(-0.5 * z * z) / (sd * sqrt(2.0 * PI))
            wsum += sc.weight
        }
        return if (wsum == 0.0) 0.0 else total / wsum
    }

    data class Posterior(
        val gridBP: List<Double>,
        val density: List<Double>,
        val normalized: Boolean,
        val mass: Double
    )

    fun posterior(
        measurement: Measurement, curve: CalibrationCurve, hypothesis: OffsetHypothesis
    ): Posterior {
        val obs = measurement.c14Age.value
            ?: error("measurement ${measurement.code} has no numeric 14C age")
        val obsSig = measurement.c14Sigma.value
            ?: error("measurement ${measurement.code} has no numeric 14C sigma")
        val lo = ceil(curve.anchorBP / GRID_STEP_BP) * GRID_STEP_BP
        val hi = floor(curve.maxBP / GRID_STEP_BP) * GRID_STEP_BP
        val n = ((hi - lo) / GRID_STEP_BP).toInt() + 1
        val grid = ArrayList<Double>(n)
        val raw = ArrayList<Double>(n)
        for (i in 0 until n) {
            val t = lo + i * GRID_STEP_BP
            val (cc, cs) = curveAt(curve, t)
            grid.add(t)
            raw.add(likelihood(obs, obsSig, cc, cs, hypothesis.scenarios))
        }
        val mass = raw.sum() * GRID_STEP_BP
        val density = if (mass > 0) raw.map { it / mass } else raw.map { 0.0 }
        return Posterior(grid, density, mass > 0, mass)
    }

    /**
     * Highest Posterior Density intervals from the normalised grid.
     * Sorts cells by density, accumulates until [coverage]; contiguous cells
     * above the threshold become separate HPD modes (parallel calendar ranges).
     */
    fun hpdIntervals(p: Posterior, coverage: Double = 0.9545): List<HpdInterval> {
        if (p.density.isEmpty() || !p.normalized) return emptyList()
        val sorted = p.density.withIndex().sortedByDescending { it.value }
        var acc = 0.0
        val thresholdCellCount: Int = run {
            var k = 0
            for ((_, d) in sorted) {
                acc += d * GRID_STEP_BP; k++
                if (acc >= coverage) break
            }
            k
        }
        val threshold = sorted[thresholdCellCount - 1].value
        val inside = BooleanArray(p.density.size) { p.density[it] >= threshold }
        val intervals = mutableListOf<HpdInterval>()
        var i = 0
        var idx = 0
        while (i < inside.size) {
            if (!inside[i]) { i++; continue }
            var j = i
            var modeD = 0.0; var modeAt = i
            var prob = 0.0
            while (j < inside.size && inside[j]) {
                prob += p.density[j] * GRID_STEP_BP
                if (p.density[j] > modeD) { modeD = p.density[j]; modeAt = j }
                j++
            }
            val from = p.gridBP[i] - GRID_STEP_BP / 2
            val to = p.gridBP[j - 1] + GRID_STEP_BP / 2
            intervals.add(HpdInterval(idx++, from, to, prob, p.gridBP[modeAt]))
            i = j
        }
        // Cells cross the discrete threshold, so empirical mass is reported per
        // interval; it is the integration evidence, not an invented mean.
        return intervals
    }

    fun isMultiModal(p: Posterior): Boolean {
        var modes = 0
        var rising = false
        var prev = 0.0
        for (d in p.density) {
            if (d > prev * 1.05) rising = true
            else if (rising && d < prev * 0.90) { modes++; rising = false }
            prev = d
        }
        if (rising) modes++
        return modes >= 2
    }

    fun fingerprint(
        measurement: Measurement, curve: CalibrationCurve, hypothesis: OffsetHypothesis
    ): String {
        val key = buildString {
            append(ALGORITHM_VERSION).append('|')
            append(measurement.id).append('@').append(measurement.c14Age.raw).append('+')
            append(measurement.c14Sigma.raw).append('|')
            append(curve.id).append('@').append(curve.version).append('|')
            append(hypothesis.id)
            for (s in hypothesis.scenarios.sortedBy { it.mean })
                append(":").append(s.mean).append(',').append(s.sigma).append(',').append(s.weight)
        }
        return Ids.sha256(key)
    }

    /** Integrate posterior density over [fromBP, toBP] on the fixed grid
     *  (trapezoidal between cell midpoints via half-cell boundaries). */
    fun intervalProbability(p: Posterior, fromBP: Double, toBP: Double): Double {
        var area = 0.0
        for ((i, t) in p.gridBP.withIndex()) {
            val a = max(fromBP, t - GRID_STEP_BP / 2)
            val b = min(toBP, t + GRID_STEP_BP / 2)
            if (b > a) area += p.density[i] * (b - a)
        }
        return area
    }
}

/* ---------------- era system: calBP <-> BCE/CE (astronomical year) ---------------- */
object Era {
    /** calBP -> astronomical calendar year (0 BCE = 1 BCE convention:
     *  astronomical year 0 is 1 BCE, year -1 is 2 BCE). */
    fun calBPToAstronomical(bp: Double, presentYear: Int = 1950): Double =
        presentYear - bp

    fun formatAstronomical(year: Double): String {
        val y = round(year).toInt()
        return when {
            y >= 1 -> "${y} CE"
            y == 0 -> "1 BCE"
            else -> "${-y + 1} BCE"
        }
    }

    /** Whether an HPD interval (in calBP) straddles the BCE/CE boundary. */
    fun straddlesBceCe(fromBP: Double, toBP: Double): Boolean {
        val a = calBPToAstronomical(fromBP)
        val b = calBPToAstronomical(toBP)
        val lo = min(a, b); val hi = max(a, b)
        return lo < 1.0 && hi >= 0.0
    }
}
