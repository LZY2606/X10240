package chrono

import kotlin.math.exp
import kotlin.math.sqrt

const val CALIBRATION_ALGORITHM_VERSION = "calibrate-grid-1.0.0"
const val HPD_ALGORITHM_VERSION = "hpd-grid-1.0.0"
const val INTEGRAL_ALGORITHM_VERSION = "trapezoid-1.0.0"
const val DEFAULT_GRID_STEP = 1.0

/** 分段线性插值（不做外推；落在曲线范围外即 UNKNOWN）。 */
class InterpolatedCurve(val curve: CalibrationCurve) {
    val minCalbp = curve.points.first().calbp
    val maxCalbp = curve.points.last().calbp
    private val ages = curve.points.map { doubleArrayOf(it.calbp, it.c14age, it.c14sigma) }

    fun contains(calbp: Double): Boolean = calbp in minCalbp..maxCalbp

    /** 返回 [c14age, c14sigma]；边界点直接取端点，内部线性插值。 */
    fun at(calbp: Double): DoubleArray {
        if (calbp <= minCalbp) return doubleArrayOf(ages.first()[1], ages.first()[2])
        if (calbp >= maxCalbp) return doubleArrayOf(ages.last()[1], ages.last()[2])
        var lo = 0
        var hi = ages.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (ages[mid][0] <= calbp) lo = mid else hi = mid
        }
        val a = ages[lo]; val b = ages[hi]
        val t = (calbp - a[0]) / (b[0] - a[0])
        return doubleArrayOf(a[1] + t * (b[1] - a[1]), a[2] + t * (b[2] - a[2]))
    }
}

object MathUtil {
    private val normConst = 1.0 / sqrt(2.0 * Math.PI)
    fun gauss(x: Double, mu: Double, sigma: Double): Double {
        val z = (x - mu) / sigma
        return normConst / sigma * exp(-0.5 * z * z)
    }
    fun trapezoid(x: List<Double>, y: List<Double>): Double {
        var s = 0.0
        for (i in 1 until x.size) s += 0.5 * (y[i] + y[i - 1]) * (x[i] - x[i - 1])
        return s
    }
    fun trapezoidClipped(grid: List<Double>, density: List<Double>, from: Double, to: Double): Double {
        if (grid.isEmpty() || to < grid.first() || from > grid.last() || to <= from) return 0.0
        val lo = maxOf(from, grid.first())
        val hi = minOf(to, grid.last())
        val xs = ArrayList<Double>(); val ys = ArrayList<Double>()
        xs.add(lo); ys.add(interpolateDensity(grid, density, lo))
        for (i in grid.indices) if (grid[i] > lo && grid[i] < hi) { xs.add(grid[i]); ys.add(density[i]) }
        xs.add(hi); ys.add(interpolateDensity(grid, density, hi))
        return trapezoid(xs, ys)
    }

    private fun interpolateDensity(grid: List<Double>, d: List<Double>, x: Double): Double {
        if (x <= grid.first()) return d.first()
        if (x >= grid.last()) return d.last()
        var lo = 0; var hi = grid.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (grid[mid] <= x) lo = mid else hi = mid
        }
        val t = (x - grid[lo]) / (grid[hi] - grid[lo])
        return d[lo] + t * (d[hi] - d[lo])
    }
    fun sha256(s: String): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        return md.digest(s.encodeToByteArray()).joinToString("") { "%02x".format(it) }
    }
}

/**
 * 网格校准：固定曲线版本 + 固定网格步长下结果逐位可重现。
 * 多峰以完整密度与峰列表保留，绝不压缩为单一年龄。
 */
class CalibrationEngine {

    fun offsetFingerprint(offset: OffsetDistribution): String =
        MathUtil.sha256(
            offset.kind + "|" + offset.mean + "|" + offset.sigma + "|" +
                offset.atoms.joinToString(",") { "${it.delta}:${it.sigma}:${it.weight}" } + "|" + offset.unit
        )

