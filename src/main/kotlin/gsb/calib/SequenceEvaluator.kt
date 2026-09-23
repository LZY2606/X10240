package gsb.calib

import gsb.domain.CalibrationRunResult
import gsb.domain.OrderingPairResult
import gsb.domain.SequenceConstraint
import gsb.domain.SequenceEvalResult
import kotlin.math.ln
import kotlin.math.max

/**
 * 层序评估。
 *
 * 约定：约束 members[order] 中 order 小者更老（天文年更大、cal BP 更大）。
 * 概率 P(A older than B) 由两条独立校准密度的网格求和得到：
 *   Σ_{ta>tb} dA(ta)·dB(tb)·Δt²
 * 一致性指数参照 Bronk Ramsey 风格的合意指标（固定、可重现的近似）。
 *
 * 冲突处理原则：
 *  - 不改动任何测量；
 *  - 生成“候选调整”（建议复核：储库偏移、曲线版本、层序方向、排除区间）；
 *  - 无法唯一决定时保留全部合法候选，等待人工裁定。
 */
object SequenceEvaluator {
    const val ALGORITHM_VERSION = "seq-1.0.0"
    private const val PAIR_CONFLICT = 0.05

    fun evaluate(
        constraint: SequenceConstraint,
        runs: List<CalibrationRunResult>,
        createdAt: String,
        evalId: String
    ): SequenceEvalResult {
        val bySample = runs.associateBy { it.sampleId }
        val ordered = constraint.members.sortedBy { it.order }.map { m ->
            requireNotNull(bySample[m.sampleId]) { "missing run for sample ${m.sampleId}" }
        }
        val pairs = mutableListOf<OrderingPairResult>()
        val adjustments = linkedSetOf<String>()
        var contradict = false
        var sumLogAgree = 0.0
        var pairCount = 0
        for (i in 0 until ordered.size - 1) {
            val older = ordered[i]
            val younger = ordered[i + 1]
            val p = probabilityOlderBefore(older, younger)
            val bad = p < PAIR_CONFLICT
            if (bad) contradict = true
            pairs += OrderingPairResult(older.sampleId, younger.sampleId, p, bad)
            // 合意指数（每对）：A_overall = 100 * P(顺序成立) / P_prior，
            // 采用信息量近似，固定公式可重现。
            val info = max(1e-6, p)
            sumLogAgree += ln(info)
            pairCount++
            if (bad || p < 0.5) {
                adjustments += "复核 ${older.sampleId} → ${younger.sampleId}：检查下层样品是否需要储库偏移修正（ΔR）"
                adjustments += "复核 ${older.sampleId} → ${younger.sampleId}：确认层序方向（下老上新）是否记录颠倒"
                adjustments += "复核 ${older.sampleId} / ${younger.sampleId}：尝试更新校准曲线版本后重算"
                adjustments += "复核 ${older.sampleId} → ${younger.sampleId}：检查是否存在应排除的并列日历区间（HPD 次峰）"
            }
        }
        // 整体满足概率：各相邻对概率之积（独立性近似，仅用于复核排序）。
        val satisfied = pairs.fold(1.0) { acc, pr -> acc * pr.probabilityOlderBeforeYounger }
        val agreement = if (pairCount == 0) 100.0 else {
            val v = Math.exp(sumLogAgree / pairCount)
            v * 100.0
        }
        val fp = CalibrationEngine.paramFingerprint(
            "seq", constraint.id, ordered.joinToString(",") { it.runId + "@" + it.paramFingerprint }
        )
        val diagnostics = if (contradict)
            listOf("检测到层序矛盾：测量未被改写；以上候选调整全部保留，等待人工裁定")
        else if (agreement < 60.0)
            listOf("层序合意指数偏低（<60%），建议人工复核但不构成硬矛盾")
        else emptyList()

        return SequenceEvalResult(
            evalId = evalId,
            constraintId = constraint.id,
            runIds = ordered.map { it.runId },
            algorithmVersion = ALGORITHM_VERSION,
            paramFingerprint = fp,
            createdAt = createdAt,
            agreementIndex = agreement,
            satisfiedProbability = satisfied,
            contradictory = contradict,
            candidateAdjustments = adjustments.toList(),
            perPair = pairs,
            diagnostics = diagnostics
        )
    }

    /**
     * P(olderSample 时间上早于 youngerSample)。
     * 两条密度以 cal BP 网格存储，网格起点可能不同，这里在并集年轴上对齐。
     */
    fun probabilityOlderBefore(older: CalibrationRunResult, younger: CalibrationRunResult): Double {
        val dO = denseMap(older)
        val dY = denseMap(younger)
        val years = (dO.keys + dY.keys).sorted()
        var p = 0.0
        var norm = 0.0
        for (ta in years) {
            val da = dO[ta] ?: 0.0
            if (da == 0.0) continue
            for (tb in years) {
                val db = dY[tb] ?: 0.0
                if (db == 0.0) continue
                val w = da * db
                norm += w
                if (ta > tb) p += w // ta 更大 calBP => 更老
            }
        }
        return if (norm == 0.0) 0.0 else p / norm
    }

    private fun denseMap(run: CalibrationRunResult): Map<Int, Double> {
        val out = LinkedHashMap<Int, Double>(run.density.size)
        var bp = run.gridFromCalBp
        for (v in run.density) {
            if (v > 0.0) out[bp] = v
            bp += run.gridStepYears
        }
        return out
    }
}
