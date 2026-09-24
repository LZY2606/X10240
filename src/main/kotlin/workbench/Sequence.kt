package workbench

import kotlinx.serialization.Serializable

const val SEQ_ALGORITHM_VERSION = "seq-check-1.0"

@Serializable
data class CandidateAdjustment(
    val kind: String,          // "downweight-measurement" | "relax-constraint" | "flag-outlier"
    val target: String,        // labCode 或 constraint 描述
    val rationale: String,
    val adopted: Boolean = false
)

@Serializable
data class SequenceCheckResult(
    val algorithmVersion: String,
    val paramFingerprint: String,
    val constraintSourceId: String,
    val beforeLabCode: String,
    val afterLabCode: String,
    val beforeCalibrationId: String,
    val afterCalibrationId: String,
    val probBeforeOlderThanAfter: Double,
    val conflict: Boolean,
    val candidates: List<CandidateAdjustment>,
    val diagnostics: List<String>,
    val note: String
)

object SequenceChecker {
    /**
     * 层序约束 before 应老于 after（calBP 更大）。
     * 用两条已校准的归一化密度估计 P(before 老于 after)。
     * 冲突时只产生候选调整，绝不改写测量值；无法唯一决定时保留全部合法候选。
     */
    fun check(
        constraintSourceId: String,
        c: SequenceConstraint,
        beforeLab: String, beforeCal: Pair<String, CalibrationResult>,
        afterLab: String, afterCal: Pair<String, CalibrationResult>
    ): SequenceCheckResult {
        val diagnostics = mutableListOf<String>()
        val (bId, bRes) = beforeCal
        val (aId, aRes) = afterCal
        val prob = if (bRes.unknown || aRes.unknown || bRes.points.isEmpty() || aRes.points.isEmpty()) {
            diagnostics.add("unknown-input:probability not decidable")
            Double.NaN
        } else {
            // P(Tb > Ta) = sum_i pB(t_i) * CDF_A_younger(t_i) * step
            val aPts = aRes.points
            fun cdfAyounger(t: Double): Double { // P(Ta <= t)
                if (t <= aPts.first().calBp) return 0.0
                if (t >= aPts.last().calBp) return 1.0
                var s = 0.0
                var i = 0
                while (i < aPts.size - 1 && aPts[i + 1].calBp < t) {
                    s += (aPts[i].norm + aPts[i + 1].norm) * 0.5 * (aPts[i + 1].calBp - aPts[i].calBp)
                    i++
                }
                if (i < aPts.size - 1) {
                    val f = (t - aPts[i].calBp) / (aPts[i + 1].calBp - aPts[i].calBp)
                    val interp = aPts[i].norm + f * (aPts[i + 1].norm - aPts[i].norm)
                    s += (aPts[i].norm + interp) * 0.5 * (t - aPts[i].calBp)
                }
                return s
            }
            var p = 0.0
            val bPts = bRes.points
            for (i in 0 until bPts.size - 1) {
                val w = bPts[i + 1].calBp - bPts[i].calBp
                val f0 = bPts[i].norm * cdfAyounger(bPts[i].calBp)
                val f1 = bPts[i + 1].norm * cdfAyounger(bPts[i + 1].calBp)
                p += (f0 + f1) * 0.5 * w
            }
            p
        }
        val conflict = !prob.isNaN() && prob < 0.5
        val candidates = mutableListOf<CandidateAdjustment>()
        if (conflict) {
            diagnostics.add("sequence-conflict:P(before older than after)=%.4f < 0.5".format(prob))
            candidates.add(CandidateAdjustment("flag-outlier", beforeLab,
                "标记 $beforeLab 为候选离群（如再沉积/老碳效应），测量值保持不变"))
            candidates.add(CandidateAdjustment("flag-outlier", afterLab,
                "标记 $afterLab 为候选离群（如污染偏年轻），测量值保持不变"))
            candidates.add(CandidateAdjustment("relax-constraint", "${c.beforeId}->${c.afterId}",
                "放宽层序约束为软约束并记录理由，等待人工裁定"))
        }
        val fp = fingerprintOf(SEQ_ALGORITHM_VERSION,
            "$constraintSourceId|${c.beforeId}>${c.afterId}|$bId@$bId|$aId|cal=$bId,$aId")
        return SequenceCheckResult(
            SEQ_ALGORITHM_VERSION, fp, constraintSourceId, beforeLab, afterLab, bId, aId,
            prob, conflict, candidates, diagnostics,
            note = if (conflict) "冲突未唯一决定：保留全部合法候选，测量不被改写"
            else "层序关系与校准分布一致"
        )
    }
}