    fun calibrate(
        calibrationId: String,
        m: Measurement,
        curve: CalibrationCurve,
        offset: OffsetDistribution,
        gridStep: Double = DEFAULT_GRID_STEP
    ): CalibrationResult {
        val diagnostics = mutableListOf<String>()
        val ic = InterpolatedCurve(curve)

        if (offset.kind == "INVALID") {
            diagnostics += "偏移分布无法转换：${offset.diagnostics.joinToString(";")}；保留原始表示，不产生归一化密度"
            return unknown(calibrationId, m, curve, offset, gridStep, diagnostics)
        }
        m.diagnostics.forEach { diagnostics += "测量诊断: $it" }

        val nSteps = ((ic.maxCalbp - ic.minCalbp) / gridStep).toInt() + 1
        val grid = ArrayList<Double>(nSteps)
        val raw = ArrayList<Double>(nSteps)
        for (i in 0 until nSteps) {
            val t = ic.minCalbp + i * gridStep
            val atBoundary = t == ic.minCalbp || t == ic.maxCalbp
            if (atBoundary) diagnostics += "网格点位于曲线边界（${"%.1f".format(t)} cal BP），取端点值不外推"
            val (c14mu, c14sigma) = ic.at(t)
            val lik = likelihood(m.ageValue, m.sigmaValue, c14mu, c14sigma, offset)
            grid.add(t); raw.add(lik)
        }

        val norm = MathUtil.trapezoid(grid, raw)
        if (norm <= 0.0 || norm.isNaN()) {
            diagnostics += "似然归一化失败（积分=$norm），标记 UNKNOWN"
            return unknown(calibrationId, m, curve, offset, gridStep, diagnostics, grid, raw)
        }
        val density = raw.map { it / norm }
        val boundaryNote =
            if (grid.size > 2 && (density.first() > 0.01 * density.max() || density.last() > 0.01 * density.max()))
                "密度在曲线边界仍非零，边界之外按不可知处理" else null
        boundaryNote?.let { diagnostics += it }

        val peaks = detectPeaks(grid, density)
        val full95 = hpdGlobal(grid, density, 0.9545)
        val full68 = hpdGlobal(grid, density, 0.6827)
        val wmean = grid.zip(density).sumOf { it.first * it.second } * gridStep /
            (MathUtil.trapezoid(grid, density).takeIf { it > 0 } ?: 1.0)

        return CalibrationResult(
            calibrationId = calibrationId,
            measurementId = m.id,
            curveVersion = "${curve.id}@${curve.version}",
            offsetId = offset.id,
            offsetFingerprint = offsetFingerprint(offset),
            algorithmVersion = CALIBRATION_ALGORITHM_VERSION,
            gridStep = gridStep,
            grid = grid,
            density = density,
            normalization = norm,
            peaks = peaks,
            full95 = full95,
            full68 = full68,
            status = "CURRENT",
            diagnostics = diagnostics,
            weightedMeanCalbp = wmean
        )
    }

    /** 似然：NORMAL 走解析方差合成；DISCRETE/MIXTURE 走加权原子卷积。 */
    private fun likelihood(obs: Double, obsSigma: Double, c14mu: Double, c14sigma: Double, o: OffsetDistribution): Double {
        return when (o.kind) {
            "NORMAL" -> {
                val mu = c14mu + (o.mean ?: 0.0)
                val s = sqrt(obsSigma * obsSigma + c14sigma * c14sigma + (o.sigma ?: 0.0).let { it * it })
                MathUtil.gauss(obs, mu, s)
            }
            "DISCRETE", "MIXTURE" -> {
                var total = 0.0; var wsum = 0.0
                for (a in o.atoms) {
                    val mu = c14mu + a.delta
                    val s = sqrt(obsSigma * obsSigma + c14sigma * c14sigma + a.sigma * a.sigma)
                    total += a.weight * MathUtil.gauss(obs, mu, s); wsum += a.weight
                }
                if (wsum > 0) total / wsum else 0.0
            }
            else -> 0.0
        }
    }

    private fun detectPeaks(grid: List<Double>, d: List<Double>): List<Peak> {
        val maxD = d.max()
        val cutoff = maxD * 1e-4
        val peaks = mutableListOf<Peak>()
        var idx = 0
        for (i in 1 until d.size - 1) {
            // 严格局部最大，平台取平台首点
            val localMax = d[i] > cutoff && d[i] > d[i - 1] && d[i] >= d[i + 1]
            if (!localMax) continue
            idx++
            val region = basinOf(grid, d, i, cutoff)
            val mass = integrateRegion(grid, d, region.first, region.last)
            val p68 = hpdWithin(grid, d, region.first, region.last, mass, 0.6827)
            val p95 = hpdWithin(grid, d, region.first, region.last, mass, 0.9545)
            peaks += Peak(
                index = idx,
                modeCalbp = grid[i],
                mass = mass,
                hpd68 = p68,
                hpd95 = p95,
                label = "峰$idx @ ${CalTime.labeled(grid[i])}"
            )
        }
        return peaks
    }

