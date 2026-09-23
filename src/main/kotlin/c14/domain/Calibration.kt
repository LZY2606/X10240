package c14.domain

import kotlinx.serialization.Serializable
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

const val ALGORITHM_VERSION = "cal-grid-normal-v1"

/** Reservoir offset assumption (e.g. marine reservoir Delta-R), in C-14 years BP. */
@Serializable
data class ReservoirAssumption(
    val deltaRMeanBP: Double,
    val deltaRSigmaBP: Double,
    val distributionKind: String = "normal",
    val note: String = "",
) {
    companion object {
        val NONE = ReservoirAssumption(0.0, 0.0, "deterministic-zero", "no reservoir offset")
        val MARINE_DEFAULT = ReservoirAssumption(180.0, 40.0, "normal", "regional marine reservoir Delta-R")
    }
}

@Serializable
data class CalibrationRequest(
    val labAgeBP: Double,
    val labSigmaBP: Double,
    val curve: CalCurve,
    val reservoir: ReservoirAssumption = ReservoirAssumption.NONE,
    val gridStepYears: Int = 1,
) {
    init {
        require(labSigmaBP > 0.0) { "lab sigma must be positive" }
        require(gridStepYears >= 1)
    }
}

@Serializable
data class CalibPeak(val calBP: Int, val label: String, val density: Double)

@Serializable
data class IntervalEvidence(
    val intervalId: String,
    val calBPStartInclusive: Int,
    val calBPEndInclusive: Int,
    val humanRange: String,
    val probability: Double,
    val rawLikelihoodSum: Double,
    val gridStepYears: Int,
    val binCount: Int,
    val crossesBceCe: Boolean,
    val curveControlPoints: List<CurvePoint>,
)

@Serializable
data class CalibrationResult(
    val curveId: String,
    val curveVersion: Int,
    val gridStartBP: Int,
    val gridEndBP: Int,
    val gridStepYears: Int,
    /** Per-bin probability mass at each grid year, index-aligned with [grid]. */
    val binMass: List<Double>,
    /** Normalization constant: integral of the raw likelihood over the grid. */
    val normalizationConstant: Double,
    val rawLikelihoodIntegral: Double,
    val reservoir: ReservoirAssumption,
    val summaryMeanBP: Double,
    val medianBP: Int,
    val peaks: List<CalibPeak>,
    val intervals68: List<IntervalEvidence>,
    val intervals95: List<IntervalEvidence>,
    val multiModal: Boolean,
    val lowBoundaryClamped: Boolean,
    val highBoundaryClamped: Boolean,
    val lowEdgeMass: Double,
    val highEdgeMass: Double,
    val crossesBceCe: Boolean,
    val diagnostics: List<String>,
    val algorithmVersion: String = ALGORITHM_VERSION,
) {
    val grid: IntRange get() = gridStartBP..gridEndBP step gridStepYears
    fun densityAt(calBP: Int): Double =
        if (calBP in gridStartBP..gridEndBP)
            binMass[(calBP - gridStartBP) / gridStepYears] / gridStepYears
        else 0.0
}

/**
 * Deterministic grid calibration.
 *
 * L(t) = Normal(labAge - meanCurve(t) - DeltaR ; 0,
 *               sqrt(labSigma^2 + sigmaCurve(t)^2 + deltaRSigma^2))
 *
 * P(bin) = L(t)*dt / Z with Z = integral L dt, recorded explicitly so the same
 * (measurement, curve version, reservoir distribution, grid) always reproduces the
 * same multi-peak posterior. No mean year replaces the distribution.
 */
object Calibrator {
    private const val SQRT_2PI = 2.5066282746310002

