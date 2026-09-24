package org.gsb.carbon.calc

import org.gsb.carbon.model.AdjustmentCandidate
import org.gsb.carbon.model.CalibrationCurve
import org.gsb.carbon.model.CalibrationRun
import org.gsb.carbon.model.Measurement
import org.gsb.carbon.model.OffsetDistribution
import org.gsb.carbon.model.SequenceEdge
import org.gsb.carbon.model.SequenceEvaluation
import java.security.MessageDigest
import kotlin.math.max

/**
 * 层序（stratigraphic ordering）评估。
 *
 * 用两条后验密度（公共网格）计算 P(calBP_older > calBP_younger)。
 * 冲突时产出候选调整（换/调储库偏移、曲线升版重算、复核边方向），
 * 但绝不改写测量；无法唯一决定时全部合法候选都保留。
 */
object SequenceModel {
    const val ALGORITHM_VERSION = "seq-prob-v1.0"
    const val CONFLICT_THRESHOLD = 0.60
    const val CONSISTENT_THRESHOLD = 0.90

    fun parameterFingerprint(older: CalibrationRun, younger: CalibrationRun): String {
        val raw = "${ALGORITHM_VERSION}|${older.runId}|${younger.runId}"
        return sha256(raw).take(16)
    }

    fun evaluate(
        edge: SequenceEdge,
        olderRun: CalibrationRun,
        youngerRun: CalibrationRun,
        eventSeq: Int,
    ): SequenceEvaluation {
        val grid = commonGrid(olderRun, youngerRun)
        val po = resample(olderRun, grid)
        val py = resample(youngerRun, grid)
        val step = grid[1] - grid[0]
        var prob = 0.0
        for (i in grid.indices) {
            for (j in grid.indices) {
                if (grid[i] > grid[j]) prob += po[i] * py[j] * step * step
            }
        }
        val conflict = prob < CONFLICT_THRESHOLD
        val consistent = prob >= CONSISTENT_THRESHOLD
        val fp = parameterFingerprint(olderRun, youngerRun)
        return SequenceEvaluation(
            evaluationId = "seq-$fp",
            edgeId = edge.edgeId,
            olderRunId = olderRun.runId,
            youngerRunId = youngerRun.runId,
            orderProbability = prob,
            consistent = consistent,
            conflict = conflict,
            algorithmVersion = ALGORITHM_VERSION,
            paramFingerprint = fp,
            createdAtEventSeq = eventSeq,
        )
    }

    private fun commonGrid(a: CalibrationRun, b: CalibrationRun): DoubleArray {
        val lo = max(a.gridPoints.first().calBp, b.gridPoints.first().calBp)
        val hi = minOf(a.gridPoints.last().calBp, b.gridPoints.last().calBp)
        require(hi > lo) { "两条后验没有公共网格，无法评估层序" }
        val step = max(a.gridStepYears, b.gridStepYears)
        val n = ((hi - lo) / step).toInt() + 1
        return DoubleArray(n) { lo + it * step }
    }

    private fun resample(run: CalibrationRun, grid: DoubleArray): DoubleArray {
        val step = run.gridStepYears
        val raw = DoubleArray(grid.size)
        var sum = 0.0
        for (i in grid.indices) {
            val d = densityAt(run, grid[i], step)
            raw[i] = d
            sum += d
        }
        if (sum > 0.0) for (i in raw.indices) raw[i] /= sum * (grid[1] - grid[0])
        return raw
    }

