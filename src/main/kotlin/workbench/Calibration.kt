package workbench

import kotlin.math.*

const val ALGO_VERSION = "calib-1.3.0"

fun normalPdf(x: Double, mean: Double, sigma: Double): Double {
    val z = (x - mean) / sigma
    return exp(-0.5 * z * z) / (sigma * sqrt(2.0 * PI))
}

/** Linear interpolation on a calibration curve. Boundary handling: clamp to the
 *  nearest endpoint and report a diagnostic (never silently invent curve data). */
class Curve(val payload: CurvePayload) {
    val points = payload.points.sortedBy { it.calBp }

    fun interpolate(calBp: Int): Pair<CurvePoint, Boolean> {
        val first = points.first(); val last = points.last()
        if (calBp <= first.calBp) return first to (calBp < first.calBp)
        if (calBp >= last.calBp) return last to (calBp > last.calBp)
        var lo = 0; var hi = points.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) / 2
            if (points[mid].calBp <= calBp) lo = mid else hi = mid
        }
        val a = points[lo]; val b = points[hi]
        val t = (calBp - a.calBp).toDouble() / (b.calBp - a.calBp)
        return CurvePoint(
            calBp,
            a.c14Age + t * (b.c14Age - a.c14Age),
            a.sigma + t * (b.sigma - a.sigma)
        ) to false
    }
}

data class CalibrationResult(
    val gridStartBp: Int, val gridEndBp: Int, val gridStep: Int,
    val densities: List<Double>,
    val peaks: List<CalPeak>,
    val intervals: List<CalInterval>,
    val diagnostics: List<String>
)

object Calibration {

    fun fingerprint(parts: List<String?>): String {
        var h = 1469598103934665603L
        for (p in parts) {
            val s = p ?: "<null>"
            for (c in s) { h = h xor c.code.toLong(); h = h * 1099511628211L }
            h = h xor 31; h = h * 1099511628211L
        }
        return h.toString(16).padStart(16, '0')
    }

    /**
     * Calibrate a radiocarbon measurement against a curve version.
     * Deterministic for a fixed (curve version, measurement, offset, grid) tuple.
     * The result is a full normalized density grid: multi-modal structure is kept,
     * never collapsed to a single mean year.
     */
    fun calibrate(
        measurement: MeasurementPayload,
        curve: Curve,
        reservoir: ReservoirPayload?,
        gridStartBp: Int,
        gridEndBp: Int,
        gridStep: Int = 1
    ): CalibrationResult {
        val diagnostics = mutableListOf<String>()
        val effMean = measurement.ageBp - (reservoir?.deltaR ?: 0.0)
        val effSigma = sqrt(
            measurement.sigma * measurement.sigma +
                (reservoir?.deltaRSigma ?: 0.0).pow(2)
        )
        val n = (gridEndBp - gridStartBp) / gridStep + 1
        val raw = DoubleArray(n)
        var boundaryHit = false
        for (i in 0 until n) {
            val calBp = gridStartBp + i * gridStep
            val (cp, extrapolated) = curve.interpolate(calBp)
            if (extrapolated) boundaryHit = true
            val sigma = sqrt(effSigma * effSigma + cp.sigma * cp.sigma)
            raw[i] = normalPdf(effMean, cp.c14Age, sigma)
        }
        if (boundaryHit) diagnostics += "BOUNDARY_INTERPOLATION: grid extends beyond curve range; endpoint values clamped"
        val sum = raw.sum()
        require(sum > 0.0) { "calibration likelihood sums to zero on grid" }
        val densities = raw.map { it / sum }

        val peaks = findPeaks(densities, gridStartBp, gridStep)
        if (peaks.size > 1) diagnostics += "MULTI_MODAL: ${peaks.size} distinct modes retained (not collapsed to a mean)"
        val intervals = hpdIntervals(densities, gridStartBp, gridStep, listOf(0.682, 0.954))
        for (iv in intervals) {
            if (Era.crossesEraBoundary(iv.fromBp, iv.toBp))
                diagnostics += "BCE_CE_CROSSING: interval ${iv.fromBp}-${iv.toBp} calBP spans the era boundary"
        }
        return CalibrationResult(gridStartBp, gridEndBp, gridStep, densities, peaks, intervals, diagnostics.distinct())
    }

