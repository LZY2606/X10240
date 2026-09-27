package workbench

import kotlinx.serialization.Serializable
import java.security.MessageDigest
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sqrt

const val ALGORITHM_VERSION = "grid-calib/1.0"
const val GRID_STEP = 1.0

@Serializable
data class CurvePoint(val calBP: Double, val ageBP: Double, val sigma: Double)

@Serializable
data class CalibrationCurve(val id: String, val version: Int, val points: List<CurvePoint>) {
    val minCalBP: Double get() = points.first().calBP
    val maxCalBP: Double get() = points.last().calBP

    /** Linear interpolation between bracketing points; clamps at range boundary (boundary interpolation). */
    fun interp(calBP: Double): Pair<Double, Double> {
        if (calBP <= minCalBP) return points.first().ageBP to points.first().sigma
        if (calBP >= maxCalBP) return points.last().ageBP to points.last().sigma
        var lo = 0
        var hi = points.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) / 2
            if (points[mid].calBP <= calBP) lo = mid else hi = mid
        }
        val a = points[lo]
        val b = points[hi]
        val t = (calBP - a.calBP) / (b.calBP - a.calBP)
        return (a.ageBP + t * (b.ageBP - a.ageBP)) to (a.sigma + t * (b.sigma - a.sigma))
    }
}

@Serializable
data class Peak(val calBP: Double, val density: Double, val calendarLabel: String)

/** A contiguous calendar interval; olderCalBP > youngerCalBP. */
@Serializable
data class CalInterval(
    val olderCalBP: Double,
    val youngerCalBP: Double,
    val mass: Double,
    val label: String,
)

@Serializable
data class DensityResult(
    val gridStart: Double,
    val step: Double,
    val values: List<Double>,
    val peaks: List<Peak>,
    val intervals68: List<CalInterval>,
    val intervals95: List<CalInterval>,
    val truncatedAtBoundary: Boolean,
    val fingerprint: String,
    val algorithm: String = ALGORITHM_VERSION,
) {
    fun calBPAt(index: Int): Double = gridStart + index * step

    /** Deterministic median of the calibrated grid distribution. */
    fun medianCalBP(): Double {
        var acc = 0.0
        for (i in values.indices) {
            acc += values[i]
            if (acc >= 0.5) return calBPAt(i)
        }
        return calBPAt(values.size - 1)
    }
}

fun sha256(text: String): String =
    MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

/** Astronomical year numbering: year 0 = 1 BCE, year -1 = 2 BCE, year 1 = 1 CE. */
fun calBPToAstronomicalYear(calBP: Double): Int = (1950.0 - calBP).let { if (it >= 0) ceil(it).toInt() else floor(it).toInt() }

fun calendarLabel(calBP: Double): String {
    val y = calBPToAstronomicalYear(calBP)
    return if (y >= 1) "CE $y" else "BCE ${1 - y}"
}

fun intervalLabel(olderCalBP: Double, youngerCalBP: Double): String =
    "${calendarLabel(olderCalBP)} – ${calendarLabel(youngerCalBP)}"

/**
 * Deterministic grid calibration. The measured radiocarbon age (minus reservoir offset mean)
 * is compared against the interpolated curve; variance combines measurement std, offset std
 * and curve sigma. The density is normalised over the fixed curve range so results are
 * reproducible for a fixed curve version; mass falling outside the curve range is reported
 * via [DensityResult.truncatedAtBoundary] instead of being silently dropped.
 */
