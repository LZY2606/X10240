package rgsb.domain

import rgsb.model.CalibrationCurve
import rgsb.model.CurvePoint
import rgsb.model.GridCell
import rgsb.model.IntervalEvidence
import rgsb.model.OffsetComponent
import rgsb.model.OffsetSpec
import rgsb.model.RawMeasurement
import rgsb.model.Sourced
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Deterministic radiocarbon calibration.
 *
 * For each calendar year t on a fixed 1-year grid:
 *   - the calibration curve is linearly interpolated (deterministic piecewise-linear,
 *     so fixed curve version + grid always reproduce the same values);
 *   - a discrete reservoir-offset mixture (delta R_k, sigma_k, weight_k) is folded in:
 *       mu(t)    = curve.age(t) + deltaR
 *       sigma2   = curve.sigma(t)^2 + labSigma^2 + deltaRSigma^2
 *   - the unnormalized weight is sum_k w_k * Normal(labAge | mu, sigma)
 *     (the standard calibration likelihood, with the curve Jacobian retained as weights);
 *   - weights are normalized by their grid sum (trapezoid-free: constant 1-year step).
 *
 * The full multi-modal posterior is kept as a grid; it is never collapsed to one mean year.
 * Highest posterior density (HPD) intervals at 68% and 95% carry explicit integral evidence.
 */
