package org.gsb.carbon.calc

import org.gsb.carbon.model.CalDensityPoint
import org.gsb.carbon.model.CalInterval
import org.gsb.carbon.model.CalibrationCurve
import org.gsb.carbon.model.CalibrationRun
import org.gsb.carbon.model.CalendarScale
import org.gsb.carbon.model.Measurement
import org.gsb.carbon.model.OffsetDistribution
import java.security.MessageDigest
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

/**
 * 放射性碳日历校准。
 *
 * 模型（固定算法版本 ALGORITHM_VERSION）：
 *   对日历网格点 t（cal BP），曲线 mu_c(t)、sigma_c(t) 采用点间线性插值；
 *   储库偏移 R ~ 混合分布（deltaR_k, sigma_Rk, 权重 p_k），测量年龄含储库效应
 *   （mu_obs = mu_c(t) + R），故
 *     w(t) = Σ_k p_k · Normal(mu_age - mu_c(t) - deltaR_k;
 *                              0, sqrt(sigma_age² + sigma_c(t)² + sigma_Rk²))
 *   数值积分用梯形法，Z = ∫w dt，密度 = w/Z。
 *
 * 多峰不压缩为平均年；归一化常数与原始权重一并保存，任何区间都可重算积分证据。
 */
object Calibration {
    const val ALGORITHM_VERSION = "cal-grid-v1.0"
    const val DEFAULT_GRID_STEP_YEARS = 0.5
    private const val NORMAL_TAIL = 6.0
    private const val WEIGHT_EPS = 1e-12
    val HPD_LEVELS: List<Pair<String, Double>> = listOf("68.3" to 0.6827, "95.4" to 0.9545)

    fun parameterFingerprint(
        measurement: Measurement,
        curve: CalibrationCurve,
        offset: OffsetDistribution,
        gridStep: Double,
    ): String {
        val raw = buildString {
            append(ALGORITHM_VERSION).append('|')
            append(measurement.idVersion).append('|')
            append(curve.versionId).append('|')
            append(offset.offsetId).append('|')
            append(gridStep)
        }
        return sha256(raw).take(16)
    }

    fun runIdFor(measurement: Measurement, curve: CalibrationCurve, offset: OffsetDistribution, gridStep: Double): String =
        "run-" + parameterFingerprint(measurement, curve, offset, gridStep)

    @Suppress("LongParameterList")
    fun calibrate(
        measurement: Measurement,
        curve: CalibrationCurve,
        offset: OffsetDistribution,
        gridStep: Double = DEFAULT_GRID_STEP_YEARS,
        eventSeq: Int,
    ): CalibrationRun {
        val age = measurement.age.value
        val sig = measurement.sigma.value
        require(age != null && sig != null && sig > 0.0) {
            "测量 ${measurement.idVersion} 含不可转换量值，不能进入校准派生层"
        }

        val grid = buildGrid(age, curve, gridStep)
        val rawWeights = DoubleArray(grid.size) { idx ->
            val t = grid[idx]
            val muC = curve.ageAt(t)
            val sigC = curve.sigmaAt(t)
            var w = 0.0
            for (k in offset.components.indices) {
                val c = offset.components[k]
                val variance = sig * sig + sigC * sigC + c.sigma * c.sigma
                val d = age - muC - c.deltaR
                w += offset.normalizedWeights[k] * normalDensity(d, sqrt(variance))
            }
            w
        }

        val normalization = trapezoid(grid, rawWeights)
        require(normalization > 0.0) { "校准权重全为 0：${measurement.idVersion} / ${curve.versionId}" }

        val points = grid.indices.map { i ->
            CalDensityPoint(grid[i], rawWeights[i] / normalization, rawWeights[i])
        }
        val intervals = hpdIntervals(points)
        val fp = parameterFingerprint(measurement, curve, offset, gridStep)
        return CalibrationRun(
            runId = "run-$fp",
            measurementIdVersion = measurement.idVersion,
            curveVersionId = curve.versionId,
            offsetId = offset.offsetId,
            algorithmVersion = ALGORITHM_VERSION,
            paramFingerprint = fp,
            gridStepYears = gridStep,
            gridPoints = points,
            intervals = intervals,
            normalization = normalization,
            createdAtEventSeq = eventSeq,
        )
    }

    /** 限制到曲线范围与 ±6σ 尾部窗口的网格（窗口按偏移最远分量放宽）。 */
    private fun buildGrid(age: Double, curve: CalibrationCurve, gridStep: Double): DoubleArray {
        val maxSig = run {
            var m = 0.0
            for (i in curve.points.indices) m = max(m, curve.points[i].sigma)
            m
        }
        val lo = max(curve.minBp, age - 3000.0 - NORMAL_TAIL * maxSig - 300.0)
        val hi = (curve.maxBp).coerceAtMost(age + 3000.0 + NORMAL_TAIL * maxSig + 300.0)
        require(hi > lo) { "校准窗口为空（曲线范围不覆盖该测量）" }
        val n = ((hi - lo) / gridStep).toInt() + 1
        return DoubleArray(n) { i -> lo + i * gridStep }
    }