fun calibrate(
    ageBP: Double,
    std: Double,
    offsetMean: Double,
    offsetStd: Double,
    curve: CalibrationCurve,
): DensityResult {
    val n = ((curve.maxCalBP - curve.minCalBP) / GRID_STEP).toInt() + 1
    val values = DoubleArray(n)
    val measured = ageBP - offsetMean
    val baseVar = std * std + offsetStd * offsetStd
    for (i in 0 until n) {
        val t = curve.minCalBP + i * GRID_STEP
        val (mu, sigma) = curve.interp(t)
        val v = baseVar + sigma * sigma
        val z = (measured - mu) * (measured - mu) / v
        values[i] = exp(-0.5 * z) / sqrt(2.0 * Math.PI * v)
    }
    // Boundary check: does the un-truncated model put meaningful mass outside the curve range?
    val (muLo, sigLo) = curve.interp(curve.minCalBP)
    val (muHi, sigHi) = curve.interp(curve.maxCalBP)
    val varLo = baseVar + sigLo * sigLo
    val varHi = baseVar + sigHi * sigHi
    val truncated = (measured - muLo) < -2.0 * sqrt(varLo) || (measured - muHi) > 2.0 * sqrt(varHi)
    var sum = 0.0
    for (v in values) sum += v
    val norm = List(n) { values[it] / sum }

    val maxV = norm.max()
    val peaks = mutableListOf<Peak>()
    for (i in 0 until n) {
        val v = norm[i]
        val left = if (i == 0) -1.0 else norm[i - 1]
        val right = if (i == n - 1) -1.0 else norm[i + 1]
        if (v >= left && v > right && v > 0.03 * maxV) {
            peaks.add(Peak(curve.minCalBP + i * GRID_STEP, v, calendarLabel(curve.minCalBP + i * GRID_STEP)))
        }
    }
    val result = DensityResult(
        gridStart = curve.minCalBP,
        step = GRID_STEP,
        values = norm,
        peaks = peaks,
        intervals68 = hpdIntervals(norm, curve.minCalBP, GRID_STEP, 0.6827),
        intervals95 = hpdIntervals(norm, curve.minCalBP, GRID_STEP, 0.9545),
        truncatedAtBoundary = truncated,
        fingerprint = sha256(
            listOf(
                ALGORITHM_VERSION, curve.id, curve.version.toString(),
                ageBP.toString(), std.toString(), offsetMean.toString(), offsetStd.toString(),
                GRID_STEP.toString(),
            ).joinToString("|"),
        ),
    )
    return result
}

/** Highest-posterior-density intervals: threshold chosen so mass above it equals [targetMass]. */
fun hpdIntervals(values: List<Double>, gridStart: Double, step: Double, targetMass: Double): List<CalInterval> {
    val maxV = values.max()
    var lo = 0.0
    var hi = maxV
    repeat(60) {
        val mid = (lo + hi) / 2
        var mass = 0.0
        for (v in values) if (v >= mid) mass += v
        if (mass > targetMass) lo = mid else hi = mid
    }
    val threshold = (lo + hi) / 2
    val intervals = mutableListOf<CalInterval>()
    var i = 0
    while (i < values.size) {
        if (values[i] >= threshold) {
            var j = i
            var mass = 0.0
            while (j < values.size && values[j] >= threshold) {
                mass += values[j]
                j++
            }
            val older = gridStart + (j - 1) * step
            val younger = gridStart + i * step
            intervals.add(CalInterval(max(older, younger), minOf(older, younger), mass, intervalLabel(max(older, younger), minOf(older, younger))))
            i = j
        } else {
            i++
        }
    }
    return intervals
}

@Serializable
data class EvidenceSegment(val fromCalBP: Double, val toCalBP: Double, val mass: Double)

@Serializable
data class IntegrationEvidence(
    val interval: CalInterval,
    val level: String,
    val gridStep: Double,
    val pointCount: Int,
    val thresholdMass: Double,
    val segments: List<EvidenceSegment>,
    val fingerprint: String,
    val algorithm: String,
)

/** Expand an interval into per-segment partial sums of the stored normalised density. */
fun integrationEvidence(result: DensityResult, interval: CalInterval, level: String, segments: Int = 10): IntegrationEvidence {
    val idxLo = ((interval.youngerCalBP - result.gridStart) / result.step).toInt().coerceIn(0, result.values.size - 1)
    val idxHi = ((interval.olderCalBP - result.gridStart) / result.step).toInt().coerceIn(0, result.values.size - 1)
    val count = idxHi - idxLo + 1
    val segs = mutableListOf<EvidenceSegment>()
    val per = max(1, count / segments)
    var i = idxLo
    while (i <= idxHi) {
        val j = minOf(idxHi, i + per - 1)
        var mass = 0.0
        for (k in i..j) mass += result.values[k]
        segs.add(EvidenceSegment(result.calBPAt(j), result.calBPAt(i), mass))
        i = j + 1
    }
    return IntegrationEvidence(
        interval = interval,
        level = level,
        gridStep = result.step,
        pointCount = count,
        thresholdMass = interval.mass,
        segments = segs,
        fingerprint = result.fingerprint,
        algorithm = result.algorithm,
    )
}