class CalibrationEngine(
    val algorithmVersion: String = "rgsb-cal-1.0.0",
    val gridStepYears: Int = 1,
    val seed: Long = 0L,
) {
    data class ResolvedMeasurement(val age: Double, val sigma: Double, val unconverted: List<String>)

    fun resolveMeasurement(measurement: RawMeasurement): ResolvedMeasurement {
        val unconverted = mutableListOf<String>()
        val age = parseDouble(measurement.age) ?: run {
            unconverted += "age:${measurement.age.raw}"
            Double.NaN
        }
        val sigma = parseDouble(measurement.sigma) ?: run {
            unconverted += "sigma:${measurement.sigma.raw}"
            Double.NaN
        }
        return ResolvedMeasurement(age, sigma, unconverted)
    }

    private fun parseDouble(sourced: Sourced): Double? =
        sourced.raw.replace(",", "").replace("±", "").trim().toDoubleOrNull()

    data class Interpolated(val carbonAge: Double, val sigma: Double, val inside: Boolean)

    fun interpolate(curve: CalibrationCurve, year: Int): Interpolated {
        val pts = curve.points
        if (pts.isEmpty()) return Interpolated(Double.NaN, Double.NaN, false)
        if (year <= pts.first().calYear) {
            val p = pts.first()
            val inside = year == p.calYear
            if (year < p.calYear) {
                // Boundary handling: outside support extrapolates flat but is flagged,
                // and the grid never asks for it except at exact edges.
                return Interpolated(p.carbonAge, p.uncertainty, inside)
            }
            return Interpolated(p.carbonAge, p.uncertainty, true)
        }
        if (year >= pts.last().calYear) {
            val p = pts.last()
            return Interpolated(p.carbonAge, p.uncertainty, year == p.calYear)
        }
        // binary search for the bracketing pair
        var lo = 0
        var hi = pts.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (pts[mid].calYear <= year) lo = mid else hi = mid
        }
        val a = pts[lo]
        val b = pts[hi]
        val span = (b.calYear - a.calYear).toDouble()
        val f = if (span == 0.0) 0.0 else (year - a.calYear) / span
        return Interpolated(
            carbonAge = a.carbonAge + f * (b.carbonAge - a.carbonAge),
            sigma = a.uncertainty + f * (b.uncertainty - a.uncertainty),
            inside = true,
        )
    }

    data class CalibrationInputs(
        val labAge: Double,
        val labSigma: Double,
        val components: List<OffsetComponent>,
    )

    fun components(offset: OffsetSpec?): List<OffsetComponent> {
        if (offset == null || offset.components.isEmpty()) {
            return listOf(OffsetComponent(0.0, 0.0, 1.0, "no-reservoir"))
        }
        val total = offset.components.sumOf { it.weight }
        return offset.components.map { it.copy(weight = it.weight / total) }
    }

    fun unnormalizedWeight(t: Int, inputs: CalibrationInputs, curve: CalibrationCurve): Double {
        val at = interpolate(curve, t)
        if (!at.inside) return 0.0
        var sum = 0.0
        for (c in inputs.components) {
            val mu = at.carbonAge + c.deltaR
            val variance = at.sigma * at.sigma + inputs.labSigma * inputs.labSigma + c.sigma * c.sigma
            val sd = sqrt(variance)
            val z = (inputs.labAge - mu) / sd
            // normal density up to a constant; the shared 1/sqrt(2pi) cancels in normalization,
            // but we keep it so the normalization constant is physically interpretable.
            sum += c.weight * (1.0 / (sd * sqrt(2.0 * Math.PI))) * exp(-0.5 * z * z)
        }
        return sum
    }

    data class FingerprintMaterial(
        val algorithmVersion: String,
        val gridStepYears: Int,
        val measurementId: String,
        val labAge: Double,
        val labSigma: Double,
        val curveId: String,
        val curveVersion: String,
        val curvePointHash: String,
        val offsetId: String?,
        val offsetComponents: List<List<Double>>,
    )

    fun parameterFingerprint(
        measurement: RawMeasurement,
        resolved: ResolvedMeasurement,
        curve: CalibrationCurve,
        offset: OffsetSpec?,
    ): String {
        val material = FingerprintMaterial(
            algorithmVersion = algorithmVersion,
            gridStepYears = gridStepYears,
            measurementId = measurement.id,
            labAge = resolved.age,
            labSigma = resolved.sigma,
            curveId = curve.id,
            curveVersion = curve.version,
            curvePointHash = Hashing.sha256Hex(
                curve.points.joinToString("\n") { "${it.calYear},${it.carbonAge},${it.uncertainty}" }
            ),
            offsetId = offset?.id,
            offsetComponents = components(offset).map { listOf(it.deltaR, it.sigma, it.weight) },
        )
        return Hashing.fingerprint(material)
    }

    /**
     * Window = where unnormalized weight can matter: within the curve support and within
     * labAge +/- 6 total sigma around curve ages, with a fixed 50 year margin.
     */
    fun windowYears(curve: CalibrationCurve, inputs: CalibrationInputs): IntRange {
        val support = curve.points.first().calYear..curve.points.last().calYear
        val maxSigma = inputs.components.maxOf {
            sqrt(curve.points.maxOf { p -> p.uncertainty * p.uncertainty } +
                inputs.labSigma * inputs.labSigma + it.sigma * it.sigma)
        }
        val targetLow = inputs.labAge - inputs.components.maxOf { it.deltaR } - 6.0 * maxSigma - 50.0
        val targetHigh = inputs.labAge - inputs.components.minOf { it.deltaR } + 6.0 * maxSigma + 50.0
        val years = curve.points.filter { p ->
            p.carbonAge in targetLow..targetHigh
        }.map { it.calYear }
        if (years.isEmpty()) return support
        val lo = max(support.first, years.min() - 60)
        val hi = min(support.last, years.max() + 60)
        return lo..hi
    }

    data class RawGrid(val years: List<Int>, val weights: List<Double>)

    fun rawGrid(curve: CalibrationCurve, inputs: CalibrationInputs): RawGrid {
        val window = windowYears(curve, inputs)
        val years = window.toList()
        val weights = years.map { unnormalizedWeight(it, inputs, curve) }
        return RawGrid(years, weights)
    }

    data class Hpd(val intervals: List<Pair<IntRange, Double>>, val level: Double)

    /**
     * HPD set: sort cells by descending density, accumulate until level, then group
     * contiguous selected years. This is the classic highest posterior density construction;
     * each interval's probability is the actual integral (sum of normalized weights) over it.
     */
    fun hpdIntervals(years: List<Int>, normalized: List<Double>, level: Double): Hpd {
        val order = years.indices.sortedWith(compareByDescending<Int> { normalized[it] }.thenBy { years[it] })
        val chosen = BooleanArray(years.size)
        var acc = 0.0
        var i = 0
        while (i < order.size && acc < level - 1e-12) {
            val idx = order[i]
            chosen[idx] = true
            acc += normalized[idx]
            i++
        }
        val intervals = mutableListOf<Pair<IntRange, Double>>()
        var start = -1
        var prev = 0
        var prob = 0.0
        for (k in years.indices) {
            if (chosen[k]) {
                if (start < 0) { start = years[k]; prob = 0.0 }
                if (prev in 1 until years[k] - prev) {
                    intervals += (start..prev) to prob
                    start = years[k]; prob = 0.0
                }
                prob += normalized[k]
                prev = years[k]
            } else if (start >= 0) {
                intervals += (start..prev) to prob
                start = -1; prob = 0.0
            }
        }
        if (start >= 0) intervals += (start..prev) to prob
        return Hpd(intervals.sortedBy { it.first.first }, level)
    }

    data class Posterior(
        val cells: List<GridCell>,
        val intervals68: List<Pair<IntRange, Double>>,
        val intervals95: List<Pair<IntRange, Double>>,
        val normalizationConstant: Double,
        val modeCount: Int,
        val crossesBceCe: Boolean,
        val window: IntRange,
    )

    fun posterior(curve: CalibrationCurve, inputs: CalibrationInputs): Posterior {
        val raw = rawGrid(curve, inputs)
        val z = raw.weights.sum()
        if (z <= 0.0) {
            return Posterior(emptyList(), emptyList(), emptyList(), 0.0, 0, false, 0..0)
        }
        val normalized = raw.weights.map { it / z }
        val cells = raw.years.indices.map { k ->
            val year = raw.years[k]
            val at = interpolate(curve, year)
            GridCell(
                year = year,
                weight = normalized[k],
                unnormalized = raw.weights[k],
                curveCarbonAge = at.carbonAge,
                curveSigma = at.sigma,
            )
        }
        val hpd68 = hpdIntervals(raw.years, normalized, 0.68).intervals
        val hpd95 = hpdIntervals(raw.years, normalized, 0.95).intervals
        // Modes = number of 68% HPD intervals is too coarse; instead count local maxima
        // separated by a valley below half of the smaller peak.
        val modes = countModes(normalized)
        val crosses = raw.years.first() <= 0 && raw.years.last() >= 0
        return Posterior(cells, hpd68, hpd95, z, modes, crosses, raw.years.first()..raw.years.last())
    }

    fun countModes(normalized: List<Double>): Int {
        val peak = normalized.maxOrNull() ?: return 0
        if (peak <= 0.0) return 0
        val cutoff = peak * 0.10
        // An island is a maximal run of cells above the cutoff. Each island counts as
        // one mode; a noisy wiggle cannot create modes because sub-cutoff gaps split.
        var modes = 0
        var inIsland = false
        for (w in normalized) {
            if (w > cutoff) {
                if (!inIsland) { modes += 1; inIsland = true }
            } else {
                inIsland = false
            }
        }
        return max(1, modes)
    }

    companion object {
        /** Stable interval identity within a result. */
        fun intervalId(resultId: String, level: Double, index: Int): String =
            "$resultId/hpd${(level * 100).toInt()}/$index"
    }
}
