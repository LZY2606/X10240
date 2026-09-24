package app

import kotlinx.serialization.json.*
import kotlin.math.exp
import kotlin.math.sqrt

data class CurvePoint(val calBp: Int, val c14: Double, val sigma: Double)

fun parseCurve(content: JsonObject): List<CurvePoint> {
    val pts = content["points"]?.jsonArray
        ?: throw ImportException(listOf("curve: missing 'points' array"))
    return pts.map { el ->
        val a = el.jsonArray
        CurvePoint(
            a[0].jsonPrimitive.int,
            a[1].jsonPrimitive.double,
            a[2].jsonPrimitive.double
        )
    }.sortedBy { it.calBp }
}

data class CurveEval(val c14: Double, val sigma: Double, val boundary: Boolean)

/** Linear interpolation between bracketing curve points; clamps at the curve
 *  boundary (boundary interpolation) and flags it so the fact is recorded. */
fun evalCurve(points: List<CurvePoint>, calBp: Int): CurveEval {
    if (calBp <= points.first().calBp)
        return CurveEval(points.first().c14, points.first().sigma, calBp < points.first().calBp || calBp == points.first().calBp)
    if (calBp >= points.last().calBp)
        return CurveEval(points.last().c14, points.last().sigma, true)
    var lo = 0
    var hi = points.size - 1
    while (hi - lo > 1) {
        val mid = (lo + hi) / 2
        if (points[mid].calBp <= calBp) lo = mid else hi = mid
    }
    val a = points[lo]; val b = points[hi]
    val f = (calBp - a.calBp).toDouble() / (b.calBp - a.calBp)
    return CurveEval(a.c14 + (b.c14 - a.c14) * f, a.sigma + (b.sigma - a.sigma) * f, false)
}

data class IntegrationEvidence(
    val gridStart: Int, val gridEnd: Int, val step: Int,
    val normConstant: Double, val threshold: Double,
    val intervalFromBp: Int, val intervalToBp: Int,
    val pointCount: Int, val partialSum: Double, val total: Double
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("gridStart", gridStart); put("gridEnd", gridEnd); put("step", step)
        put("normConstant", normConstant); put("threshold", threshold)
        put("intervalFromBp", intervalFromBp); put("intervalToBp", intervalToBp)
        put("pointCount", pointCount); put("partialSum", partialSum); put("total", total)
        put("eraRange", Era.displayRange(intervalFromBp, intervalToBp))
    }
}

data class CalInterval(val fromBp: Int, val toBp: Int, val prob: Double, val evidence: IntegrationEvidence) {
    fun toJson(): JsonObject = buildJsonObject {
        put("fromBp", fromBp); put("toBp", toBp)
        put("era", Era.displayRange(fromBp, toBp))
        put("prob", prob)
        put("evidence", evidence.toJson())
    }
}

data class Calibration(
    val gridStart: Int, val gridEnd: Int, val step: Int,
    val probs: List<Double>,          // normalized, sums to 1
    val normConstant: Double,             // raw sum before normalization
    val peaks: List<Int>,             // cal BP of local maxima (all modes kept)
    val intervals68: List<CalInterval>,
    val intervals95: List<CalInterval>,
    val boundaryUsed: Boolean,
    val effectiveMu: Double,          // after reservoir offset
    val effectiveSigma: Double        // sqrt(sigma^2 + sigmaOffset^2)
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("gridStart", gridStart); put("gridEnd", gridEnd); put("step", step)
        put("normConstant", norm)
        put("boundaryInterpolationUsed", boundaryUsed)
        put("effectiveMu", effectiveMu); put("effectiveSigma", effectiveSigma)
        putJsonArray("peaks") { peaks.forEach { add(it) } }
        putJsonArray("peaksEra") { peaks.forEach { add(Era.displayCalBp(it)) } }
        putJsonArray("probs") { probs.forEach { add(it) } }
        putJsonArray("intervals68") { intervals68.forEach { add(it.toJson()) } }
        putJsonArray("intervals95") { intervals95.forEach { add(it.toJson()) } }
    }

    companion object {
        fun fromJson(o: JsonObject): Calibration {
            val probs = o["probs"]!!.jsonArray.map { it.jsonPrimitive.double }
            fun intervals(key: String) = o[key]!!.jsonArray.map { iv ->
                val io = iv.jsonObject
                val ev = io["evidence"]!!.jsonObject
                CalInterval(
                    io["fromBp"]!!.jsonPrimitive.int, io["toBp"]!!.jsonPrimitive.int,
                    io["prob"]!!.jsonPrimitive.double,
                    IntegrationEvidence(
                        ev["gridStart"]!!.jsonPrimitive.int, ev["gridEnd"]!!.jsonPrimitive.int,
                        ev["step"]!!.jsonPrimitive.int, ev["normConstant"]!!.jsonPrimitive.double,
                        ev["threshold"]!!.jsonPrimitive.double,
                        ev["intervalFromBp"]!!.jsonPrimitive.int, ev["intervalToBp"]!!.jsonPrimitive.int,
                        ev["pointCount"]!!.jsonPrimitive.int, ev["partialSum"]!!.jsonPrimitive.double,
                        ev["total"]!!.jsonPrimitive.double
                    )
                )
            }
            return Calibration(
                o["gridStart"]!!.jsonPrimitive.int, o["gridEnd"]!!.jsonPrimitive.int,
                o["step"]!!.jsonPrimitive.int, probs, o["normConstant"]!!.jsonPrimitive.double,
                o["peaks"]!!.jsonArray.map { it.jsonPrimitive.int },
                intervals("intervals68"), intervals("intervals95"),
                o["boundaryInterpolationUsed"]!!.jsonPrimitive.boolean,
                o["effectiveMu"]!!.jsonPrimitive.double, o["effectiveSigma"]!!.jsonPrimitive.double
            )
        }
    }
}

