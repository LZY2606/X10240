package gsb

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Calendar era handling. Internal axis: astronomical year numbering (year 0 = 1 BCE, no year 0 in labels). */
object Era {
    const val REFERENCE_YEAR_BP = 1950
    fun calBpToAstroYear(bp: Double): Double = REFERENCE_YEAR_BP - bp
    fun astroYearToLabel(year: Double): String {
        val y = year.roundToInt()
        return if (y >= 1) "CE $y" else "BCE ${1 - y}"
    }
    fun calBpToLabel(bp: Double): String = astroYearToLabel(calBpToAstroYear(bp))
}

data class CurvePoint(val calBp: Int, val c14: Double, val sigma: Double)

/** A fixed-version calibration curve. Interpolation is piecewise linear and reproducible. */
class Curve(val curveId: String, val version: String, val points: List<CurvePoint>) {
    val loBp: Int get() = points.first().calBp
    val hiBp: Int get() = points.last().calBp

    /** Linear interpolation between bracketing curve points; clamps (and reports) outside the range. */
    fun interp(t: Double): Pair<Double, Double> {
        if (t <= loBp) return points.first().c14 to points.first().sigma
        if (t >= hiBp) return points.last().c14 to points.last().sigma
        var i = 0
        while (i < points.size - 2 && points[i + 1].calBp < t) i++
        val a = points[i]
        val b = points[i + 1]
        val f = (t - a.calBp) / (b.calBp - a.calBp)
        return (a.c14 + f * (b.c14 - a.c14)) to (a.sigma + f * (b.sigma - a.sigma))
    }
}

data class PeakOut(val apexBp: Int, val loBp: Int, val hiBp: Int, val mass: Double)

data class IntervalOut(
    val level: Double,
    val loBp: Int,
    val hiBp: Int,
    val prob: Double,
    val loLabel: String,
    val hiLabel: String,
    val evidence: JsonObject,
)

data class CalibrationOut(
    val loBp: Int,
    val hiBp: Int,
    val step: Int,
    val density: List<Double>,
    val peaks: List<PeakOut>,
    val intervals: List<IntervalOut>,
    val diagnostics: List<String>,
    val paramFingerprint: String,
)

object Calibrator {
    const val ALGORITHM_VERSION = "gsb-cal/1.0.0"
    val LEVELS = listOf(0.682, 0.954)
    const val MIN_PEAK_MASS = 0.005

    fun paramsOf(
        measAge: Double, measSigma: Double,
        offsetMu: Double, offsetSigma: Double,
        curveHash: String, step: Int,
    ): JsonObject = buildJsonObject {
        put("measAgeBP", JsonPrimitive(measAge))
        put("measSigma", JsonPrimitive(measSigma))
        put("offsetMu", JsonPrimitive(offsetMu))
        put("offsetSigma", JsonPrimitive(offsetSigma))
        put("curveHash", JsonPrimitive(curveHash))
        put("gridStep", JsonPrimitive(step))
        put("levels", JsonPrimitive(LEVELS.joinToString(",")))
    }

    fun fingerprint(params: JsonObject): String = "fp-" + sha256(canonical(params)).take(16)

