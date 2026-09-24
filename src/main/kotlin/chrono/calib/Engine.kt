package chrono.calib

import chrono.domain.CalibrationCurve
import chrono.domain.CalendarInterval
import chrono.domain.GridPoint
import chrono.domain.Hypothesis
import chrono.domain.Measurement
import chrono.domain.Reservoir
import chrono.domain.SequenceConstraint
import chrono.domain.Units
import chrono.domain.CalibrationRun
import chrono.domain.SequenceEvaluation
import chrono.domain.AdjustmentProposal
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.math.round

const val ALGORITHM_VERSION = "calib-1.0.0"

/** Deterministic, fixed-version calibration. No RNG, no network. */
object CalibrationEngine {

    const val GRID_STEP = 5
    const val HIGHEST_PROBABILITY_68 = 0.6826894921370859
    const val HIGHEST_PROBABILITY_95 = 0.9544997361036416

    /** Astronomical year (0 = 1 BCE). Negative or zero means BCE side. */
    fun calBPToAstronomical(calBP: Double): Double = 1950.0 - calBP

    fun astronomicalToBceCe(astro: Double): String {
        // astro 1 -> 1 CE ; astro 0 -> 1 BCE ; astro -1 -> 2 BCE
        val rounded = round(astro)
        return if (rounded >= 1.0) "${rounded.toLong()} CE"
        else "${(1L - rounded.toLong())} BCE"
    }

    fun calBPToBceCe(calBP: Double): String = astronomicalToBceCe(calBPToAstronomical(calBP))

    private data class MixtureComponent(val weight: Double, val mean: Double, val sd: Double)

    private fun offsetComponents(reservoir: Reservoir?): List<MixtureComponent> {
        val spec = reservoir?.offset ?: return listOf(MixtureComponent(1.0, 0.0, 0.0))
        return when (spec) {
            is chrono.domain.OffsetSpec.Normal ->
                listOf(MixtureComponent(1.0, spec.meanBP, spec.sigmaBP))
            is chrono.domain.OffsetSpec.Mixture -> {
                val w = spec.components.sumOf { it.weight }
                spec.components.map {
                    MixtureComponent(it.weight / w, it.meanBP, it.sigmaBP)
                }
            }
            is chrono.domain.OffsetSpec.Samples ->
                listOf(MixtureComponent(1.0, spec.mean, spec.sd))
        }
    }

    /**
     * Likelihood of measurement D,sig given calendar age t on curve mu(t),sigmaC(t)
     * under reservoir offset mixture (R_j, sigR_j):
     *
     *   L(t) = sum_j w_j * N(D | mu(t) - R_j, sqrt(sig^2 + sigC(t)^2 + sigR_j^2))
     *
     * D is 14C age BP; a positive reservoir R shifts the *true* 14C age upward,
     * so the expected unreserved value is D_observed = mu(t) + R.
     */
    private fun likelihood(
        d: Double,
        sigD: Double,
        curveValue: Double,
        curveSigma: Double,
        comps: List<MixtureComponent>
    ): Double {
        var acc = 0.0
        for (c in comps) {
            val mean = curveValue + c.mean
            val variance = sigD * sigD + curveSigma * curveSigma + c.sd * c.sd
            val sd = sqrt(variance)
            val z = (d - mean) / sd
            acc += c.weight * exp(-0.5 * z * z) / (sd * 2.5066282746310002)
        }
        return acc
    }

    data class GridResult(
        val calBP: IntArray,
        val raw: DoubleArray,
        val density: DoubleArray,
        val inside: BooleanArray,
        val lowTruncated: Boolean,
        val highTruncated: Boolean
    )

    fun computeGrid(
        measurement: Measurement,
        reservoir: Reservoir?,
        curve: CalibrationCurve,
        step: Int = GRID_STEP
    ): GridResult {
        val span = curve.span
        val start = (kotlin.math.ceil(span.start / step) * step).toInt()
        val end = (kotlin.math.floor(span.endInclusive / step) * step).toInt()
        val n = (end - start) / step + 1
        val ages = IntArray(n)
        val raw = DoubleArray(n)
        val inside = BooleanArray(n)
        val comps = offsetComponents(reservoir)

        // Evaluate one half step beyond each edge to detect truncation.
        val lowProbe = span.start - step / 2.0
        val highProbe = span.endInclusive + step / 2.0
        val lowTrunc = curve.interpolateAt(lowProbe) == null && run {
            val atEdge = curve.interpolateAt(span.start + 1e-9)
            atEdge != null && likelihood(
                measurement.ageBP, measurement.sigmaBP, atEdge.c14BP, atEdge.sigmaC14, comps
            ) > 1e-12
        }
        val highTrunc = curve.interpolateAt(highProbe) == null && run {
            val atEdge = curve.interpolateAt(span.endInclusive - 1e-9)
            atEdge != null && likelihood(
                measurement.ageBP, measurement.sigmaBP, atEdge.c14BP, atEdge.sigmaC14, comps
            ) > 1e-12
        }

        var total = 0.0
        for (i in 0 until n) {
            val t = start + i * step
            ages[i] = t
            val interp = curve.interpolateAt(t.toDouble())
            if (interp == null) {
                raw[i] = 0.0
            } else {
                inside[i] = true
                val v = likelihood(
                    measurement.ageBP, measurement.sigmaBP,
                    interp.c14BP, interp.sigmaC14, comps
                )
                raw[i] = v
                total += v
            }
        }
        val density = DoubleArray(n) { if (total > 0.0) raw[it] / total else 0.0 }
        return GridResult(ages, raw, density, inside, lowTrunc, highTrunc)
    }

