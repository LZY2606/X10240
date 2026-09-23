package gsb

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import kotlin.math.*

const val ALGO_CALIB = "calib-1.2.0"
const val ALGO_STRAT = "strat-1.1.0"

@Serializable
data class Interval(val loBp: Double, val hiBp: Double, val prob: Double, val id: String)

@Serializable
data class EvidenceRow(val calBp: Double, val density: Double, val cumulative: Double)

@Serializable
data class IntervalEvidence(val intervalId: String, val step: Double, val rows: List<EvidenceRow>, val totalProb: Double)

@Serializable
data class CalibResult(
    val gridStart: Double,
    val step: Double,
    val density: List<Double>,
    val norm: Double,
    val peaks: List<Double>,
    val intervals68: List<Interval>,
    val intervals95: List<Interval>,
    val evidence: List<IntervalEvidence>,
    val truncatedAtMin: Boolean,
    val truncatedAtMax: Boolean
) {
    fun toJson(): JsonObject = JsonFmt.encodeToJsonElement(this).jsonObject
    companion object {
        fun fromJson(o: JsonObject): CalibResult = JsonFmt.decodeFromJsonElement(o)
    }
}

object Calib {
    const val GRID_STEP = 1.0

    /**
     * 校准：对每个日历年 t，p(t) ∝ Σ_k w_k · N(m − μ_k ; curve(t), √(σm² + σk² + σc(t)²))。
     * 多峰分布完整保留，绝不压成平均年；固定曲线版本下插值与归一化可重现。
     */
    fun calibrate(curve: CalibrationCurve, age: Double, sigma: Double, offset: OffsetDistribution): CalibResult {
        val comps = offset.effectiveComponents()
        val n = ((curve.maxBp - curve.minBp) / GRID_STEP).toInt() + 1
        val density = DoubleArray(n)
        for (i in 0 until n) {
            val t = curve.minBp + i * GRID_STEP
            val (c14, sc) = curve.interp(t) ?: continue
            var p = 0.0
            for (c in comps) {
                val s = sqrt(sigma * sigma + c.sigma * c.sigma + sc * sc)
                val z = (age - c.mean - c14) / s
                p += c.weight * exp(-0.5 * z * z) / (s * sqrt(2.0 * PI))
            }
            density[i] = p
        }
        val rawSum = density.sum() * GRID_STEP
        val norm = if (rawSum > 0) 1.0 / rawSum else 0.0
        for (i in 0 until n) density[i] *= norm

        val densList = density.toList()
        val maxP = density.max()
        val peaks = ArrayList<Double>()
        for (i in 1 until n - 1) {
            if (density[i] >= density[i - 1] && density[i] >= density[i + 1] && density[i] >= 0.05 * maxP) {
                if (peaks.isEmpty() || abs(peaks.last() - (curve.minBp + i * GRID_STEP)) > 3.0)
                    peaks.add(curve.minBp + i * GRID_STEP)
            }
        }
        val iv68 = hpd(densList, curve.minBp, GRID_STEP, 0.682, "68")
        val iv95 = hpd(densList, curve.minBp, GRID_STEP, 0.954, "95")
        val evidence = (iv68 + iv95).map { buildEvidence(densList, curve.minBp, GRID_STEP, it) }
        val eps = 1e-4 * maxP
        return CalibResult(
            gridStart = curve.minBp, step = GRID_STEP, density = densList, norm = norm,
            peaks = peaks, intervals68 = iv68, intervals95 = iv95, evidence = evidence,
            truncatedAtMin = density.first() > eps, truncatedAtMax = density.last() > eps
        )
    }

    /** 最高后验密度区间：按密度降序累积到目标概率，连续的格子合并为并列区间（不合并、不平均）。 */
    private fun hpd(density: List<Double>, start: Double, step: Double, level: Double, tag: String): List<Interval> {
        val order = density.indices.sortedByDescending { density[it] }
        val chosen = BooleanArray(density.size)
        var acc = 0.0
        for (i in order) {
            if (acc >= level) break
            chosen[i] = true
            acc += density[i] * step
        }
        val out = ArrayList<Interval>()
        var i = 0
        var k = 0
        while (i < density.size) {
            if (!chosen[i]) { i++; continue }
            var j = i
            var prob = 0.0
            while (j < density.size && chosen[j]) { prob += density[j] * step; j++ }
            out.add(Interval(start + i * step, start + (j - 1) * step, prob, "iv$tag-${k++}"))
            i = j
        }
        return out
    }