    /**
     * Calibrate a radiocarbon determination against a fixed curve version.
     * The reservoir offset distribution N(offsetMu, offsetSigma) is marginalised analytically:
     * effective mean = measAge - offsetMu, effective variance = measSigma^2 + offsetSigma^2.
     * Fully deterministic: same params + same curve version -> same fingerprint -> same result.
     */
    fun calibrate(
        measAge: Double, measSigma: Double,
        offsetMu: Double, offsetSigma: Double,
        curve: Curve, step: Int = 1,
    ): CalibrationOut {
        val params = paramsOf(measAge, measSigma, offsetMu, offsetSigma, curveHashOf(curve), step)
        val fp = fingerprint(params)
        val effMean = measAge - offsetMu
        val effVar = measSigma * measSigma + offsetSigma * offsetSigma
        val n = (curve.hiBp - curve.loBp) / step + 1
        val raw = DoubleArray(n)
        for (i in 0 until n) {
            val t = curve.loBp + i * step
            val (c, s) = curve.interp(t.toDouble())
            val v = effVar + s * s
            raw[i] = exp(-0.5 * (effMean - c) * (effMean - c) / v) / sqrt(v)
        }
        val total = raw.sum()
        val density = raw.map { it / total }

        val diagnostics = mutableListOf<String>()
        if (density.first() > 1e-4 || density.last() > 1e-4) {
            diagnostics += "distribution truncated at curve boundary [${curve.loBp}, ${curve.hiBp}] calBP; " +
                "edge mass first=${"%.6f".format(density.first())} last=${"%.6f".format(density.last())}"
        }

        val peaks = findPeaks(density, curve.loBp, step)
        val intervals = mutableListOf<IntervalOut>()
        for (level in LEVELS) {
            for (run in hpdRuns(density, level)) {
                val loBp = curve.loBp + run.first * step
                val hiBp = curve.loBp + run.last * step
                var rawSum = 0.0
                for (i in run.first..run.last) rawSum += raw[i]
                val prob = rawSum / total
                val evidence = buildJsonObject {
                    put("algorithm", JsonPrimitive(ALGORITHM_VERSION))
                    put("paramFingerprint", JsonPrimitive(fp))
                    put("level", JsonPrimitive(level))
                    put("gridStep", JsonPrimitive(step))
                    put("gridPoints", JsonPrimitive(run.last - run.first + 1))
                    put("rawSum", JsonPrimitive(rawSum))
                    put("totalSum", JsonPrimitive(total))
                    put("prob", JsonPrimitive(prob))
                    put("normalised", JsonPrimitive(true))
                }
                // Older (higher calBP) end first in calendar labels.
                intervals += IntervalOut(level, loBp, hiBp, prob,
                    Era.calBpToLabel(hiBp.toDouble()), Era.calBpToLabel(loBp.toDouble()), evidence)
            }
        }
        return CalibrationOut(curve.loBp, curve.hiBp, step, density, peaks, intervals, diagnostics, fp)
    }

    fun curveHashOf(curve: Curve): String =
        "curve-" + sha256(curve.points.joinToString(";") { "${it.calBp}:${it.c14}:${it.sigma}" }).take(16)

    /** Local maxima with mass >= MIN_PEAK_MASS; boundaries at intervening minima. Multi-peak is preserved, never averaged. */
    fun findPeaks(density: List<Double>, loBp: Int, step: Int): List<PeakOut> {
        val n = density.size
        val maxima = mutableListOf<Int>()
        for (i in 0 until n) {
            val left = if (i == 0) -1.0 else density[i - 1]
            val right = if (i == n - 1) -1.0 else density[i + 1]
            if (density[i] >= left && density[i] > right && density[i] > 0) maxima += i
        }
        if (maxima.isEmpty()) return emptyList()
        val bounds = mutableListOf(0)
        for (k in 0 until maxima.size - 1) {
            var minIdx = maxima[k]
            for (i in maxima[k]..maxima[k + 1]) if (density[i] < density[minIdx]) minIdx = i
            bounds += minIdx
        }
        bounds += n - 1
        val out = mutableListOf<PeakOut>()
        for (k in maxima.indices) {
            val lo = bounds[k]
            val hi = bounds[k + 1]
            var mass = 0.0
            for (i in lo..hi) mass += density[i]
            if (mass >= MIN_PEAK_MASS) {
                out += PeakOut(loBp + maxima[k] * step, loBp + lo * step, loBp + hi * step, mass)
            }
        }
        return out
    }

    /** Highest-posterior-density runs: densest grid points accumulated until the level is covered. */
    fun hpdRuns(density: List<Double>, level: Double): List<IntRange> {
        val order = density.indices.sortedByDescending { density[it] }
        val chosen = BooleanArray(density.size)
        var acc = 0.0
        for (i in order) {
            if (acc >= level) break
            chosen[i] = true
            acc += density[i]
        }
        val runs = mutableListOf<IntRange>()
        var i = 0
        while (i < density.size) {
            if (chosen[i]) {
                var j = i
                while (j + 1 < density.size && chosen[j + 1]) j++
                runs += i..j
                i = j + 1
            } else i++
        }
        return runs
    }
}
