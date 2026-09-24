package workbench

/**
 * Enumerates legal combinations of per-sample credible intervals under the
 * active sequence constraints. Conflicts never rewrite measurements; they
 * produce candidate adjustments (choice of intervals) or a CONTRADICTION
 * status, and every legal candidate is kept when the answer is not unique.
 */
object SequenceSolver {
    const val ALGORITHM_VERSION = "seq-1.0.0"
    const val MAX_CANDIDATES = 128

    data class Input(
        val intervalsBySample: Map<String, List<CredibleInterval>>,
        val constraints: List<SequenceConstraint>
    )

    data class Output(
        val status: SequenceStatus,
        val candidates: List<Map<String, Int>>,
        val conflicts: List<SequenceConflict>,
        val diagnostics: List<Diagnostic>,
        val fingerprint: String
    )

    private fun pairCompatible(older: CredibleInterval, younger: CredibleInterval): Boolean =
        older.toCalBP >= younger.fromCalBP

    fun solve(input: Input): Output {
        val diagnostics = mutableListOf<Diagnostic>()
        val fp = sha256(
            ALGORITHM_VERSION + "|" +
                input.intervalsBySample.entries.sortedBy { it.key }
                    .joinToString(";") { e -> e.key + ":" + e.value.joinToString(",") { "${it.fromCalBP}-${it.toCalBP}" } } + "|" +
                input.constraints.filter { it.active }.sortedBy { it.id }
                    .joinToString(";") { "${it.id}:${it.olderSampleId}->${it.youngerSampleId}" }
        )
        val active = input.constraints.filter { it.active }
        val samples = input.intervalsBySample.keys.sorted()
        if (samples.isEmpty()) {
            diagnostics += Diagnostic("NO_POSTERIORS", "没有可用的校准后验，无法评估层序")
            return Output(SequenceStatus.INSUFFICIENT_DATA, emptyList(), emptyList(), diagnostics, fp)
        }
        val constrainedSamples = active.flatMap { listOf(it.olderSampleId, it.youngerSampleId) }.toSet()
        val missing = constrainedSamples.filter { it !in input.intervalsBySample }
        if (missing.isNotEmpty()) {
            diagnostics += Diagnostic("MISSING_POSTERIOR", "以下受约束样品缺少可用后验: ${missing.joinToString(", ")}")
            return Output(SequenceStatus.INSUFFICIENT_DATA, emptyList(), emptyList(), diagnostics, fp)
        }

        // Per-constraint feasibility over interval pairs.
        val conflicts = mutableListOf<SequenceConflict>()
        for (c in active) {
            val older = input.intervalsBySample.getValue(c.olderSampleId)
            val younger = input.intervalsBySample.getValue(c.youngerSampleId)
            val feasible = older.any { o -> younger.any { y -> pairCompatible(o, y) } }
            if (!feasible) {
                conflicts += SequenceConflict(
                    c.id, c.olderSampleId, c.youngerSampleId,
                    "约束 ${c.id}（${c.olderSampleId} 老于 ${c.youngerSampleId}）与两样品所有可信区间组合均不兼容；产生候选调整，不改写测量"
                )
            }
        }

        val candidates = mutableListOf<Map<String, Int>>()
        val assignment = HashMap<String, Int>()
        val ordered = samples.toList()

        fun partialOk(sample: String, intervalIdx: Int): Boolean {
            for (c in active) {
                val oIdx = if (c.olderSampleId == sample) intervalIdx else assignment[c.olderSampleId]
                val yIdx = if (c.youngerSampleId == sample) intervalIdx else assignment[c.youngerSampleId]
                if (oIdx != null && yIdx != null) {
                    val o = input.intervalsBySample.getValue(c.olderSampleId)[oIdx]
                    val y = input.intervalsBySample.getValue(c.youngerSampleId)[yIdx]
                    if (!pairCompatible(o, y)) return false
                }
            }
            return true
        }

        fun backtrack(k: Int) {
            if (candidates.size >= MAX_CANDIDATES) {
                return
            }
            if (k == ordered.size) {
                candidates += assignment.toMap()
                return
            }
            val s = ordered[k]
            val intervals = input.intervalsBySample.getValue(s)
            for (idx in intervals.indices) {
                assignment[s] = idx
                if (partialOk(s, idx)) backtrack(k + 1)
                assignment.remove(s)
            }
        }
        backtrack(0)

        if (candidates.size >= MAX_CANDIDATES) {
            diagnostics += Diagnostic("CANDIDATES_CAPPED", "合法候选数量超过 $MAX_CANDIDATES，已截断")
        }
        val status = when {
            candidates.isEmpty() -> SequenceStatus.CONTRADICTION
            candidates.size == 1 -> SequenceStatus.CONSISTENT
            else -> SequenceStatus.AMBIGUOUS
        }
        return Output(status, candidates, conflicts, diagnostics, fp)
    }
}
