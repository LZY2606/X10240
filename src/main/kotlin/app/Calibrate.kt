package app

import kotlinx.serialization.Serializable
import kotlin.math.exp
import kotlin.math.sqrt

/** 一个并列日历区间（HPD 区间），带积分证据。 */
@Serializable
data class Interval(
    val id: String,
    val level: Double,
    val startCalBP: Double,
    val endCalBP: Double,
    val startEra: String,
    val endEra: String,
    val crossesEra: Boolean,
    val mass: Double,
    val evidence: IntegrationEvidence
)

/** 积分证据：任一区间概率可展开读回。 */
@Serializable
data class IntegrationEvidence(
    val gridStep: Double,
    val startIndex: Int,
    val endIndex: Int,
    val pointCount: Int,
    val massSum: Double,
    val normalization: Double,
    val algorithm: String
)

@Serializable
data class CalibrationOutcome(
    val gridStart: Double,
    val gridEnd: Double,
    val gridStep: Double,
    val normalization: Double,
    val effectiveSigma: Double,
    val diagnostics: List<String>,
    val density: List<Double>,
    val intervals: List<Interval>
)

object Calibrator {
    const val ALGO = "calib-1.0.0"
    const val SEQ_ALGO = "seqeval-1.0.0"
    val LEVELS = listOf(0.6827, 0.9545)

    /**
     * 固定版本曲线上的线性插值。
     * 返回 (mu, sigma, diagnostic?)：t 在曲线域外时不外推，附诊断。
     */
    fun interpolate(points: List<CurvePoint>, t: Double): Triple<Double, Double, String?> {
        require(points.isNotEmpty()) { "曲线无数据点" }
        val sorted = points.sortedBy { it.calBP }
        if (t < sorted.first().calBP || t > sorted.last().calBP)
            return Triple(0.0, 0.0, "t=$t 超出曲线范围 [${sorted.first().calBP}, ${sorted.last().calBP}]，不外推")
        // 边界/节点：恰好落在节点上时直接取节点值（边界插值可重现）
        val idx = sorted.binarySearchBy(t) { it.calBP }
        if (idx >= 0) return Triple(sorted[idx].c14, sorted[idx].sigma, null)
        val hi = -idx - 1
        val lo = hi - 1
        val a = sorted[lo]; val b = sorted[hi]
        val f = (t - a.calBP) / (b.calBP - a.calBP)
        return Triple(a.c14 + f * (b.c14 - a.c14), a.sigma + f * (b.sigma - a.sigma), null)
    }

    /**
     * 校准：多峰后验分布，不压成平均年。
     * 归一化在固定曲线版本 + 固定参数下完全可重现。
     */
    fun calibrate(
        ageBP: Double, sigmaBP: Double,
        offsetMean: Double, offsetSd: Double,
        curve: List<CurvePoint>,
        step: Double = 1.0,
        resultIdPrefix: String = "tmp"
    ): CalibrationOutcome {
        val sorted = curve.sortedBy { it.calBP }
        val start = sorted.first().calBP
        val end = sorted.last().calBP
        val n = ((end - start) / step).toInt() + 1
        val effBase = sigmaBP * sigmaBP + offsetSd * offsetSd
        val diagnostics = mutableListOf<String>()
        val raw = DoubleArray(n)
        var truncatedLo = false
        var truncatedHi = false
        val shifted = ageBP - offsetMean
        for (i in 0 until n) {
            val t = start + i * step
            val (mu, sc, diag) = interpolate(sorted, t)
            if (diag != null) { raw[i] = 0.0; continue }
            val sd = sqrt(effBase + sc * sc)
            val z = (shifted - mu) / sd
            raw[i] = exp(-0.5 * z * z) / sd
        }
        // 边界截断诊断：边缘概率质量显著非零 → 分布被曲线边界截断
        val edge = raw.take(5).max() to raw.takeLast(5).max()
        val peak = raw.max()
        if (peak > 0 && edge.first > 0.01 * peak) { truncatedLo = true }
        if (peak > 0 && edge.second > 0.01 * peak) { truncatedHi = true }
        if (truncatedLo) diagnostics.add("后验分布在曲线边界 calBP=$start 处被截断（边界插值）")
        if (truncatedHi) diagnostics.add("后验分布在曲线边界 calBP=$end 处被截断（边界插值）")
        val norm = raw.sum()
        val density = raw.map { it / norm }
        val intervals = mutableListOf<Interval>()
        for (level in LEVELS) {
            hpdRuns(density, level).forEachIndexed { k, (lo, hi) ->
                val mass = (lo..hi).sumOf { density[it] }
                val loBP = start + lo * step
                val hiBP = start + hi * step
                val iid = stableId("ivl", "$resultIdPrefix|$level|$k|$loBP|$hiBP")
                intervals.add(
                    Interval(
                        id = iid, level = level,
                        startCalBP = loBP, endCalBP = hiBP,
                        startEra = Era.label(hiBP), endEra = Era.label(loBP),
                        crossesEra = Era.crosses(loBP, hiBP),
                        mass = mass,
                        evidence = IntegrationEvidence(
                            gridStep = step, startIndex = lo, endIndex = hi,
                            pointCount = hi - lo + 1, massSum = mass,
                            normalization = norm, algorithm = ALGO
                        )
                    )
                )
            }
        }
        return CalibrationOutcome(
            gridStart = start, gridEnd = end, gridStep = step,
            normalization = norm, effectiveSigma = sqrt(effBase),
            diagnostics = diagnostics, density = density, intervals = intervals
        )
    }