    /**
     * Highest-density regions. The posterior is NOT reduced to one mean year:
     * disjoint above-threshold islands stay separate intervals, one per mode island.
     */
    fun highestDensityIntervals(grid: GridResult, level: String): List<CalendarInterval> {
        val target = if (level == "68.2%") HIGHEST_PROBABILITY_68 else HIGHEST_PROBABILITY_95
        val n = grid.density.size
        if (n == 0 || grid.density.sum() == 0.0) return emptyList()
        val step = grid.calBP[1] - grid.calBP[0]

        // HDR threshold: include descending densities until target mass reached.
        val order = (0 until n).sortedWith(compareByDescending<Int> { grid.density[it] })
        val included = BooleanArray(n)
        var mass = 0.0
        var threshold = 0.0
        for (idx in order) {
            val p = grid.density[idx]
            if (mass + p >= target - 1e-12) {
                threshold = p
                // include this final bucket too (mass may slightly exceed target, deterministically)
                included[idx] = true
                mass += p
                break
            }
            included[idx] = true
            mass += p
            threshold = p
        }

        val intervals = mutableListOf<CalendarInterval>()
        var i = 0
        var islandIndex = 0
        while (i < n) {
            if (!included[i] || grid.density[i] + 1e-15 < threshold) {
                i++
                continue
            }
            val j0 = i
            var peak = grid.density[i]
            var islandMass = 0.0
            while (i < n && included[i] && grid.density[i] + 1e-15 >= threshold) {
                if (grid.density[i] > peak) peak = grid.density[i]
                islandMass += grid.density[i]
                i++
            }
            val j1 = i - 1
            val startBP = grid.calBP[j0].toDouble() - step / 2.0
            val endBP = grid.calBP[j1].toDouble() + step / 2.0
            val crosses = crossesYearZero(startBP, endBP)
            val truncated = (j0 == 0 && grid.lowTruncated) || (j1 == n - 1 && grid.highTruncated)
            intervals += CalendarInterval(
                intervalId = "",
                level = level,
                index = islandIndex,
                startCalBP = startBP,
                endCalBP = endBP,
                probabilityMass = islandMass,
                relativePeakDensity = peak,
                crossesBceCe = crosses,
                boundaryTruncated = truncated
            )
            islandIndex++
        }
        return intervals
    }

    /** BCE/CE boundary: astronomical year 0 (= 1 BCE) lies within [startBP, endBP] cal BP. */
    fun crossesYearZero(startCalBP: Double, endCalBP: Double): Boolean {
        val zeroInCalBP = 1950.0
        return startCalBP <= zeroInCalBP && zeroInCalBP <= endCalBP
    }

    fun countModes(grid: GridResult): Int {
        // count interior local maxima, merging flat shoulders; separated by near-zero troughs
        var count = 0
        for (i in grid.density.indices) {
            val left = if (i == 0) 0.0 else grid.density[i - 1]
            val right = if (i == grid.density.size - 1) 0.0 else grid.density[i + 1]
            if (grid.density[i] > left && grid.density[i] >= right && grid.density[i] > 1e-9) count++
        }
        return count
    }

    fun integrateRange(grid: GridResult, startBP: Double, endBP: Double): Double {
        val step = (grid.calBP[1] - grid.calBP[0]).toDouble()
        var acc = 0.0
        for (i in grid.calBP.indices) {
            val lo = grid.calBP[i] - step / 2.0
            val hi = grid.calBP[i] + step / 2.0
            if (hi <= startBP || lo >= endBP) continue
            acc += grid.density[i]
        }
        return acc
    }

    /**
     * Sequence probability from two discrete posteriors.
     * P(A older than B) = sum over tA > tB pA(tA) pB(tB)
     * P(coeval)      = sum over same bucket (within one grid step)
     */
    fun evaluateSequence(
        gridA: GridResult,
        gridB: GridResult,
        coeval: Boolean
    ): Triple<Double, Double, Double> {
        // Both grids use the same curve axis; resample B onto A indices by nearest age.
        val step = gridA.calBP[1] - gridA.calBP[0]
        val bByAge = HashMap<Int, Double>()
        for (i in gridB.calBP.indices) bByAge[gridB.calBP[i]] = gridB.density[i]
        var older = 0.0
        var younger = 0.0
        var same = 0.0
        for (i in gridA.calBP.indices) {
            val pa = gridA.density[i]
            if (pa == 0.0) continue
            val ageA = gridA.calBP[i]
            for ((ageB, pb) in bByAge) {
                when {
                    ageA > ageB + step / 2 -> older += pa * pb
                    ageA < ageB - step / 2 -> younger += pa * pb
                    else -> same += pa * pb
                }
            }
        }
        val s = older + younger + same
        if (s > 0) {
            older /= s; younger /= s; same /= s
        }
        return Triple(older, younger, same)
    }
}
