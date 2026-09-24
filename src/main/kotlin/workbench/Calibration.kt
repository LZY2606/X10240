package workbench

import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

const val CAL_ALGO_VERSION = "cal-1.0"
const val ERA_BOUNDARY_BP = 1949 // calBP <= 1949 -> CE, >= 1950 -> BCE（无 0 年）

data class CurvePoint(val calBP: Int, val c14Age: Double)

data class CurveDef(
    val curveId: String,
    val citation: String,
    val sigma: Double,
    val points: List<CurvePoint>
) {
    val minBP: Int get() = points.first().calBP
    val maxBP: Int get() = points.last().calBP
}

object CurveRegistry {
    private fun build(id: String, citation: String, sigma: Double, shift: Double, longAmp: Double): CurveDef {
        val pts = (0..6000 step 25).map { t ->
            val c14 = t - 240.0 + 130.0 * sin(t / 170.0) + 55.0 * sin(t / 61.0) + shift + longAmp * sin(t / 300.0)
            CurvePoint(t, c14)
        }
        return CurveDef(id, citation, sigma, pts)
    }

    private val curves: Map<String, CurveDef> = listOf(
        build("INTCAL-LAB-1.0", "Workbench 内置演示校准曲线 v1（固定正弦摆动形状，可重现）", 25.0, 0.0, 0.0),
        build("INTCAL-LAB-2.0", "Workbench 内置演示校准曲线 v2（v1 + 系统性偏移与长周期修正）", 20.0, 45.0, 20.0)
    ).associateBy { it.curveId }

    fun get(id: String): CurveDef =
        curves[id] ?: throw IllegalArgumentException("未知曲线版本: $id（可用: ${curves.keys.sorted().joinToString()}）")

    fun ids(): List<String> = curves.keys.sorted()
}

/** 分段线性插值；超出端点时沿首/末段斜率线性外推（边界插值规则，固定版本下可重现）。 */
fun interpolate(curve: CurveDef, t: Double): Double {
    val pts = curve.points
    fun seg(p0: CurvePoint, p1: CurvePoint): Double =
        p0.c14Age + (t - p0.calBP) * (p1.c14Age - p0.c14Age) / (p1.calBP - p0.calBP)
    if (t <= pts.first().calBP) return seg(pts[0], pts[1])
    if (t >= pts.last().calBP) return seg(pts[pts.size - 2], pts[pts.size - 1])
    var i = 0
    while (i < pts.size - 2 && pts[i + 1].calBP < t) i++
    return seg(pts[i], pts[i + 1])
}

/** calBP -> 年代系统标签（BCE/CE，无 0 年）。 */
fun eraLabel(calBP: Int): String =
    if (calBP <= ERA_BOUNDARY_BP) "${1950 - calBP} CE" else "${calBP - 1949} BCE"

data class PeakEvidence(
    val peakId: String,
    val tStartBP: Int,       // 区间年轻端（较小 BP）
    val tEndBP: Int,         // 区间年老端（较大 BP）
    val step: Int,
    val rawSum: Double,      // 未归一化似然和
    val totalSum: Double,    // 全网格似然和（归一化分母）
    val mass: Double,        // rawSum / totalSum
    val touchesBoundary: Boolean,
    val eraStart: String,
    val eraEnd: String,
    val crossesEraBoundary: Boolean
)

data class CalibrationOutcome(
    val gridStart: Int,
    val gridEnd: Int,
    val step: Int,
    val sigmaEff: Double,
    val totalSum: Double,
    val peaks: List<PeakEvidence>,
    val hpd68: List<Pair<Int, Int>>,
    val hpd95: List<Pair<Int, Int>>,
    val density: DoubleArray
)

/**
 * 放射性碳校准：在固定曲线版本上对后验密度做网格积分并归一化。
 * 多峰分布完整保留为 PeakEvidence 列表，绝不压成平均年。
 */
fun calibrate(
    curve: CurveDef,
    measuredR: Double,
    measSigma: Double,
    deltaR: Double = 0.0,
    offsetSigma: Double = 0.0,
    gridStart: Int = curve.minBP,
    gridEnd: Int = curve.maxBP,
    step: Int = 1
): CalibrationOutcome {
    val sigmaEff = sqrt(curve.sigma * curve.sigma + measSigma * measSigma + offsetSigma * offsetSigma)
    val n = (gridEnd - gridStart) / step + 1
    val likelihood = DoubleArray(n)
    for (i in 0 until n) {
        val t = gridStart + i * step
        val mu = interpolate(curve, t.toDouble()) + deltaR
        val z = (measuredR - mu) / sigmaEff
        likelihood[i] = exp(-0.5 * z * z)
    }
    val total = likelihood.sum()
    val p = DoubleArray(n) { likelihood[it] / total }
    val maxP = p.max()
    val threshold = maxP * 0.02

    val rawRegions = mutableListOf<Pair<Int, Int>>()
    var i = 0
    while (i < n) {
        if (p[i] >= threshold) {
            var j = i
            while (j + 1 < n && p[j + 1] >= threshold) j++
            rawRegions.add(i to j)
            i = j + 1
        } else i++
    }
    val merged = mutableListOf<Pair<Int, Int>>()
    for (r in rawRegions) {
        if (merged.isNotEmpty() && r.first - merged.last().second <= 10) {
            merged[merged.lastIndex] = merged.last().first to r.second
        } else merged.add(r)
    }
    val peaks = merged.mapIndexed { idx, range ->
        val (a, b) = range
        var raw = 0.0
        for (k in a..b) raw += likelihood[k]
        val tS = gridStart + a * step
        val tE = gridStart + b * step
        PeakEvidence(
            peakId = "p${idx + 1}",
            tStartBP = tS,
            tEndBP = tE,
            step = step,
            rawSum = raw,
            totalSum = total,
            mass = raw / total,
            touchesBoundary = a == 0 || b == n - 1,
            eraStart = eraLabel(tS),
            eraEnd = eraLabel(tE),
            crossesEraBoundary = tS <= ERA_BOUNDARY_BP && tE >= ERA_BOUNDARY_BP + 1
        )
    }.filter { it.mass >= 0.005 }

    fun hpd(level: Double): List<Pair<Int, Int>> {
        val order = p.indices.sortedByDescending { p[it] }
        val chosen = HashSet<Int>()
        var acc = 0.0
        for (idx in order) {
            if (acc >= level) break
            acc += p[idx]
            chosen.add(idx)
        }
        val out = mutableListOf<Pair<Int, Int>>()
        var start = -1
        for (k in 0 until n) {
            if (k in chosen) {
                if (start < 0) start = k
            } else if (start >= 0) {
                out.add(gridStart + start * step to gridStart + (k - 1) * step)
                start = -1
            }
        }
        if (start >= 0) out.add(gridStart + start * step to gridEnd)
        return out
    }

    return CalibrationOutcome(gridStart, gridEnd, step, sigmaEff, total, peaks, hpd(0.682), hpd(0.954), p)
}
