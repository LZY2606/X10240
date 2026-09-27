package bench

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

data class CalibrationComputation(
    val gridFrom: Int, val gridTo: Int, val gridStep: Int,
    val normalization: Double, val density: List<Double>,
    val intervals: List<HpdIntervalS>, val peaks: List<Int>
)

object Calibration {
    const val ALGORITHM = "calib/1.0"
    const val GRID_FROM = 0
    const val GRID_TO = 4000
    const val GRID_STEP = 1

    /** 边界插值：节点内线性插值，网格端点之外钳制到端点值。固定曲线版本下可重现。 */
    fun interpolateCurve(points: List<CurvePoint>, calBp: Int): Pair<Double, Double> {
        require(points.isNotEmpty()) { "curve has no points" }
        if (calBp <= points.first().calBp) return points.first().bpAge to points.first().sigma
        if (calBp >= points.last().calBp) return points.last().bpAge to points.last().sigma
        var lo = 0
        var hi = points.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) / 2
            if (points[mid].calBp <= calBp) lo = mid else hi = mid
        }
        val a = points[lo]
        val b = points[hi]
        val f = (calBp - a.calBp).toDouble() / (b.calBp - a.calBp).toDouble()
        return (a.bpAge + f * (b.bpAge - a.bpAge)) to (a.sigma + f * (b.sigma - a.sigma))
    }

    fun normalPdf(x: Double, mu: Double, sigma: Double): Double {
        val z = (x - mu) / sigma
        return exp(-0.5 * z * z) / (sigma * sqrt(2.0 * PI))
    }

    /**
     * 校准：测量 (ageBP±sigma) 与储库偏移 (offsetMean±offsetSigma) 合成有效测量，
     * 在固定网格上对曲线逐点求似然并归一化。多峰分布保留全部峰，不压成平均年。
     */
    fun calibrate(
        ageBP: Double, sigma: Double,
        offsetMean: Double, offsetSigma: Double,
        points: List<CurvePoint>,
        gridFrom: Int = GRID_FROM, gridTo: Int = GRID_TO, gridStep: Int = GRID_STEP
    ): CalibrationComputation {
        val effMean = ageBP - offsetMean
        val effSigma = sqrt(sigma * sigma + offsetSigma * offsetSigma)
        val n = (gridTo - gridFrom) / gridStep + 1
        val likelihood = DoubleArray(n)
        for (i in 0 until n) {
            val t = gridFrom + i * gridStep
            val (curveAge, curveSigma) = interpolateCurve(points, t)
            val s = sqrt(effSigma * effSigma + curveSigma * curveSigma)
            likelihood[i] = normalPdf(effMean, curveAge, s)
        }
        val z = likelihood.sum()
        require(z > 0.0) { "normalization constant is zero" }
        val density = likelihood.map { it / z }
        val intervals = hpd(density, gridFrom, gridStep, listOf(0.6827, 0.9545), z)
        val peaks = findPeaks(density, gridFrom, gridStep)
        return CalibrationComputation(gridFrom, gridTo, gridStep, z, density, intervals, peaks)
    }

    /** 最高后验密度区间：按密度降序累积到目标水平，回扫连续段。 */
    fun hpd(density: List<Double>, gridFrom: Int, gridStep: Int, levels: List<Double>, normalization: Double): List<HpdIntervalS> {
        val sorted = density.sortedDescending()
        val out = mutableListOf<HpdIntervalS>()
        for (level in levels) {
            var acc = 0.0
            var threshold = 0.0
            for (p in sorted) {
                acc += p
                threshold = p
                if (acc >= level) break
            }
            var i = 0
            while (i < density.size) {
                if (density[i] >= threshold - 1e-18) {
                    var j = i
                    var mass = 0.0
                    while (j < density.size && density[j] >= threshold - 1e-18) {
                        mass += density[j]
                        j++
                    }
                    out += HpdIntervalS(level, gridFrom + i * gridStep, gridFrom + (j - 1) * gridStep, mass, mass * normalization)
                    i = j
                } else i++
            }
        }
        return out.sortedWith(compareBy({ it.level }, { it.loCalBp }))
    }

    fun findPeaks(density: List<Double>, gridFrom: Int, gridStep: Int): List<Int> {
        val maxD = density.max()
        val out = mutableListOf<Int>()
        for (i in 1 until density.size - 1) {
            if (density[i] >= density[i - 1] && density[i] > density[i + 1] && density[i] >= 0.05 * maxD) {
                out += gridFrom + i * gridStep
            }
        }
        return out
    }

    fun fingerprint(algorithm: String, measurementId: String, measurementVersion: Int,
                    curveId: String, curveVersion: Int, offsetId: String, offsetVersion: Int,
                    offsetMean: Double, offsetSigma: Double, ageBP: Double, sigma: Double,
                    gridFrom: Int, gridTo: Int, gridStep: Int): String =
        sha256("$algorithm|m=$measurementId@$measurementVersion|age=$ageBP|sigma=$sigma|" +
            "curve=$curveId@$curveVersion|offset=$offsetId@$offsetVersion:$offsetMean±$offsetSigma|" +
            "grid=$gridFrom..$gridTo:$gridStep")
}

/** 内置离线合成曲线（两个版本，用于曲线升版情形）。 */
object CurveData {
    const val CURVE_ID = "intcal-mini"
    val KEYS = listOf("intcal-mini-v1", "intcal-mini-v2")

    fun points(key: String): List<CurvePoint> = when (key) {
        "intcal-mini-v1" -> generate(0.0)
        "intcal-mini-v2" -> generate(30.0)
        else -> throw IllegalArgumentException("unknown builtin curve: $key")
    }

    private fun r3(x: Double) = (x * 1000.0).roundToInt() / 1000.0

    private fun generate(shift: Double): List<CurvePoint> =
        (Calibration.GRID_FROM..Calibration.GRID_TO step 10).map { t ->
            val d = (t - 2600.0) / 100.0
            val bump = -500.0 * exp(-d * d)
            val bp = t - 200.0 + 40.0 * sin(t / 80.0) + 25.0 * sin(t / 37.0) + bump + shift * sin(t / 61.0)
            val sig = 15.0 + 5.0 * sin(t / 53.0)
            CurvePoint(t, r3(bp), r3(sig))
        }
}