    fun calibrate(req: CalibrationRequest): CalibrationResult {
        val c = req.curve
        val step = req.gridStepYears
        val years = (c.tMin..c.tMax step step).toList()
        val raw = DoubleArray(years.size)
        var z = 0.0
        for ((i, t) in years.withIndex()) {
            val sig = sqrt(
                req.labSigmaBP * req.labSigmaBP +
                    c.sigmaAt(t.toDouble()).let { it * it } +
                    req.reservoir.deltaRSigmaBP * req.reservoir.deltaRSigmaBP
            )
            val diff = req.labAgeBP - c.meanAt(t.toDouble()) - req.reservoir.deltaRMeanBP
            // Work in log space to keep edge (far-from-mean) bins numerically stable.
            val ll = -0.5 * (diff / sig) * (diff / sig) - ln(sig)
            val w = exp(ll) / SQRT_2PI
            raw[i] = w
            z += w * step
        }
        val diagnostics = mutableListOf<String>()
        if (z <= 1e-30) {
            diagnostics += "NO_COVERAGE: curve range gives negligible likelihood for measurement"
        }
        val mass = DoubleArray(years.size) { if (z > 0.0) raw[it] * step / z else 0.0 }

        val intervals68 = hdrIntervals(years, mass, raw, step, 0.68, c)
        val intervals95 = hdrIntervals(years, mass, raw, step, 0.95, c)
        val peaks = peaks(years, mass, step)

        val edgeBins = (5.0 / step).toInt().coerceAtLeast(1)
        val lowEdge = (0 until edgeBins.coerceAtMost(mass.size)).sumOf { mass[it] }
        val lowClamp = lowEdge >= 0.5
        val tailHi = (mass.size - edgeBins.coerceAtMost(mass.size) until mass.size).sumOf { mass[it] }
        val highClamp = tailHi >= 0.5
        if (lowClamp) diagnostics += "BOUNDARY_CLAMP: >=50% posterior mass against youngest curve edge (${c.tMin} BP)"
        if (highClamp) diagnostics += "BOUNDARY_CLAMP: >=50% posterior mass against oldest curve edge (${c.tMax} BP)"

        val cross = intervals95.any { it.crossesBceCe }
        if (cross) diagnostics += "BCE_CE_CROSSING: a 95% interval straddles 1 BCE / 1 CE"
        if (intervals95.size >= 2) diagnostics += "MULTI_PEAK: ${intervals95.size} separated 95% HDR calendar intervals"
        if (req.reservoir.deltaRSigmaBP > 0.0)
            diagnostics += "RESERVOIR_OFFSET_DISTRIBUTION: Delta-R ${req.reservoir.deltaRMeanBP} \u00b1 ${req.reservoir.deltaRSigmaBP} C14 yr BP (${req.reservoir.distributionKind})"

        val mean = years.indices.sumOf { years[it] * mass[it] }
        var acc = 0.0
        var median = years.last()
        for (i in mass.indices) {
            acc += mass[i]
            if (acc >= 0.5) { median = years[i]; break }
        }

        return CalibrationResult(
            curveId = c.id,
            curveVersion = c.version,
            gridStartBP = c.tMin,
            gridEndBP = c.tMax,
            gridStepYears = step,
            binMass = mass.toList(),
            normalizationConstant = z,
            rawLikelihoodIntegral = z,
            reservoir = req.reservoir,
            summaryMeanBP = mean,
            medianBP = median,
            peaks = peaks,
            intervals68 = intervals68,
            intervals95 = intervals95,
            multiModal = intervals95.size >= 2 || peaks.size >= 2,
            lowBoundaryClamped = lowClamp,
            highBoundaryClamped = highClamp,
            lowEdgeMass = lowEdge,
            highEdgeMass = tailHi,
            crossesBceCe = cross,
            diagnostics = diagnostics,
        )
    }

    private fun hdrIntervals(
        years: List<Int>,
        mass: DoubleArray,
        raw: DoubleArray,
        step: Int,
        level: Double,
        curve: CalCurve,
    ): List<IntervalEvidence> {
        val order = mass.indices.sortedWith(compareByDescending<Int> { mass[it] })
        var acc = 0.0
        var cutoff = 0.0
        for (i in order) {
            acc += mass[i]
            cutoff = mass[i]
            if (acc >= level) break
        }
        val eps = 1e-15
        val inside = BooleanArray(mass.size) { mass[it] >= cutoff - eps }
        val ranges = mutableListOf<IntRange>()
        var start = -1
        for (i in inside.indices) {
            if (inside[i] && start < 0) start = i
            if ((!inside[i] || i == inside.size - 1) && start >= 0) {
                val end = if (inside[i]) i else i - 1
                ranges += start..end
                start = -1
            }
        }
        return ranges.mapIndexed { k, r ->
            val p = (r.first..r.last).sumOf { mass[it] }
            val rs = (r.first..r.last).sumOf { raw[it] * step }
            val t0 = years[r.first]
            val t1 = years[r.last]
            IntervalEvidence(
                intervalId = "hdr-%02d-%d".format((level * 100).toInt(), k + 1),
                calBPStartInclusive = t0,
                calBPEndInclusive = t1,
                humanRange = Calendars.formatRange(t0.toDouble(), t1.toDouble()),
                probability = p,
                rawLikelihoodSum = rs,
                gridStepYears = step,
                binCount = r.last - r.first + 1,
                crossesBceCe = Calendars.crossesBceCe(t0.toDouble(), t1.toDouble()),
                curveControlPoints = curve.points.filter { it.calBP in t0..t1 },
            )
        }
    }

    private fun peaks(years: List<Int>, mass: DoubleArray, step: Int): List<CalibPeak> {
        if (mass.isEmpty()) return emptyList()
        val result = mutableListOf<CalibPeak>()
        val total = mass.sum()
        for (i in 1 until mass.size - 1) {
            if (mass[i] > mass[i - 1] && mass[i] >= mass[i + 1] && mass[i] / total > 0.0005 / step) {
                result += CalibPeak(years[i], Calendars.format(years[i].toDouble()), mass[i] / step)
            }
        }
        return result
    }
}