    /** 积分证据：区间内逐格累积，抽样到 ≤41 行，附精确总概率。 */
    private fun buildEvidence(density: List<Double>, start: Double, step: Double, iv: Interval): IntervalEvidence {
        val i0 = ((iv.loBp - start) / step).roundToInt()
        val i1 = ((iv.hiBp - start) / step).roundToInt()
        val rows = ArrayList<EvidenceRow>()
        var cum = 0.0
        val count = i1 - i0 + 1
        val stride = max(1, count / 40)
        for (i in i0..i1) {
            cum += density[i] * step
            if ((i - i0) % stride == 0 || i == i1)
                rows.add(EvidenceRow(start + i * step, density[i], cum))
        }
        return IntervalEvidence(iv.id, step, rows, cum)
    }

    /** P(A 比 B 老)：在共同网格上离散卷积。 */
    fun probOlder(a: CalibResult, b: CalibResult): Double {
        require(a.gridStart == b.gridStart && a.step == b.step && a.density.size == b.density.size)
        var p = 0.0
        var prefixB = 0.0
        for (i in a.density.indices) {
            p += a.density[i] * a.step * prefixB
            prefixB += b.density[i] * b.step
        }
        return p
    }
}

/** 层序冲突的候选调整：全部合法候选都保留，测量值不被改写。 */
@Serializable
data class StratCandidate(val id: String, val action: String, val detail: String, val resultingP: Double?, val resolves: Boolean)

@Serializable
data class StratAssessment(
    val constraintInputId: String,
    val olderStream: String,
    val youngerStream: String,
    val pOrdered: Double,
    val status: String, // "consistent" | "conflict"
    val olderCalibId: String,
    val youngerCalibId: String,
    val candidates: List<StratCandidate>
) {
    fun toJson(): JsonObject = JsonFmt.encodeToJsonElement(this).jsonObject
    companion object { fun fromJson(o: JsonObject): StratAssessment = JsonFmt.decodeFromJsonElement(o) }
}

object Strat {
    fun assess(
        constraintInputId: String,
        olderStream: String, older: CalibResult, olderCalibId: String,
        youngerStream: String, younger: CalibResult, youngerCalibId: String,
        olderOffset: OffsetDistribution, olderAge: Double, olderSigma: Double, curve: CalibrationCurve
    ): StratAssessment {
        val p = Calib.probOlder(older, younger)
        val status = if (p >= 0.5) "consistent" else "conflict"
        val cands = ArrayList<StratCandidate>()
        if (status == "conflict") {
            cands.add(StratCandidate("cand-exclude-older", "exclude", "将 $olderStream 标记为离群（不改写测量）", 1.0, true))
            cands.add(StratCandidate("cand-exclude-younger", "exclude", "将 $youngerStream 标记为离群（不改写测量）", 1.0, true))
            val spread = olderOffset.effectiveComponents().map { it.sigma }.max()
            if (spread > 0.0) {
                var k = 0
                var shift = -2.0 * spread
                while (shift <= 2.0 * spread + 1e-9) {
                    if (abs(shift) > 1e-9) {
                        val adj = Calib.calibrate(curve, olderAge - shift, olderSigma, olderOffset)
                        val pa = Calib.probOlder(adj, younger)
                        cands.add(StratCandidate(
                            "cand-shift-${k}", "adjustOffset",
                            "将 $olderStream 的储库偏移平移 ${shift.toInt()} ${olderOffset.unit}（分布内 ±2σ）",
                            pa, pa >= 0.5
                        ))
                        k++
                    }
                    shift += spread
                }
            }
            // 无偏移分布可调时，仍保留“无法唯一决定”的全部合法候选（两个排除候选）
        }
        return StratAssessment(constraintInputId, olderStream, youngerStream, p, status, olderCalibId, youngerCalibId, cands)
    }
}
