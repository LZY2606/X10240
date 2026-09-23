package workbench

import java.security.MessageDigest
import kotlin.math.exp
import kotlin.math.sqrt

/** Calibration algorithm version. Bumped when the math changes; stored on every derived result. */
const val ALGO_VERSION = "cal-1.0.0"
const val GRID_STEP = 1.0

/** One knot of a calibration curve: calendar year (cal BP) -> radiocarbon age (14C yr BP) with 1-sigma. */
data class Knot(val calBP: Double, val c14: Double, val sigma: Double)

data class CurveKnots(val knots: List<Knot>) {
    init {
        require(knots.size >= 2) { "curve needs at least 2 knots" }
        require(knots.zipWithNext().all { (a, b) -> b.calBP > a.calBP }) { "knots must be strictly increasing in calBP" }
    }
    val minT: Double get() = knots.first().calBP
    val maxT: Double get() = knots.last().calBP
}

data class CurvePoint(val c14: Double, val sigma: Double, val clamped: Boolean)

/**
 * Linear interpolation between knots. Outside the knot range the endpoint value is held
 * (boundary clamp) and flagged, so edge behaviour is explicit and reproducible.
 * Interpolation at a knot returns the knot value exactly (boundary interpolation is continuous).
 */
fun interpolateCurve(curve: CurveKnots, t: Double): CurvePoint {
    val ks = curve.knots
    if (t <= curve.minT) return CurvePoint(ks.first().c14, ks.first().sigma, clamped = t < curve.minT)
    if (t >= curve.maxT) return CurvePoint(ks.last().c14, ks.last().sigma, clamped = t > curve.maxT)
    var lo = 0
    var hi = ks.size - 1
    while (hi - lo > 1) {
        val mid = (lo + hi) / 2
        if (ks[mid].calBP <= t) lo = mid else hi = mid
    }
    val a = ks[lo]
    val b = ks[hi]
    val f = (t - a.calBP) / (b.calBP - a.calBP)
    return CurvePoint(a.c14 + f * (b.c14 - a.c14), a.sigma + f * (b.sigma - a.sigma), clamped = false)
}

/** A calibrated probability distribution over cal BP, on a fixed 1-year grid. Never collapsed to a mean. */
data class CalDistribution(
    val startBP: Double,
    val step: Double,
    val probs: DoubleArray,      // normalized: sums to 1
    val normConstant: Double,    // sum of raw weights before normalization (reproducibility evidence)
    val clampedLow: Boolean,     // likelihood mass touched the young edge of the curve
    val clampedHigh: Boolean,    // likelihood mass touched the old edge of the curve
) {
    fun tAt(i: Int): Double = startBP + i * step
    val size: Int get() = probs.size
    /** Cumulative probability mass in [fromBP, toBP] (inclusive, linear index span). */
    fun massBetween(fromBP: Double, toBP: Double): Double {
        var s = 0.0
        for (i in probs.indices) {
            val t = tAt(i)
            if (t >= fromBP && t <= toBP) s += probs[i]
        }
        return s
    }
    fun quantile(q: Double): Double {
        var acc = 0.0
        for (i in probs.indices) {
            acc += probs[i]
            if (acc >= q) return tAt(i)
        }
        return tAt(probs.size - 1)
    }
}

data class CalibrationParams(
    val c14Age: Double,          // measured radiocarbon age, 14C yr BP
    val c14Sigma: Double,        // lab 1-sigma
    val offsetMean: Double,      // reservoir offset DeltaR mean, 14C yr (subtracted from measurement)
    val offsetSigma: Double,     // reservoir offset 1-sigma (0 for a point offset)
)

/**
 * Calibrate a radiocarbon determination against a curve under a reservoir-offset assumption.
 * Likelihood at calendar year t:  w(t) = exp(-0.5 * ((r - dR - c(t))^2) / (sm^2 + sdR^2 + sc(t)^2))
 * then normalized over the fixed grid. Deterministic for a fixed curve version + params.
 */