    fun findPeaks(densities: List<Double>, gridStartBp: Int, gridStep: Int): List<CalPeak> {
        val max = densities.max()
        val peaks = mutableListOf<CalPeak>()
        for (i in densities.indices) {
            val v = densities[i]
            if (v < max * 0.05) continue
            val left = if (i > 0) densities[i - 1] else -1.0
            val right = if (i < densities.size - 1) densities[i + 1] else -1.0
            if (v >= left && v >= right && (v > left || v > right)) {
                // merge plateaus: skip if previous peak is adjacent with equal height
                if (peaks.isNotEmpty()) {
                    val prev = peaks.last()
                    val prevIdx = (prev.calBp - gridStartBp) / gridStep
                    if (prevIdx == i - 1 && prev.height == v) continue
                }
                peaks += CalPeak(gridStartBp + i * gridStep, v)
            }
        }
        // keep only peaks separated by a valley below half of the lower peak
        val merged = mutableListOf<CalPeak>()
        for (p in peaks) {
            val last = merged.lastOrNull()
            if (last != null) {
                val li = (last.calBp - gridStartBp) / gridStep
                val pi = (p.calBp - gridStartBp) / gridStep
                val valley = (li..pi).minOf { densities[it] }
                if (valley > min(last.height, p.height) * 0.5) {
                    if (p.height > last.height) { merged.removeAt(merged.size - 1); merged += p }
                    continue
                }
            }
            merged += p
        }
        return merged
    }

    /** Highest-posterior-density intervals: grid cells ranked by density, thresholded
     *  until the requested probability mass is covered; contiguous runs become intervals. */
    fun hpdIntervals(
        densities: List<Double>, gridStartBp: Int, gridStep: Int, levels: List<Double>
    ): List<CalInterval> {
        val order = densities.indices.sortedByDescending { densities[it] }
        val out = mutableListOf<CalInterval>()
        var idx = 0
        for ((li, level) in levels.withIndex()) {
            val covered = BooleanArray(densities.size)
            var acc = 0.0
            while (idx < order.size && acc < level) {
                covered[order[idx]] = true
                acc += densities[order[idx]]
                idx++
            }
            var runStart = -1
            var k = 0
            for (i in covered.indices + covered.size) {
                val on = i < covered.size && covered[i]
                if (on && runStart < 0) runStart = i
                if (!on && runStart >= 0) {
                    val from = gridStartBp + runStart * gridStep
                    val to = gridStartBp + (i - 1) * gridStep
                    val mass = (runStart until i).sumOf { densities[it] }
                    out += CalInterval("hpd-${(level * 1000).toInt()}-$k", level, from, to, mass)
                    runStart = -1; k++
                }
            }
        }
        return out
    }

    /** Integration evidence for one interval: chunked partial sums that add up to the
     *  interval probability. Fully determined by the stored density grid. */
    fun integrationEvidence(
        payload: CalibrationPayload, interval: CalInterval, chunkYears: Int = 10
    ): List<EvidenceSegment> {
        val segments = mutableListOf<EvidenceSegment>()
        var cumulative = 0.0
        var bp = interval.fromBp
        var segIdx = 0
        while (bp <= interval.toBp) {
            val end = minOf(bp + chunkYears - 1, interval.toBp)
            var mass = 0.0
            for (y in bp..end step payload.gridStep) {
                val i = (y - payload.gridStartBp) / payload.gridStep
                if (i in payload.densities.indices) mass += payload.densities[i]
            }
            cumulative += mass
            segments += EvidenceSegment("${interval.id}/seg-$segIdx", bp, end, mass, cumulative)
            bp = end + 1
            segIdx++
        }
        return segments
    }
}

data class EvidenceSegment(
    val id: String, val fromBp: Int, val toBp: Int, val mass: Double, val cumulative: Double
)
