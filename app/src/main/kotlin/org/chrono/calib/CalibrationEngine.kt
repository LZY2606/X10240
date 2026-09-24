package org.chrono.calib

import org.chrono.domain.*
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

const val ALGORITHM_VERSION = "calib-grid-v1"

/**
 * 单样本校准结果：完整日历年后验（多峰），永不压缩为单个平均年。
 * 固定曲线版本 + 固定参数指纹下，网格、归一化与区间积分完全可重现（无随机数）。
 */
data class CalibrationResult(
    val measurementId: String,
    val measurementVersion: Int,
    val curveId: String,
    val curveVersion: String,
    val curveFingerprint: String,
    val reservoirFingerprint: String?,
    val grid: List<GridPoint>,
    val normalized: Boolean,
    val mass: Double,
    val peaks: List<Peak>,
    val intervals95: List<CredibleInterval>,
    val algorithmVersion: String = ALGORITHM_VERSION
) {
    fun densityAt(year: Double): Double = GridMath.linInterp(grid, year)
    fun intervalMass(from: Double, to: Double): Double = GridMath.trapezoid(grid, from, to)
}

data class GridPoint(val year: Double, val density: Double)
data class Peak(val yearAstro: Double, val density: Double, val mass: Double)
data class CredibleInterval(val index: Int, val fromAstro: Double, val toAstro: Double, val probability: Double)

private const val LN_2PI2 = 0.9189385332046727 // 0.5*ln(2*pi)

object CalibrationEngine {

    fun calibrate(m: Measurement, curve: CalibrationCurve): CalibrationResult {
        require(m.curveId == curve.curveId && m.curveVersion == curve.version) {
            "measurement ${m.measurementId} pinned to ${m.curveId}@${m.curveVersion}, got ${curve.curveId}@${curve.version}"
        }
        if (!m.valid) {
            return empty(m, curve)
        }
        val age = m.age.value
        val ageSigma = m.sigma.value
        val deltas: List<Pair<Double, Double>> = m.reservoir?.let { reservoir ->
            val raw = reservoir.discretize(5.0)
            val wsum = raw.sumOf { it.second }
            raw.map { it.first to it.second / wsum }
        } ?: listOf(0.0 to 1.0)

        val lo = curve.minCal
        val hi = curve.maxCal
        val step = curve.gridStep
        val n = (hi - lo) / step
        val raw = ArrayList<GridPoint>(n + 1)
        var i = 0
        while (i <= n) {
            val year = lo + i * step
            var lik = 0.0
            for ((delta, w) in deltas) {
                val mu = curve.mu(year) + delta
                val s = sqrt(ageSigma * ageSigma + curve.sigma(year) * curve.sigma(year))
                val z = (age - mu) / s
                // 未归一化核；常数项对最终归一化无影响，保留以利诊断
                val logD = -z * z / 2.0 - ln(s) - LN_2PI2
                lik += w * fastExp(logD)
            }
            raw += GridPoint(year.toDouble(), lik)
            i++
        }
        val mass = GridMath.trapezoid(raw, raw.first().year, raw.last().year)
        val norm = if (mass > 0.0) raw.map { GridPoint(it.year, it.density / mass) } else raw
        val peaks = PeakFinder.peaks(norm)
        val intervals = Hpd.intervals(norm, 0.95)
        return CalibrationResult(
            measurementId = m.measurementId,
            measurementVersion = m.version,
            curveId = curve.curveId,
            curveVersion = curve.version,
            curveFingerprint = curve.fingerprint(),
            reservoirFingerprint = m.reservoir?.let { reservoirFingerprint(it) },
            grid = norm,
            normalized = mass > 0.0,
            mass = mass,
            peaks = peaks,
            intervals95 = intervals
        )
    }

    private fun empty(m: Measurement, curve: CalibrationCurve) = CalibrationResult(
        measurementId = m.measurementId, measurementVersion = m.version,
        curveId = curve.curveId, curveVersion = curve.version,
        curveFingerprint = curve.fingerprint(), reservoirFingerprint = null,
        grid = listOf(GridPoint(curve.minCal.toDouble(), 0.0), GridPoint(curve.maxCal.toDouble(), 0.0)),
        normalized = false, mass = 0.0, peaks = emptyList(), intervals95 = emptyList()
    )

    /** 参数指纹：储库假设的完整形状参与，保证换假设即得到不同直接来源。 */
    fun reservoirFingerprint(r: ReservoirModel): String {
        val h = FNV1a()
        when (r) {
            is ReservoirModel.Gaussian -> {
                h.update("G"); h.update(trimNum(r.mean)); h.update(trimNum(r.sd)); h.update(r.sourceRef)
            }
            is ReservoirModel.Empirical -> {
                h.update("E")
                for (s in r.samples) {
                    h.update(trimNum(s.delta)); h.update(trimNum(s.sd)); h.update(trimNum(s.weight))
                }
                h.update(r.sourceRef)
            }
        }
        return h.hex()
    }
}