fun calibrate(curve: CurveKnots, p: CalibrationParams): CalDistribution {
    val n = ((curve.maxT - curve.minT) / GRID_STEP).toInt() + 1
    val w = DoubleArray(n)
    var clampedLow = false
    var clampedHigh = false
    val varBase = p.c14Sigma * p.c14Sigma + p.offsetSigma * p.offsetSigma
    val target = p.c14Age - p.offsetMean
    for (i in 0 until n) {
        val t = curve.minT + i * GRID_STEP
        val cp = interpolateCurve(curve, t)
        if (cp.clamped) {
            if (t <= curve.minT) clampedLow = true else clampedHigh = true
        }
        val v = varBase + cp.sigma * cp.sigma
        val d = target - cp.c14
        w[i] = exp(-0.5 * d * d / v)
    }
    val norm = w.sum()
    val probs = DoubleArray(n) { w[it] / norm }
    // edge flags: report clamp if the outermost 5% of mass sits in the first/last grid cell region
    return CalDistribution(curve.minT, GRID_STEP, probs, norm, clampedLow, clampedHigh)
}

/** One contiguous calendar interval of a highest-posterior-density region. */
data class CalInterval(
    val fromBP: Double,   // older edge (larger cal BP)
    val toBP: Double,     // younger edge (smaller cal BP)
    val probability: Double,
    val level: Double,    // 0.682 or 0.954
    val gridFrom: Int,
    val gridTo: Int,
    val rawWeightSum: Double, // integration evidence: sum of raw weights in this interval
)

/**
 * Highest posterior density intervals: sort grid cells by density, accumulate until `level`
 * of total mass is covered, then group the covered cells into contiguous runs.
 * A multimodal distribution yields multiple intervals; they are never merged into a mean.
 */
fun hpdIntervals(dist: CalDistribution, level: Double): List<CalInterval> {
    val order = dist.probs.indices.sortedByDescending { dist.probs[it] }
    val covered = BooleanArray(dist.size)
    var acc = 0.0
    for (i in order) {
        if (acc >= level) break
        covered[i] = true
        acc += dist.probs[i]
    }
    val out = mutableListOf<CalInterval>()
    var i = 0
    while (i < dist.size) {
        if (covered[i]) {
            var j = i
            var mass = 0.0
            while (j < dist.size && covered[j]) { mass += dist.probs[j]; j++ }
            val from = dist.tAt(j - 1)
            val to = dist.tAt(i)
            out.add(
                CalInterval(
                    fromBP = maxOf(from, to),
                    toBP = minOf(from, to),
                    probability = mass,
                    level = level,
                    gridFrom = i,
                    gridTo = j - 1,
                    rawWeightSum = mass * dist.normConstant,
                )
            )
            i = j
        } else i++
    }
    return out.sortedBy { it.fromBP }
}

val HPD_LEVELS = listOf(0.682, 0.954)

/** Parameter fingerprint: stable hash of algo version + every direct input, rounded canonically. */
fun fingerprint(algoVersion: String, curveId: String, params: CalibrationParams, measurementId: String, offsetId: String): String {
    fun r(x: Double) = "%.6f".format(x)
    val canon = listOf(
        algoVersion, curveId, measurementId, offsetId,
        r(params.c14Age), r(params.c14Sigma), r(params.offsetMean), r(params.offsetSigma),
    ).joinToString("|")
    val md = MessageDigest.getInstance("SHA-256")
    return md.digest(canon.toByteArray()).joinToString("") { "%02x".format(it) }.take(24)
}

/**
 * Calendar BP -> historical era label. No year zero:
 * calBP 1949 -> 2 CE ... calBP 1950 -> 1 BCE, calBP 1951 -> 2 BCE.
 */
fun eraLabel(calBP: Double): String {
    val ce = 1950.0 - calBP
    // ce < 1 -> BCE year = 1 - ce (astronomical year numbering, no year 0)
    return if (ce >= 1.0) "${ce.toInt()} CE" else "${(1.0 - ce).toInt()} BCE"
}

/** Combined sigma for measurement + offset distribution. */
fun combinedSigma(c14Sigma: Double, offsetSigma: Double): Double =
    sqrt(c14Sigma * c14Sigma + offsetSigma * offsetSigma)
