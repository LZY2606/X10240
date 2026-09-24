package org.chronobench.engine

import org.chronobench.domain.*

object SequenceEngine {
    const val ALGORITHM_VERSION = "seq-v1.0.0"
    private const val VALID_THRESHOLD = 0.95
    private const val CONFLICT_THRESHOLD = 0.05

    fun fingerprintOf(cal: Calibration): String =
        Hashing.sha256Hex(cal.grid.joinToString(";") { "${it.calBP}:${"%.10f".format(it.p)}" })

    /** P(older sample calendar date is really older) = P(t_older > t_younger) on cal BP axis. */
    fun pOlderBeforeYounger(older: Calibration, younger: Calibration): Double {
        val o = older.grid.associate { it.calBP to it.p }
        val y = younger.grid.associate { it.calBP to it.p }
        var strict = 0.0; var equal = 0.0
        for ((t1, p1) in o) {
            for ((t2, p2) in y) {
                when {
                    t1 > t2 -> strict += p1 * p2
                    t1 == t2 -> equal += p1 * p2
                }
            }
        }
        return strict + 0.5 * equal
    }

    fun checkId(projectId: String, edgeId: String, olderCalId: String, youngerCalId: String): String =
        "seq_" + Hashing.sha256Hex(listOf(projectId, edgeId, olderCalId, youngerCalId).joinToString("|")).take(16)

    fun check(
        projectId: String,
        edge: SequenceEdge,
        older: Calibration,
        younger: Calibration,
        alternativeCals: Map<String, List<Calibration>>
    ): SequenceCheck {
        val p = pOlderBeforeYounger(older, younger)
        val status = when {
            older.status == CalStatus.UNKNOWN || younger.status == CalStatus.UNKNOWN -> OrderStatus.UNKNOWN
            p >= VALID_THRESHOLD -> OrderStatus.VALID
            p <= CONFLICT_THRESHOLD -> OrderStatus.CONFLICT
            else -> OrderStatus.AMBIGUOUS
        }
        val candidates = if (status == OrderStatus.CONFLICT || status == OrderStatus.AMBIGUOUS)
            proposeCandidates(edge, older, younger, alternativeCals) else emptyList()
        return SequenceCheck(
            id = checkId(projectId, edge.id, older.id, younger.id),
            projectId = projectId, edgeId = edge.id, edgeVersion = edge.version,
            olderCalibrationId = older.id, youngerCalibrationId = younger.id,
            status = status, pOlderBeforeYounger = p, candidates = candidates,
            measurementFingerprintBefore = fingerprintOf(older),
            measurementFingerprintAfter = fingerprintOf(younger),
            createdSeq = 0
        )
    }

    /**
     * Conflict produces *candidate adjustments* only: alternative reservoir hypotheses,
     * documented exclusion, or a human override. The raw measurement is never rewritten.
     * Every legal alternative is retained when ordering cannot be decided uniquely.
     */
    private fun proposeCandidates(
        edge: SequenceEdge,
        older: Calibration,
        younger: Calibration,
        alternativeCals: Map<String, List<Calibration>>
    ): List<Candidate> {
        val out = ArrayList<Candidate>()
        val altsOlder = alternativeCals[edge.olderMeasurementId].orEmpty().filter { it.id != older.id && it.status != CalStatus.UNKNOWN }
        val altsYounger = alternativeCals[edge.youngerMeasurementId].orEmpty().filter { it.id != younger.id && it.status != CalStatus.UNKNOWN }
        for (alt in altsOlder + altsYounger) {
            val (base, other) = if (alt.inputs.measurementId == edge.olderMeasurementId) alt to younger else older to alt
            val pAlt = pOlderBeforeYounger(base, other)
            val legal = pAlt > VALID_THRESHOLD
            val targetM = if (alt.inputs.measurementId == edge.olderMeasurementId) edge.olderMeasurementId else edge.youngerMeasurementId
            out += Candidate(
                id = "cand_" + Hashing.sha256Hex(edge.id + alt.id).take(12),
                kind = "RESERVOIR_HYPOTHESIS",
                description = "改用储库偏移假设 ${alt.inputs.reservoirHypothesisId ?: "none"} 后，层序成立概率 P=${"%.3f".format(pAlt)}",
                targetMeasurementId = targetM,
                suggestedHypothesisId = alt.inputs.reservoirHypothesisId,
                legal = legal,
                rationale = "候选只替换派生计算的偏移输入；原测量值与标准差保持不变。legal=$legal"
            )
        }
        out += Candidate(
            id = "cand_excl_" + Hashing.sha256Hex(edge.id).take(12),
            kind = "EXCLUDE_WITH_REASON",
            description = "保留测量但将该层证据标记为排除（须附来源与原因）",
            targetMeasurementId = edge.youngerMeasurementId,
            legal = false,
            rationale = "排除是人工决定，不由算法自动采纳"
        )
        return out.sortedWith(compareByDescending<Candidate> { it.legal }.thenBy { it.id })
    }
}