private val EXP_CUT = -40.0
private fun fastExp(x: Double): Double = if (x < EXP_CUT) 0.0 else exp(x)

object GridMath {
    fun linInterp(grid: List<GridPoint>, year: Double): Double {
        if (grid.isEmpty()) return 0.0
        if (year <= grid.first().year) return grid.first().density
        if (year >= grid.last().year) return grid.last().density
        var lo = 0; var hi = grid.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (grid[mid].year <= year) lo = mid else hi = mid
        }
        val a = grid[lo]; val b = grid[hi]
        val f = (year - a.year) / (b.year - a.year)
        return a.density + f * (b.density - a.density)
    }

    /** 梯形积分；部分端点用网格线性插值，任何区间概率都可由此重放证据。 */
    fun trapezoid(grid: List<GridPoint>, from: Double, to: Double): Double {
        if (grid.size < 2) return 0.0
        val a = from.coerceIn(grid.first().year, grid.last().year)
        val b = to.coerceIn(grid.first().year, grid.last().year)
        if (a >= b) return 0.0
        var total = 0.0
        var prevYear = a
        var prevD = linInterp(grid, a)
        var idx = 0
        while (idx < grid.size && grid[idx].year < a) idx++
        while (idx < grid.size && grid[idx].year <= b) {
            val gy = grid[idx].year
            val gd = grid[idx].density
            total += (prevD + gd) * 0.5 * (gy - prevYear)
            prevYear = gy; prevD = gd
            idx++
        }
        if (prevYear < b) {
            val bd = linInterp(grid, b)
            total += (prevD + bd) * 0.5 * (b - prevYear)
        }
        return total
    }
}

object PeakFinder {
    private const val FLOOR = 1e-7
    internal fun peaks(grid: List<GridPoint>): List<Peak> {
        val out = ArrayList<Peak>()
        var runStart = -1
        for (i in grid.indices) {
            val on = grid[i].density > FLOOR
            if (on && runStart < 0) runStart = i
            val nextOn = i + 1 < grid.size && grid[i + 1].density > FLOOR
            if (runStart >= 0 && (!on || !nextOn)) {
                val seg = grid.subList(runStart, i + 1)
                val top = seg.maxBy { it.density }
                val mass = GridMath.trapezoid(grid, seg.first().year - 0.5, seg.last().year + 0.5)
                out += Peak(top.year, top.density, mass)
                runStart = -1
            }
        }
        return out.sortedByDescending { it.density }
    }
}

/**
 * 最高密度区间 (HPD)：密度阈值二分，使并集质量逼近 level；
 * 每个连通段是一条并列日历区间，多峰时自然得到多个并列区间而非平均值。
 */
object Hpd {
    fun intervals(grid: List<GridPoint>, level: Double = 0.95): List<CredibleInterval> {
        if (grid.size < 2) return emptyList()
        val maxD = grid.maxOf { it.density }
        if (maxD <= 0.0) return emptyList()
        var loT = 0.0; var hiT = maxD
        var segments: List<Pair<Double, Double>> = emptyList()
        repeat(60) {
            val mid = (loT + hiT) / 2.0
            val segs = segmentsAbove(grid, mid)
            val mass = segs.sumOf { GridMath.trapezoid(grid, it.first, it.second) }
            if (mass > level) loT = mid else hiT = mid
            segments = segs
        }
        val threshold = (loT + hiT) / 2.0
        val segs = segmentsAbove(grid, threshold)
        return segs.mapIndexed { idx, (f, t) ->
            CredibleInterval(idx, f, t, GridMath.trapezoid(grid, f, t))
        }.sortedBy { it.fromAstro }
    }

    private fun segmentsAbove(grid: List<GridPoint>, threshold: Double): List<Pair<Double, Double>> {
        // 收集阈值穿越事件；相等（相切）不改变在/出状态，避免重复或零宽区间。
        val events = ArrayList<Pair<Double, Int>>()
        if (grid.first().density >= threshold) events += grid.first().year to -1
        for (j in 1 until grid.size) {
            val a = grid[j - 1]; val b = grid[j]
            val da = a.density - threshold; val db = b.density - threshold
            if (da * db < 0.0) {
                val f = (threshold - a.density) / (b.density - a.density)
                events += (a.year + f * (b.year - a.year)) to (if (da < 0) 1 else -1)
            }
        }
        if (grid.last().density >= threshold) events += grid.last().year to 1
        events.sortWith(compareBy({ it.first }, { it.second })) // 同位点：先 -1(开) 后 +1(关)
        val out = ArrayList<Pair<Double, Double>>()
        var depth = 0; var segStart = 0.0
        for ((year, kind) in events) {
            if (kind == -1) { if (depth == 0) segStart = year; depth++ } else { depth--; if (depth == 0) out += segStart to year }
        }
        return out
    }
}}
