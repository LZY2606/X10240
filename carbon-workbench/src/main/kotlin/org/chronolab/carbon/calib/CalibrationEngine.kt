package org.chronolab.carbon.calib

import org.chronolab.carbon.model.CalibrationInterval
import org.chronolab.carbon.model.CalendarEra
import org.chronolab.carbon.model.ModePeak
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

data class CalibrationConfig(
    val gridStartCalBP: Double,
    val gridEndCalBP: Double,
    val stepYears: Double,
    val supportSigma: Double = 4.0,
    val seed: Long = 20260924L
) {
    init {
        require(stepYears > 0.0 && gridEndCalBP >= gridStartCalBP)
    }
}

data class EngineOutput(
    val grid: DoubleArray,
    val density: DoubleArray,
    val totalMass: Double,
    val intervals68: List<CalibrationInterval>,
    val intervals95: List<CalibrationInterval>,
    val modes: List<ModePeak>,
    val multimodal: Boolean,
    val tiedIntervals: Boolean,
    val crossesBceCe: Boolean
)

/**
 * 固定版本下确定性的校准引擎：
 *  likelihood(t) = Σ_component w_c * Normal(age ; c_curve(t) + R + dR_c, σ_meas² + σ_curve(t)² + σ_dR,c²)
 * 密度在固定网格上梯形归一化。无随机过程；seed 仅作为指纹的一部分固定记录。
 */
object CalibrationEngine {
    private const val LOG_SQRT_2PI = 0.9189385332046727

    fun calibrate(
        ageBP: Double,
        sigmaBP: Double,
        curve: CalibrationCurve,
        reservoir: ReservoirModel,
        config: CalibrationConfig,
        idPrefix: String
    ): EngineOutput? {
        val n = ((config.gridEndCalBP - config.gridStartCalBP) / config.stepYears).toInt() + 1
        val grid = DoubleArray(n) { config.gridStartCalBP + it * config.stepYears }
        val raw = DoubleArray(n)
        val support = config.supportSigma * (sigmaBP + 80.0)
        var any = false

        for (i in 0 until n) {
            val t = grid[i]
            val interp = curve.interp(t)
            if (interp == null) { raw[i] = 0.0; continue }
            if (abs(interp.first - ageBP) > support + reservoir.run { components.maxOf { abs(it.meanYears) } + 4 * components.maxOf { it.sigmaYears } }) {
                raw[i] = 0.0; continue
            }
            var sum = 0.0
            for (comp in reservoir.components) {
                val mu = interp.first + reservoir.marineReservoirYears + comp.meanYears
                val variance = sigmaBP * sigmaBP + interp.second * interp.second + comp.sigmaYears * comp.sigmaYears
                if (variance <= 0.0) continue
                val sd = sqrt(variance)
                val z = (ageBP - mu) / sd
                sum += comp.weight * exp(-0.5 * z * z - ln(sd) - LOG_SQRT_2PI)
            }
            raw[i] = sum
            if (sum > 0.0) any = true
        }
        if (!any) return null

        var mass = trapezoid(grid, raw)
        if (!(mass > 0.0) || mass.isNaN()) return null
        val density = DoubleArray(n) { raw[it] / mass }
        mass = 1.0

        val (i68, tie68) = hpdIntervals(grid, density, 0.6827, idPrefix, "68")
        val (i95, tie95) = hpdIntervals(grid, density, 0.9545, idPrefix, "95")
        val modes = detectModes(grid, density, idPrefix)
        val cross = i95.any { CalendarEra.crossesBceCe(it.startCalBP, it.endCalBP) }
        return EngineOutput(
            grid = grid, density = density, totalMass = mass,
            intervals68 = i68, intervals95 = i95, modes = modes,
            multimodal = modes.size >= 2,
            tiedIntervals = tie68 || tie95,
            crossesBceCe = cross
        )
    }

    fun trapezoid(grid: DoubleArray, y: DoubleArray): Double {
        var s = 0.0
        for (i in 0 until grid.size - 1) {
            val dx = grid[i + 1] - grid[i]
            s += 0.5 * (y[i] + y[i + 1]) * dx
        }
        return s
    }

    /** HPD：在密度阈值二分搜索，使密度≥阈值的网格单元积分最接近目标概率。 */
    fun hpdIntervals(
        grid: DoubleArray,
        density: DoubleArray,
        target: Double,
        idPrefix: String,
        valTag: String
    ): Pair<List<CalibrationInterval>, Boolean> {
        var lo = 0.0
        var hi = density.max()
        val midpointMass = DoubleArray(grid.size)
        repeat(60) {
            val mid = 0.5 * (lo + hi)
            val m = massAbove(grid, density, mid, midpointMass)
            if (m >= target) lo = mid else hi = mid
        }
        val threshold = 0.5 * (lo + hi)
        val intervals = mutableListOf<CalibrationInterval>()
        var i = 0
        var tie = false
        var k = 0
        while (i < grid.size) {
            if (density[i] < threshold) { i++; continue }
            val startIdx = i
            while (i < grid.size && density[i] >= threshold) i++
            val endIdx = (i - 1).coerceAtMost(grid.size - 1)
            val s = grid[startIdx]
            val e = grid[endIdx]
            var p = 0.0
            for (j in startIdx until endIdx) {
                val dx = grid[j + 1] - grid[j]
                p += 0.5 * (density[j] + density[j + 1]) * dx
            }
            if (abs(density[endIdx] - threshold) <= 1e-9) tie = true
            intervals += CalibrationInterval(
                intervalId = "$idPrefix-iv$valTag-${k++}",
                startCalBP = s, endCalBP = e,
                startLabel = CalendarEra.label(s), endLabel = CalendarEra.label(e),
                probability = p, level = target,
                crossesBceCe = CalendarEra.crossesBceCe(s, e)
            )
        }
        return intervals.toList() to tie
    }

    private fun massAbove(grid: DoubleArray, density: DoubleArray, threshold: Double, work: DoubleArray): Double {
        var m = 0.0
        for (i in work.indices) work[i] = if (density[i] >= threshold) density[i] else 0.0
        for (i in 0 until grid.size - 1) {
            val dx = grid[i + 1] - grid[i]
            m += 0.5 * (work[i] + work[i + 1]) * dx
        }
        return m
    }

    fun detectModes(grid: DoubleArray, density: DoubleArray, idPrefix: String): List<ModePeak> {
        val peaks = mutableListOf<ModePeak>()
        var k = 0
        for (i in 1 until grid.size - 1) {
            if (density[i] > density[i - 1] && density[i] >= density[i + 1] && density[i] > 1e-8) {
                peaks += ModePeak("$idPrefix-mode-${k++}", grid[i], CalendarEra.label(grid[i]), density[i])
            }
        }
        val prominence = if (peaks.isNotEmpty()) peaks.maxOf { it.density } * 0.08 else 0.0
        val separated = peaks.filter { p ->
            p.density >= prominence && peaks.none { q ->
                q !== p && q.density > p.density && abs(q.calBP - p.calBP) < 25.0
            }
        }
        return separated.sortedByDescending { it.density }
    }
}