/**
 * Calibrate a 14C measurement against a curve on a fixed integer grid.
 * - measurement mu/sigma in 14C yr BP; optional reservoir offset (deltaR, sigmaDeltaR)
 *   shifts mu and widens sigma (offset is itself a distribution, never a point).
 * - likelihood p(t) ∝ exp(-(mu' - c(t))^2 / (2 (sigma'^2 + s(t)^2)))
 * - normalized over the full grid; reproducible for a fixed curve version.
 * - all modes are kept; the distribution is never collapsed to a mean year.
 */
fun calibrate(
    mu: Double, sigma: Double,
    points: List<CurvePoint>,
    offsetMean: Double = 0.0, offsetSigma: Double = 0.0
): Calibration {
    require(sigma > 0) { "sigma must be positive" }
    val effMu = mu - offsetMean
    val effSigma = sqrt(sigma * sigma + offsetSigma * offsetSigma)
    val start = points.first().calBp
    val end = points.last().calBp
    val n = end - start + 1
    val raw = DoubleArray(n)
    var boundary = false
    for (i in 0 until n) {
        val ev = evalCurve(points, start + i)
        if (ev.boundary) boundary = true
        val v = effSigma * effSigma + ev.sigma * ev.sigma
        val d = effMu - ev.c14
        raw[i] = exp(-(d * d) / (2.0 * v))
    }
    val norm = raw.sum()
    val probs = raw.map { it / norm }

    val maxP = probs.max()
    val peaks = mutableListOf<Int>()
    for (i in 0 until n) {
        val left = if (i == 0) -1.0 else probs[i - 1]
        val right = if (i == n - 1) -1.0 else probs[i + 1]
        if (probs[i] > left && probs[i] >= right && probs[i] > 0.02 * maxP) peaks.add(start + i)
    }

    fun hpd(level: Double): List<CalInterval> {
        val order = probs.indices.sortedByDescending { probs[it] }
        var acc = 0.0
        var threshold = 0.0
        for (idx in order) {
            acc += probs[idx]
            threshold = probs[idx]
            if (acc >= level) break
        }
        val out = mutableListOf<CalInterval>()
        var i = 0
        while (i < n) {
            if (probs[i] >= threshold - 1e-18) {
                var j = i
                var sum = 0.0
                while (j < n && probs[j] >= threshold - 1e-18) { sum += probs[j]; j++ }
                out.add(
                    CalInterval(
                        start + i, start + j - 1, sum,
                        IntegrationEvidence(start, end, 1, norm, threshold, start + i, start + j - 1, j - i, sum, 1.0)
                    )
                )
                i = j
            } else i++
        }
        return out
    }

    return Calibration(start, end, 1, probs, norm, peaks, hpd(0.6827), hpd(0.9545), boundary, effMu, effSigma)
}

/** Exact P(A older than B) from two discrete normalized distributions on the same grid. */
fun probOlder(a: Calibration, b: Calibration): Double {
    require(a.gridStart == b.gridStart && a.gridEnd == b.gridEnd)
    var cumB = 0.0
    var p = 0.0
    for (i in a.probs.indices) {
        p += a.probs[i] * cumB   // B strictly younger (smaller cal BP) than A at i
        cumB += b.probs[i]
    }
    return p
}
