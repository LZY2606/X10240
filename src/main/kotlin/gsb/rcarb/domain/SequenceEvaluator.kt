package gsb.rcarb.domain

import kotlinx.serialization.Serializable
import kotlin.math.abs

const val ALGORITHM_SEQUENCE = "sequence-order-v1"

/**
 * 层序评估器。
 *
 * 在两条校准后验（固定版本网格）上做联合概率积分：
 *
 *   P(older 先于 younger) = ΣΣ pO(tO) pY(tY) [tO > tY]   （梯形权重，网格年）
 *
 * 不产生“修正后的测量值”：冲突时输出候选调整（建议排除某区间 / 建议新储库偏移参数），
 * 合法日历区间组合全部保留；多于一个合法组合时标记 notUnique。
 */
object SequenceEvaluator {

    fun evaluate(
        constraint: SequenceConstraint,
        older: CalibrationResult,
        younger: CalibrationResult,
        olderInputId: String,
        youngerInputId: String
    ): SequenceEvaluation {
        val evidence = mutableListOf<EvidenceStep>()
        if (older.status == ResultStatus.Unknown || younger.status == ResultStatus.Unknown) {
            return SequenceEvaluation(
                constraintId = constraint.constraintId,
                status = SeqStatus.Indeterminate,
                probabilityOrderHolds = null,
                legalCombinations = emptyList(),
                candidateAdjustments = emptyList(),
                evidence = listOf(EvidenceStep("posterior-status", "unknown", "one or both posteriors unknown; cannot integrate"))
            )
        }

        val po = older.points.associate { it.calBp to it.density }
        val py = younger.points.associate { it.calBp to it.density }
        val grid = (po.keys + py.keys).sorted()
        var pHolds = 0.0
        var pContemporary = 0.0
        for (i in grid.indices) {
            for (j in grid.indices) {
                val a = grid[i]
                val b = grid[j]
                val w = (po[a] ?: 0.0) * (py[b] ?: 0.0)
                if (w == 0.0) continue
                when {
                    constraint.relation == SequenceConstraint.Relation.OlderThan && a > b -> pHolds += w
                    constraint.relation == SequenceConstraint.Relation.Contemporary && abs(a - b) <= 50 -> pContemporary += w
                }
            }
        }
        val probability = if (constraint.relation == SequenceConstraint.Relation.OlderThan) pHolds else pContemporary
        evidence += EvidenceStep(
            "joint-integral",
            "ok",
            "P(order holds)=${"%.6f".format(probability)} grid=${grid.firstOrNull()}..${grid.lastOrNull()} calBP step=1yr relation=${constraint.relation}"
        )

        val olderIntervals = older.intervals.filter { it.level == "95" }
        val youngerIntervals = younger.intervals.filter { it.level == "95" }

        val legal = mutableListOf<LegalCombination>()
        for (oi in olderIntervals) {
            for (yi in youngerIntervals) {
                val ok = if (constraint.relation == SequenceConstraint.Relation.OlderThan)
                    oi.olderCalBp > yi.youngerCalBp
                else
                    intervalOverlap(oi, yi)
                if (ok) {
                    legal += LegalCombination(
                        combinationId = "COMBO-${oi.intervalId}-${yi.intervalId}",
                        olderInputId = olderInputId,
                        youngerInputId = youngerInputId,
                        olderIntervalId = oi.intervalId,
                        youngerIntervalId = yi.intervalId,
                        jointApproxProbability = oi.probability * yi.probability
                    )
                }
            }
        }
        evidence += EvidenceStep(
            "legal-combinations",
            if (legal.isEmpty()) "failed" else "ok",
            "legal interval combinations=${legal.size} (older options=${olderIntervals.size}, younger options=${youngerIntervals.size})"
        )

        val adjustments = mutableListOf<CandidateAdjustment>()
        val status: SeqStatus = when {
            constraint.relation == SequenceConstraint.Relation.OlderThan && probability < 0.05 && legal.isEmpty() -> {
                // 冲突：给出候选调整建议，但绝不改写测量
                val oPeak = older.peaks.maxByOrNull { it.calBp }
                val yPeak = younger.peaks.maxByOrNull { it.calBp }
                if (oPeak != null && yPeak != null) {
                    val offsetGap = yPeak.calBp - oPeak.calBp // >0 表示“老样本”的峰反而更年轻
                    if (offsetGap > 0) {
                        adjustments += CandidateAdjustment(
                            adjustmentId = "ADJ-${constraint.constraintId}-RES-${olderInputId.takeLast(6)}",
                            kind = AdjustmentKind.ReservoirShiftProposal,
                            targetInputId = olderInputId,
                            detail = "increase deltaR mean by ~${offsetGap} 14Cyr for older-side sample (proposal only; measurement unchanged)",
                            suggestedDeltaRShift = offsetGap.toDouble()
                        )
                    }
                }
                olderIntervals.forEach { iv ->
                    val clashes = youngerIntervals.none { iv.olderCalBp > it.youngerCalBp }
                    if (clashes) {
                        adjustments += CandidateAdjustment(
                            adjustmentId = "ADJ-${constraint.constraintId}-EXC-${iv.intervalId}",
                            kind = AdjustmentKind.ExcludeIntervalProposal,
                            targetInputId = olderInputId,
                            targetIntervalId = iv.intervalId,
                            detail = "interval ${Calendar.formatEra(iv.olderCalBp)}..${Calendar.formatEra(iv.youngerCalBp)} cannot predate any younger-side interval; review may reject it",
                            suggestedDeltaRShift = null
                        )
                    }
                }
                SeqStatus.Violated
            }
            legal.size > 1 -> SeqStatus.Indeterminate
            legal.size == 1 && probability >= 0.05 -> SeqStatus.Satisfied
            legal.size == 1 -> SeqStatus.Indeterminate
            else -> SeqStatus.Violated
        }
        evidence += EvidenceStep(
            "verdict",
            if (status == SeqStatus.Violated) "conflict" else "ok",
            "status=$status uniqueCombination=${legal.size == 1}; adjustments proposed=${adjustments.size}; measurements not rewritten"
        )

        return SequenceEvaluation(
            constraintId = constraint.constraintId,
            status = status,
            probabilityOrderHolds = probability,
            legalCombinations = legal,
            candidateAdjustments = adjustments,
            evidence = evidence
        )
    }

    private fun intervalOverlap(a: CalendarInterval, b: CalendarInterval): Boolean =
        maxOf(a.youngerCalBp, b.youngerCalBp) <= minOf(a.olderCalBp, b.olderCalBp)
}

@Serializable
data class SequenceEvaluation(
    val constraintId: String,
    val status: SeqStatus,
    val probabilityOrderHolds: Double?,
    val legalCombinations: List<LegalCombination>,
    val candidateAdjustments: List<CandidateAdjustment>,
    val evidence: List<EvidenceStep>
) {
    val notUnique: Boolean get() = legalCombinations.size > 1
}

@Serializable
data class LegalCombination(
    val combinationId: String,
    val olderInputId: String,
    val youngerInputId: String,
    val olderIntervalId: String,
    val youngerIntervalId: String,
    val jointApproxProbability: Double
)

@Serializable
data class CandidateAdjustment(
    val adjustmentId: String,
    val kind: AdjustmentKind,
    val targetInputId: String,
    val targetIntervalId: String? = null,
    val detail: String,
    val suggestedDeltaRShift: Double?
)

enum class AdjustmentKind { ReservoirShiftProposal, ExcludeIntervalProposal }
enum class SeqStatus { Satisfied, Violated, Indeterminate, Stale }