    /** HPD：按密度降序累积至覆盖率，返回连续段（并列日历区间）。 */
    fun hpdRuns(density: List<Double>, coverage: Double): List<Pair<Int, Int>> {
        val order = density.indices.sortedByDescending { density[it] }
        val chosen = BooleanArray(density.size)
        var acc = 0.0
        for (i in order) {
            if (acc >= coverage) break
            chosen[i] = true
            acc += density[i]
        }
        val runs = mutableListOf<Pair<Int, Int>>()
        var i = 0
        while (i < density.size) {
            if (chosen[i]) {
                var j = i
                while (j + 1 < density.size && chosen[j + 1]) j++
                runs.add(i to j)
                i = j + 1
            } else i++
        }
        return runs
    }

    /** P(older 的日历年更老于 younger)：两个后验网格需同域同步长。 */
    fun orderProbability(older: CalibrationOutcome, younger: CalibrationOutcome): Double {
        require(older.gridStart == younger.gridStart && older.gridStep == younger.gridStep
                && older.density.size == younger.density.size) { "后验网格不对齐" }
        var cum = 0.0
        var p = 0.0
        // t 越大越老：P(tA > tB) = sum_i pA[i] * P(tB < t_i)
        for (i in older.density.indices) {
            p += older.density[i] * cum
            cum += younger.density[i]
        }
        return p
    }
}

@Serializable
data class SeqCandidate(val action: String, val targetKey: String, val rationale: String)

@Serializable
data class SeqPairEval(
    val older: String,
    val younger: String,
    val probOrder: Double,
    val conflict: Boolean,
    val candidates: List<SeqCandidate>
)

@Serializable
data class SequenceOutcome(val pairs: List<SeqPairEval>, val diagnostics: List<String>)

object SequenceEvaluator {
    const val CONFLICT_THRESHOLD = 0.5

    /**
     * 层序冲突产生候选调整而不改写测量；
     * 无法唯一决定时保留全部合法候选。
     */
    fun evaluate(
        constraints: List<Pair<String, ConstraintPayload>>,
        calibrations: Map<String, CalibrationOutcome>,
        excluded: Set<String>
    ): SequenceOutcome {
        val pairs = mutableListOf<SeqPairEval>()
        val diagnostics = mutableListOf<String>()
        for ((ckey, c) in constraints) {
            if (c.older in excluded || c.younger in excluded) {
                diagnostics.add("约束 $ckey 涉及已排除样品，跳过")
                continue
            }
            val a = calibrations[c.older]
            val b = calibrations[c.younger]
            if (a == null || b == null) {
                diagnostics.add("约束 $ckey 缺少校准结果，状态未知")
                pairs.add(SeqPairEval(c.older, c.younger, Double.NaN, false, emptyList()))
                continue
            }
            val p = Calibrator.orderProbability(a, b)
            val conflict = p < CONFLICT_THRESHOLD
            val candidates = if (conflict) listOf(
                SeqCandidate("EXCLUDE", c.older, "排除 ${c.older} 后层序成立（P=${"%.3f".format(p)}）"),
                SeqCandidate("EXCLUDE", c.younger, "排除 ${c.younger} 后层序成立（P=${"%.3f".format(p)}）"),
                SeqCandidate("DOWNWEIGHT", c.older, "降低 ${c.older} 权重并复核测量"),
                SeqCandidate("DOWNWEIGHT", c.younger, "降低 ${c.younger} 权重并复核测量")
            ) else emptyList()
            pairs.add(SeqPairEval(c.older, c.younger, p, conflict, candidates))
        }
        return SequenceOutcome(pairs, diagnostics)
    }
}