    private fun densityAt(run: CalibrationRun, bp: Double, step: Double): Double {
        val pts = run.gridPoints
        if (bp <= pts.first().calBp) return pts.first().density
        if (bp >= pts.last().calBp) return pts.last().density
        var lo = 0
        var hi = pts.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (pts[mid].calBp <= bp) lo = mid else hi = mid
        }
        val t = (bp - pts[lo].calBp) / step
        return pts[lo].density + t * (pts[hi].density - pts[lo].density)
    }

    /**
     * 冲突候选：对 younger 样本逐一试算其它偏移假设与 deltaR 网格微调，
     * 概率跨过 CONSISTENT_THRESHOLD 的全部保留；再附加边方向复核与曲线重算候选。
     */
    fun adjustmentCandidates(
        evaluation: SequenceEvaluation,
        olderRun: CalibrationRun,
        youngerMeasurement: Measurement,
        youngerRun: CalibrationRun,
        curves: List<CalibrationCurve>,
        offsets: List<OffsetDistribution>,
        eventSeq: Int,
    ): List<AdjustmentCandidate> {
        if (!evaluation.conflict) return emptyList()
        val out = mutableListOf<AdjustmentCandidate>()
        for (offset in offsets) {
            if (offset.offsetId == youngerRun.offsetId) continue
            val trial = Calibration.calibrate(
                youngerMeasurement,
                curves.first { it.versionId == youngerRun.curveVersionId },
                offset,
                youngerRun.gridStepYears,
                eventSeq,
            )
            val p = evaluateWith(olderRun, trial)
            if (p >= CONSISTENT_THRESHOLD) {
                out += AdjustmentCandidate(
                    candidateId = "cand-${evaluation.evaluationId}-off-${offset.offsetId}",
                    evaluationId = evaluation.evaluationId,
                    kind = AdjustmentCandidate.Kind.RESERVOIR_REASSIGN,
                    targetOffsetId = offset.offsetId,
                    suggestedDeltaR = null,
                    orderProbabilityIfApplied = p,
                    basis = "将较年轻样本改用偏移分布 ${offset.label} 后，层序概率达到 ${"%.3f".format(p)}",
                )
            }
        }

        val currentOffset = offsets.firstOrNull { it.offsetId == youngerRun.offsetId }
        val currentCurve = curves.first { it.versionId == youngerRun.curveVersionId }
        if (currentOffset != null) {
            val primary = currentOffset.components.maxBy { it.weight }
            for (delta in intArrayOf(-300, -200, -100, 100, 200, 300)) {
                val tuned = OffsetDistribution(
                    offsetId = "${currentOffset.offsetId}::tune${if (delta >= 0) "+" else ""}$delta",
                    label = "${currentOffset.label} ΔR 微调 ${if (delta >= 0) "+" else ""}$delta",
                    components = listOf(
                        org.gsb.carbon.model.OffsetComponent(
                            primary.deltaR + delta,
                            primary.sigma,
                            1.0,
                            "层序冲突候选微调（${currentOffset.offsetId}）",
                        ),
                    ),
                    source = "derived:sequence-conflict:${evaluation.evaluationId}",
                )
                val trial = Calibration.calibrate(
                    youngerMeasurement, currentCurve, tuned, youngerRun.gridStepYears, eventSeq,
                )
                val p = evaluateWith(olderRun, trial)
                if (p >= CONSISTENT_THRESHOLD) {
                    out += AdjustmentCandidate(
                        candidateId = "cand-${evaluation.evaluationId}-tune$delta",
                        evaluationId = evaluation.evaluationId,
                        kind = AdjustmentCandidate.Kind.RESERVOIR_TUNE,
                        targetOffsetId = tuned.offsetId,
                        suggestedDeltaR = primary.deltaR + delta,
                        orderProbabilityIfApplied = p,
                        basis = "ΔR 调整到 ${primary.deltaR + delta} 14C yr 后，层序概率 ${"%.3f".format(p)}",
                    )
                }
            }
        }

        for (curve in curves.filter { it.versionId != youngerRun.curveVersionId }) {
            val altOffset = currentOffset
            if (altOffset != null) {
                val trial = Calibration.calibrate(
                    youngerMeasurement, curve, altOffset, youngerRun.gridStepYears, eventSeq,
                )
                val p = evaluateWith(olderRun, trial)
                out += AdjustmentCandidate(
                    candidateId = "cand-${evaluation.evaluationId}-curve-${curve.version}",
                    evaluationId = evaluation.evaluationId,
                    kind = AdjustmentCandidate.Kind.CURVE_RECALC,
                    targetOffsetId = null,
                    suggestedDeltaR = null,
                    orderProbabilityIfApplied = p,
                    basis = "改用曲线 ${curve.versionId} 重算后层序概率 ${"%.3f".format(p)}（仅作候选）",
                )
            }
        }

        out += AdjustmentCandidate(
            candidateId = "cand-${evaluation.evaluationId}-reverse",
            evaluationId = evaluation.evaluationId,
            kind = AdjustmentCandidate.Kind.REVERSE_EDGE_REVIEW,
            targetOffsetId = null,
            suggestedDeltaR = null,
            orderProbabilityIfApplied = 1.0 - evaluation.orderProbability,
            basis = "复核地层边方向；若反向则层序概率为 ${"%.3f".format(1.0 - evaluation.orderProbability)}（需来源更新，不自动改写）",
        )
        return out
    }

    private fun evaluateWith(olderRun: CalibrationRun, youngerRun: CalibrationRun): Double {
        val grid = commonGrid(olderRun, youngerRun)
        val po = resample(olderRun, grid)
        val py = resample(youngerRun, grid)
        val step = grid[1] - grid[0]
        var prob = 0.0
        for (i in grid.indices) {
            for (j in grid.indices) {
                if (grid[i] > grid[j]) prob += po[i] * py[j] * step * step
            }
        }
        return prob
    }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
}
