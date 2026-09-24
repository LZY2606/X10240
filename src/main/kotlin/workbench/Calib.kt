package workbench

import kotlin.math.*

/**
 * 校准算法（固定算法版本 calib-1.0.0）：
 * 在固定曲线版本下，对网格逐点线性插值曲线，按高斯似然求权，归一化。
 * 多峰分布保留完整密度网格，绝不压缩成平均年。
 */
object Calib {
    const val ALGO = "calib-1.0.0"
    const val CANDIDATE_ALGO = "candidate-1.0.0"
    const val SEQUENCE_ALGO = "seqcheck-1.0.0"
    const val GRID_STEP = 1.0

    data class CurvePt(val calBP: Double, val mu: Double, val sigma: Double)

    data class BoundaryInfo(
        val truncatedLow: Boolean, val truncatedHigh: Boolean,
        val edgeMassLow: Double, val edgeMassHigh: Double, val note: String,
    )

    data class Interval(
        val index: Int, val fromBP: Double, val toBP: Double, val prob: Double,
        val iFrom: Int, val iTo: Int, val points: Int,
        val fromEra: String, val toEra: String, val crossesEraBoundary: Boolean,
    )

    data class Output(
        val gridStart: Double, val step: Double,
        val density: DoubleArray,          // 归一化后的概率密度（每年一格）
        val rawWeights: DoubleArray,       // 未归一化权重（积分证据用）
        val norm: Double,                  // 归一化常数
        val intervals68: List<Interval>,
        val intervals95: List<Interval>,
        val peaks: List<Double>,           // 众数位置 calBP
        val boundary: BoundaryInfo,
        val interpMu: DoubleArray,         // 每网格点插值得到的曲线 mu（证据）
        val interpSigma: DoubleArray,
    )

    /** 线性插值；越界时钳制到端点（边界插值），调用方记录诊断。 */
    fun interp(pts: List<CurvePt>, x: Double): Pair<Double, Double> {
        if (x <= pts.first().calBP) return pts.first().mu to pts.first().sigma
        if (x >= pts.last().calBP) return pts.last().mu to pts.last().sigma
        var lo = 0; var hi = pts.size - 1
        while (hi - lo > 1) { val mid = (lo + hi) / 2; if (pts[mid].calBP <= x) lo = mid else hi = mid }
        val a = pts[lo]; val b = pts[hi]
        val f = (x - a.calBP) / (b.calBP - a.calBP)
        return (a.mu + f * (b.mu - a.mu)) to (a.sigma + f * (b.sigma - a.sigma))
    }

    fun calibrate(pts: List<CurvePt>, age: Double, sigma: Double, deltaR: Double = 0.0, deltaRSigma: Double = 0.0): Output {
        val t = age - deltaR
        val s2 = sigma * sigma + deltaRSigma * deltaRSigma
        val start = pts.first().calBP
        val n = ((pts.last().calBP - start) / GRID_STEP).toInt() + 1
        val density = DoubleArray(n); val raw = DoubleArray(n)
        val iMu = DoubleArray(n); val iSig = DoubleArray(n)
        var norm = 0.0
        for (i in 0 until n) {
            val x = start + i * GRID_STEP
            val (mu, cs) = interp(pts, x)
            iMu[i] = mu; iSig[i] = cs
            val v = s2 + cs * cs
            val w = exp(-0.5 * (t - mu) * (t - mu) / v) / sqrt(v)
            raw[i] = w; norm += w
        }
        for (i in 0 until n) density[i] = raw[i] / norm
        val maxP = density.max()
        val peaks = (1 until n - 1).filter { density[it] >= density[it - 1] && density[it] > density[it + 1] && density[it] > 0.05 * maxP }
            .map { start + it * GRID_STEP }
        val edgeLow = (0 until min(50, n)).sumOf { density[it] }
        val edgeHigh = (max(0, n - 50) until n).sumOf { density[it] }
        val truncLow = density[0] > 1e-4 * maxP
        val truncHigh = density[n - 1] > 1e-4 * maxP
        val note = buildString {
            if (truncLow) append("分布在曲线下端 ${start.toInt()} calBP 处被截断（边缘质量 ${"%.4f".format(edgeLow)}）；")
            if (truncHigh) append("分布在曲线上端 ${pts.last().calBP.toInt()} calBP 处被截断（边缘质量 ${"%.4f".format(edgeHigh)}）；")
            if (!truncLow && !truncHigh) append("分布完全落在曲线覆盖范围内")
        }
        return Output(
            start, GRID_STEP, density, raw, norm,
            hpd(density, 0.682, start), hpd(density, 0.954, start), peaks,
            BoundaryInfo(truncLow, truncHigh, edgeLow, edgeHigh, note), iMu, iSig,
        )
    }

    /** 最高后验密度区间：按密度降序累积至覆盖率，返回连续段及其真实概率。 */
    fun hpd(density: DoubleArray, level: Double, gridStart: Double, step: Double = GRID_STEP): List<Interval> {
        val total = density.sum()
        val order = density.indices.sortedByDescending { density[it] }
        val sel = BooleanArray(density.size)
        var cum = 0.0
        for (i in order) { sel[i] = true; cum += density[i]; if (cum >= level * total) break }
        val runs = mutableListOf<Interval>()
        var i = 0; var idx = 0
        while (i < density.size) {
            if (!sel[i]) { i++; continue }
            var j = i
            var p = 0.0
            while (j < density.size && sel[j]) { p += density[j]; j++ }
            val fromBP = gridStart + i * step
            val toBP = gridStart + (j - 1) * step
            runs.add(
                Interval(
                    idx++, fromBP, toBP, p / total, i, j - 1, j - i,
                    eraLabel(toBP), eraLabel(fromBP),
                    fromBP < 1950.0 && toBP > 1950.0,
                )
            )
            i = j
        }
        return runs
    }

    /** 年代系统换算：calBP -> BCE/CE（无 0 年）。 */
    fun eraLabel(calBP: Double): String {
        val ce = 1950 - calBP.roundToInt()
        return if (ce >= 1) "$ce CE" else "${1 - ce} BCE"
    }

    /** P(A 比 B 老) = P(calBP_A > calBP_B)，两分布同网格。 */
    fun probOlder(dA: DoubleArray, dB: DoubleArray): Double {
        var cumB = 0.0; var p = 0.0
        for (i in dA.indices) { p += dA[i] * cumB; cumB += dB[i] }
        return p
    }

    fun median(d: DoubleArray, gridStart: Double, step: Double = GRID_STEP): Double {
        val half = d.sum() / 2; var c = 0.0
        for (i in d.indices) { c += d[i]; if (c >= half) return gridStart + i * step }
        return gridStart
    }

    /** 截断并重归一化（层序候选调整：不改测量，只给出条件化候选）。 */
    fun truncate(d: DoubleArray, keep: (Int) -> Boolean): DoubleArray {
        val out = DoubleArray(d.size)
        var norm = 0.0
        for (i in d.indices) if (keep(i)) { out[i] = d[i]; norm += d[i] }
        if (norm > 0) for (i in out.indices) out[i] /= norm
        return out
    }
}