    private fun hpdIntervals(points: List<CalDensityPoint>): List<CalInterval> {
        val result = mutableListOf<CalInterval>()
        for ((levelTag, level) in HPD_LEVELS) {
            val densities = DoubleArray(points.size) { points[it].density }
            val step = points[1].calBp - points[0].calBp
            var thresholdLo = 0.0
            var thresholdHi = densities.max()
            var chosenMask: BooleanArray? = null
            repeat(60) {
                val mid = (thresholdLo + thresholdHi) / 2.0
                val mask = BooleanArray(points.size) { densities[it] >= mid }
                val mass = integrateMask(points, mask, step)
                if (mass < level) thresholdHi = mid else thresholdLo = mid
                chosenMask = mask
            }
            val mask = chosenMask!!
            var i = 0
            var bandIndex = 0
            while (i < points.size) {
                if (!mask[i]) { i++; continue }
                val start = i
                while (i < points.size && mask[i]) i++
                val end = i - 1
                val band = points.subList(start, end + 1)
                var total = 0.0
                for (j in 1 until band.size) {
                    total += (band[j].density + band[j - 1].density) / 2.0 * step
                }
                val peak = band.maxBy { it.density }
                val loBp = band.first().calBp - step / 2.0
                val hiBp = band.last().calBp + step / 2.0
                result += CalInterval(
                    intervalId = "int-$levelTag-$bandIndex",
                    loBp = loBp,
                    hiBp = hiBp,
                    probability = total,
                    containsPeakBp = peak.calBp,
                    peakDensity = peak.density,
                    crossesBceCe = CalendarScale.crossesBceCeBoundary(loBp, hiBp),
                )
                bandIndex++
            }
        }
        return result.sortedByDescending { it.probability }
    }

    private fun integrateMask(points: List<CalDensityPoint>, mask: BooleanArray, step: Double): Double {
        var total = 0.0
        for (i in 1 until points.size) {
            if (mask[i] && mask[i - 1]) {
                total += (points[i].density + points[i - 1].density) / 2.0 * step
            } else if (mask[i] || mask[i - 1]) {
                total += points[if (mask[i]) i else i - 1].density / 2.0 * step
            }
        }
        return total
    }

    /** 区间的逐点梯形积分证据（每项 = 该网格对区间概率的贡献）。 */
    fun integrationEvidence(run: CalibrationRun, loBp: Double, hiBp: Double): List<IntegrationTerm> {
        val step = run.gridStepYears
        val terms = mutableListOf<IntegrationTerm>()
        for (i in run.gridPoints.indices) {
            val p = run.gridPoints[i]
            if (p.calBp < loBp - step / 2.0 || p.calBp > hiBp + step / 2.0) continue
            val prev = run.gridPoints.getOrNull(i - 1)
            val contribution = if (prev != null &&
                prev.calBp >= loBp - step / 2.0 && prev.calBp <= hiBp + step / 2.0
            ) {
                (p.density + prev.density) / 2.0 * step
            } else {
                p.density / 2.0 * step
            }
            terms += IntegrationTerm(p.calBp, p.density, p.rawWeight / run.normalization, contribution)
        }
        return terms
    }

    data class IntegrationTerm(val calBp: Double, val density: Double, val normalizedRaw: Double, val contribution: Double)

    fun normalDensity(x: Double, sigma: Double): Double {
        if (sigma <= WEIGHT_EPS) return 0.0
        val z = x / sigma
        return exp(-0.5 * z * z) / (sigma * sqrt(2.0 * Math.PI))
    }

    private fun trapezoid(xs: DoubleArray, ys: DoubleArray): Double {
        var total = 0.0
        for (i in 1 until xs.size) total += (ys[i] + ys[i - 1]) / 2.0 * (xs[i] - xs[i - 1])
        return total
    }

    private fun sha256(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** 多峰检测：局部极大值（用于界面与断言，不合并、不平均）。 */
    fun peaks(run: CalibrationRun): List<CalDensityPoint> {
        val pts = run.gridPoints
        val out = mutableListOf<CalDensityPoint>()
        for (i in 1 until pts.size - 1) {
            if (pts[i].density > pts[i - 1].density && pts[i].density >= pts[i + 1].density) out += pts[i]
        }
        return out
    }

    /** 诊断：密度总质量（应为 1，允许数值误差）。 */
    fun totalMass(run: CalibrationRun): Double {
        val xs = DoubleArray(run.gridPoints.size) { run.gridPoints[it].calBp }
        val ys = DoubleArray(run.gridPoints.size) { run.gridPoints[it].density }
        return trapezoid(xs, ys)
    }

    /** 对数密度极值，供上层诊断未知/异常状态。 */
    fun logWeightRange(run: CalibrationRun): Pair<Double, Double> {
        val ws = run.gridPoints.map { it.rawWeight }.filter { it > 0.0 }
        return if (ws.isEmpty()) 0.0 to 0.0 else ln(ws.min()) to ln(ws.max())
    }
}
