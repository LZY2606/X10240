package gsb

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Deterministic radiocarbon calibration, algorithm version [ALGO_VERSION].
 * Fixed-version reproducibility: same inputs always produce the same fingerprint
 * and the same normalized multi-modal density; nothing is collapsed to a mean year.
 */
object Calibration {
    const val ALGO_VERSION = "calib-1.0"
    const val GRID_STEP = 5.0

    /** Linear interpolation inside the curve domain; clamped to the endpoint outside it. */
    fun interpolate(curve: List<CurvePoint>, calBp: Double): Pair<Double, Double> {
        require(curve.isNotEmpty()) { "curve has no points" }
        if (curve.size == 1) return curve[0].c14Age to curve[0].sigma
        if (calBp <= curve.first().calBp) return curve.first().c14Age to curve.first().sigma
        if (calBp >= curve.last().calBp) return curve.last().c14Age to curve.last().sigma
        var lo = 0
        var hi = curve.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) / 2
            if (curve[mid].calBp <= calBp) lo = mid else hi = mid
        }
        val a = curve[lo]
        val b = curve[hi]
        val f = (calBp - a.calBp) / (b.calBp - a.calBp)
        return (a.c14Age + f * (b.c14Age - a.c14Age)) to (a.sigma + f * (b.sigma - a.sigma))
    }

    /** Era label under the BCE/CE convention with no year zero. */
    fun eraLabel(calBp: Double, presentYear: Int = 1950): String {
        val yearCe = (presentYear - calBp).roundToInt()
        return if (yearCe >= 1) "CE $yearCe" else "BCE ${1 - yearCe}"
    }

    fun normalPdf(x: Double, mu: Double, sigma: Double): Double {
        val z = (x - mu) / sigma
        return exp(-0.5 * z * z) / (sigma * sqrt(2.0 * PI))
    }

    fun fingerprintOf(parts: List<String>): String = sha256(parts.joinToString("|"))

    fun calibrate(
        meas: MeasurementData,
        measurementSourceId: String,
        offset: OffsetData?,
        curve: CurveData,
        era: EraSystemData
    ): CalibrationResult {
        val deltaR = offset?.deltaR ?: 0.0
        val deltaRSigma = offset?.deltaRSigma ?: 0.0
        val mu = meas.bpAge - deltaR
        val sigma = sqrt(meas.sigma * meas.sigma + deltaRSigma * deltaRSigma)
        val from = curve.points.first().calBp
        val to = curve.points.last().calBp
        val diagnostics = mutableListOf<String>()

        val mapped = inverseMap(curve, mu)
        if (mapped == null || mapped < from || mapped > to) {
            diagnostics.add(
                "measurement mean ${meas.bpAge} BP maps outside curve domain [$from, $to] cal BP; " +
                    "boundary clamping of interpolation applies"
            )
        }
        if (offset != null) {
            diagnostics.add("reservoir offset ${offset.label}: dR=$deltaR ± $deltaRSigma convolved into effective sigma")
        }

        val rawPoints = ArrayList<DensityPoint>()
        var t = from
        while (t <= to + 1e-9) {
            val (cm, cs) = interpolate(curve.points, t)
            val combined = sqrt(sigma * sigma + cs * cs)
            rawPoints.add(DensityPoint(t, normalPdf(mu, cm, combined)))
            t += GRID_STEP
        }
        val norm = rawPoints.sumOf { it.density } * GRID_STEP
        require(norm > 0.0) { "degenerate density: normalization is zero" }
        val points = rawPoints.map { it.copy(density = it.density / norm) }
        val peaks = findPeaks(points, era.presentYear)
        if (peaks.size > 1) {
            diagnostics.add("multi-modal distribution: ${peaks.size} peaks retained, not collapsed to a mean")
        }
        return CalibrationResult(
            labCode = meas.labCode,
            measurementSourceId = measurementSourceId,
            curveRef = "${curve.name}@${curve.curveVersion}",
            offsetLabel = offset?.label,
            deltaR = deltaR,
            deltaRSigma = deltaRSigma,
            effectiveMu = mu,
            effectiveSigma = sigma,
            gridStep = GRID_STEP,
            gridFrom = from,
            gridTo = to,
            normalization = norm,
            points = points,
            peaks = peaks,
            intervals682 = hdr(points, 0.682, era.presentYear),
            intervals954 = hdr(points, 0.954, era.presentYear),
            diagnostics = diagnostics
        )
    }

    /** Map a 14C age back to cal BP by inverting the (piecewise linear) mean curve;
     *  returns null when the age is outside the curve value range. */
    fun inverseMap(curve: CurveData, c14: Double): Double? {
        val pts = curve.points
        var best: Double? = null
        var bestDist = Double.MAX_VALUE
        for (i in 0 until pts.size - 1) {
            val a = pts[i]
            val b = pts[i + 1]
            val lo = minOf(a.c14Age, b.c14Age)
            val hi = maxOf(a.c14Age, b.c14Age)
            if (c14 in lo..hi && b.c14Age != a.c14Age) {
                val f = (c14 - a.c14Age) / (b.c14Age - a.c14Age)
                val t = a.calBp + f * (b.calBp - a.calBp)
                val dist = kotlin.math.abs(t - (a.calBp + b.calBp) / 2)
                if (dist < bestDist) { bestDist = dist; best = t }
            }
        }
        return best
    }

    fun findPeaks(points: List<DensityPoint>, presentYear: Int): List<Peak> {
        if (points.size < 3) return emptyList()
        val maxD = points.maxOf { it.density }
        val peaks = mutableListOf<Peak>()
        for (i in 1 until points.size - 1) {
            val d = points[i].density
            if (d >= points[i - 1].density && d >= points[i + 1].density && d >= maxD * 0.05) {
                if (peaks.none { kotlin.math.abs(it.calBp - points[i].calBp) < 2 * GRID_STEP }) {
                    peaks.add(Peak(points[i].calBp, d, eraLabel(points[i].calBp, presentYear)))
                }
            }
        }
        return peaks
    }

    /** Highest-density-region intervals; returns possibly several disjoint (并列) intervals. */
    fun hdr(points: List<DensityPoint>, mass: Double, presentYear: Int): List<CalInterval> {
        val order = points.indices.sortedByDescending { points[it].density }
        val chosen = sortedSetOf<Int>()
        var acc = 0.0
        for (i in order) {
            if (acc >= mass) break
            chosen.add(i)
            acc += points[i].density * GRID_STEP
        }
        val runs = mutableListOf<Pair<Int, Int>>()
        var start: Int? = null
        for (i in 0..points.size) {
            if (i in chosen) {
                if (start == null) start = i
            } else if (start != null) {
                runs.add(start to i - 1)
                start = null
            }
        }
        return runs.map { (a, b) ->
            val m = (a..b).sumOf { points[it].density } * GRID_STEP
            CalInterval(
                points[a].calBp, points[b].calBp, m,
                eraLabel(points[a].calBp, presentYear), eraLabel(points[b].calBp, presentYear)
            )
        }
    }

    /** Expandable integral evidence for one interval: grid mass, normalization,
     *  contributing curve segment, offset assumption and algorithm identity. */
    fun integralEvidence(
        derivedId: String,
        result: CalibrationResult,
        level: String,
        intervalIndex: Int,
        curve: CurveData,
        offset: OffsetData?,
        fingerprint: String
    ): IntegralEvidence {
        val intervals = if (level == "68") result.intervals682 else result.intervals954
        val interval = intervals[intervalIndex]
        val inRange = result.points.filter { it.calBp >= interval.fromCalBp && it.calBp <= interval.toCalBp }
        val probability = inRange.sumOf { it.density } * result.gridStep
        val segment = curve.points.filter { it.calBp >= interval.fromCalBp - curveStep(curve) &&
            it.calBp <= interval.toCalBp + curveStep(curve) }
        return IntegralEvidence(
            derivedId = derivedId,
            level = level,
            intervalIndex = intervalIndex,
            interval = interval,
            gridStep = result.gridStep,
            nGridPoints = inRange.size,
            rawMass = probability * result.normalization,
            normalization = result.normalization,
            probability = probability,
            curveRef = result.curveRef,
            curveSegment = segment,
            interpolation = "piecewise-linear, clamped at domain boundary",
            offsetAssumption = result.offsetLabel?.let { "$it dR=${result.deltaR} ± ${result.deltaRSigma}" }
                ?: "no reservoir offset (dR=0)",
            algoVersion = ALGO_VERSION,
            fingerprint = fingerprint
        )
    }

    private fun curveStep(curve: CurveData): Double =
        if (curve.points.size > 1) curve.points[1].calBp - curve.points[0].calBp else GRID_STEP

    /** P(before is older than after) = P(calBp_before > calBp_after) over the product grid. */
    fun pOlder(before: CalibrationResult, after: CalibrationResult): Double {
        val bp = after.points
        val prefix = DoubleArray(bp.size + 1)
        for (j in bp.indices) prefix[j + 1] = prefix[j] + bp[j].density * after.gridStep
        var p = 0.0
        for (pt in before.points) {
            val idx = lowerBound(bp, pt.calBp)
            p += pt.density * before.gridStep * prefix[idx]
        }
        return p
    }

    private fun lowerBound(points: List<DensityPoint>, calBp: Double): Int {
        var lo = 0
        var hi = points.size
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (points[mid].calBp < calBp) lo = mid + 1 else hi = mid
        }
        return lo
    }
}