    private fun basinOf(grid: List<Double>, d: List<Double>, center: Int, cutoff: Double): IntRange {
        var l = center
        while (l > 0 && d[l - 1] <= d[l] && d[l - 1] > cutoff) l--
        while (l > 0 && d[l - 1] > cutoff) l--
        var r = center
        while (r < d.size - 1 && d[r + 1] <= d[r] && d[r + 1] > cutoff) r++
        while (r < d.size - 1 && d[r + 1] > cutoff) r++
        return l..r
    }

    private fun integrateRegion(grid: List<Double>, d: List<Double>, fromIdx: Int, toIdx: Int): Double {
        var s = 0.0
        for (i in fromIdx + 1..toIdx) s += 0.5 * (d[i] + d[i - 1]) * (grid[i] - grid[i - 1])
        return s
    }

    /** 区域内按密度从高到低纳入网格单元直至达到质量比例，合并相邻段为 HPD 区间。 */
    private fun hpdWithin(
        grid: List<Double>, d: List<Double>, fromIdx: Int, toIdx: Int,
        regionMass: Double, fraction: Double
    ): List<CalbpInterval> {
        val target = regionMass * fraction
        val order = (fromIdx..toIdx).sortedWith(compareByDescending<Int> { d[it] })
        val chosen = BooleanArray(d.size)
        var acc = 0.0
        for (ix in order) {
            chosen[ix] = true
            acc += cellMass(grid, d, ix)
            if (acc >= target) break
        }
        return mergeChosen(grid, d, chosen)
    }

    private fun hpdGlobal(grid: List<Double>, d: List<Double>, fraction: Double): List<CalbpInterval> {
        val order = d.indices.sortedWith(compareByDescending<Int> { d[it] })
        val chosen = BooleanArray(d.size)
        var acc = 0.0
        for (ix in order) {
            chosen[ix] = true
            acc += cellMass(grid, d, ix)
            if (acc >= fraction) break
        }
        return mergeChosen(grid, d, chosen)
    }

    private fun cellMass(grid: List<Double>, d: List<Double>, i: Int): Double {
        val left = if (i > 0) 0.5 * (d[i] + d[i - 1]) * (grid[i] - grid[i - 1]) else 0.0
        val right = if (i < d.size - 1) 0.5 * (d[i + 1] + d[i]) * (grid[i + 1] - grid[i]) else 0.0
        return (left + right) * 0.5
    }

    private fun mergeChosen(grid: List<Double>, d: List<Double>, chosen: BooleanArray): List<CalbpInterval> {
        val out = mutableListOf<CalbpInterval>()
        var i = 0
        while (i < chosen.size) {
            if (!chosen[i]) { i++; continue }
            var j = i
            while (j + 1 < chosen.size && chosen[j + 1]) j++
            val prob = integrateRegion(grid, d, i, j)
            out += CalbpInterval(grid[i], grid[j], prob)
            i = j + 1
        }
        return out
    }

    fun intervalIntegral(result: CalibrationResult, fromCalbp: Double, toCalbp: Double): IntervalIntegral {
        val lo = maxOf(fromCalbp, result.grid.first())
        val hi = minOf(toCalbp, result.grid.last())
        val prob = if (hi < lo) 0.0 else MathUtil.trapezoidClipped(result.grid, result.density, lo, hi)
        return IntervalIntegral(
            fromCalbp = fromCalbp, toCalbp = toCalbp, probability = prob,
            gridStep = result.gridStep,
            trapezoidTerms = result.grid.count { it in lo..hi }.coerceAtLeast(0),
            algorithmVersion = INTEGRAL_ALGORITHM_VERSION
        )
    }

    private fun unknown(
        calibrationId: String, m: Measurement, curve: CalibrationCurve,
        offset: OffsetDistribution, gridStep: Double,
        diagnostics: List<String>,
        grid: List<Double> = emptyList(), raw: List<Double> = emptyList()
    ) = CalibrationResult(
        calibrationId = calibrationId,
        measurementId = m.id,
        curveVersion = "${curve.id}@${curve.version}",
        offsetId = offset.id,
        offsetFingerprint = offsetFingerprint(offset),
        algorithmVersion = CALIBRATION_ALGORITHM_VERSION,
        gridStep = gridStep,
        grid = grid, density = raw.map { 0.0 },
        normalization = 0.0, peaks = emptyList(),
        full95 = emptyList(), full68 = emptyList(),
        status = "UNKNOWN",
        diagnostics = diagnostics
    )
}
