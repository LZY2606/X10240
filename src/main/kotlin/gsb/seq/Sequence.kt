package gsb.seq

import gsb.calib.Calibrator
import gsb.model.*
import gsb.util.Ids
import kotlin.math.abs

object SequenceChecker {

    /**
     * P(calBP_older >= calBP_younger) estimated on the shared fixed grid by the
     * discrete joint mass of independent posteriors.
     * A conflict never edits a measurement; it only yields candidate adjustments.
     */
    fun analyze(
        constraint: SequenceConstraint,
        younger: CalibrationResult,
        older: CalibrationResult,
        eventId: String,
        virtualTime: String
    ): SequenceAnalysis {
        val gYoung = gridOf(younger)
        val gOld = gridOf(older)
        val lo = maxOf(gYoung.first().first, gOld.first().first)
        val hi = minOf(gYoung.last().first, gOld.last().first)
        val step = younger.gridStepBP
        val py = Array(gYoung.size) { doubleArrayOf(gYoung[it].first, gYoung[it].second) }
        val po = Array(gOld.size) { doubleArrayOf(gOld[it].first, gOld[it].second) }

        var good = 0.0; var total = 0.0
        for (yo in py) {
            if (yo[0] !in lo..hi) continue
            for (oo in po) {
                if (oo[0] !in lo..hi) continue
                val joint = yo[1] * oo[1]
                total += joint
                if (oo[0] >= yo[0]) good += joint
            }
        }
        val pConsistent = if (total > 0) good / total else 0.0
        val conflict = pConsistent < 0.05

        val candidates = if (conflict) buildCandidates(constraint, younger, older, pConsistent)
        else emptyList()

        val fp = Ids.sha256(
            "seq/v1.1|${constraint.id}|${younger.paramFingerprint}|${older.paramFingerprint}"
        )
        return SequenceAnalysis(
            id = Ids.sid("seqres", constraint.id + "|" + younger.id + "|" + older.id),
            constraintId = constraint.id,
            youngerResultId = younger.id,
            olderResultId = older.id,
            consistentProbability = pConsistent,
            satisfied = pConsistent >= 0.95,
            conflict = conflict,
            candidateAdjustments = candidates,
            status = ResultStatus.CURRENT,
            paramFingerprint = fp,
            directInputs = listOf(constraint.id, younger.id, older.id),
            producedByEvent = eventId,
            virtualTime = virtualTime
        )
    }

    private fun gridOf(r: CalibrationResult): List<Pair<Double, Double>> {
        var t = r.gridAnchorBP
        return r.posterior.map { d -> (t) to d.also { t += r.gridStepBP } }
    }

    private fun buildCandidates(
        c: SequenceConstraint, y: CalibrationResult, o: CalibrationResult, p: Double
    ): List<CandidateAdjustment> {
        val base = "P(${c.olderCode} older)=${"%.3f".format(p)}"
        fun cand(kind: String, target: String, desc: String, legal: String) =
            CandidateAdjustment(
                id = Ids.sid("cand", "$kind|$target|${c.id}"),
                kind = kind, targetId = target,
                description = "$desc ($base). Original measurement is left untouched.",
                legality = legal, accepted = null
            )
        return listOf(
            cand("REVISE_OFFSET", y.offsetHypothesisId,
                "Re-examine reservoir offset for younger ${y.measurementCode}", "AMBIGUOUS"),
            cand("CHANGE_CURVE", y.curveId,
                "Compare ${y.measurementCode} under a newer curve version", "LEGAL"),
            cand("CHANGE_CURVE", o.curveId,
                "Compare ${o.measurementCode} under a newer curve version", "LEGAL"),
            cand("REVIEW_EXCLUSION", y.measurementId,
                "Review whether ${y.measurementCode} should be excluded from the sequence", "AMBIGUOUS"),
            cand("RECHECK_MEASUREMENT", o.measurementId,
                "Request lab re-check of ${o.measurementCode}; do not overwrite submitted value", "AMBIGUOUS")
        )
    }
}
