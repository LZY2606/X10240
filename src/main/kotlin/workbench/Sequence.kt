package workbench

import kotlinx.serialization.Serializable

@Serializable
data class SeqCandidateInterval(
    val level: Double,
    val loCalBP: Double,
    val hiCalBP: Double,
    val loEra: String,
    val hiEra: String,
    val mass: Double,
)

@Serializable
data class SeqCandidate(
    val id: String,
    val label: String,
    val retainedMass: Double,
    val intervals: List<SeqCandidateInterval>,
)

@Serializable
data class SequencePayload(
    val constraintLabel: String,
    val olderLabel: String,
    val youngerLabel: String,
    val pOrder: Double,
    val status: String,
    val candidates: List<SeqCandidate>,
    val note: String,
)

object SequenceModel {
    /** Density grids must share the same axis (gridStart, step). */
    private fun cum(d: List<Double>): DoubleArray {
        val c = DoubleArray(d.size)
        var acc = 0.0
        for (i in d.indices) { acc += d[i]; c[i] = acc }
        return c
    }

    /** P(older sample is truly older than younger sample), older = larger calBP. */
    fun pOrder(older: List<Double>, younger: List<Double>): Double {
        val cumYounger = cum(younger)
        var p = 0.0
        for (i in older.indices) p += older[i] * cumYounger[i]
        return p
    }

    private fun hpd(d: List<Double>, gridStart: Double, step: Double, level: Double): List<SeqCandidateInterval> {
        val order = d.indices.sortedByDescending { d[it] }
        var acc = 0.0
        var k = 0
        while (k < order.size && acc < level) { acc += d[order[k]] * step; k++ }
        val threshold = if (k == 0) 0.0 else d[order[k - 1]]
        val out = ArrayList<SeqCandidateInterval>()
        var i = 0
        while (i < d.size) {
            if (d[i] >= threshold) {
                var j = i
                var mass = 0.0
                while (j < d.size && d[j] >= threshold) { mass += d[j] * step; j++ }
                val lo = gridStart + i * step
                val hi = gridStart + (j - 1) * step
                out.add(SeqCandidateInterval(level, lo, hi, Era.format(hi), Era.format(lo), mass))
                i = j
            } else i++
        }
        return out
    }

    /**
     * Evaluate a stratigraphic constraint ("olderLabel" must be older than "youngerLabel")
     * against two calibrated densities. Conflicts and ambiguity produce candidate
     * adjustments; the underlying measurements are never modified.
     */
    fun check(
        constraintLabel: String,
        olderLabel: String,
        youngerLabel: String,
        older: List<Double>,
        younger: List<Double>,
        gridStart: Double,
        step: Double,
    ): SequencePayload {
        val p = pOrder(older, younger)
        val cumOlder = cum(older)
        val cumYounger = cum(younger)

        fun conditional(which: String): SeqCandidate {
            // Condition the pair on the stated order; report both marginal adjustments.
            val adjOlder = older.mapIndexed { i, d -> d * cumYounger[i] }
            val adjYounger = younger.mapIndexed { i, d -> d * (1.0 - cumOlder[i]) }
            val massO = adjOlder.sum() * step
            val massY = adjYounger.sum() * step
            val normO = adjOlder.map { it / (massO / step) }
            val normY = adjYounger.map { it / (massY / step) }
            val ivls = hpd(normO, gridStart, step, 0.9545).map { it.copy(level = 0.9545) } +
                hpd(normY, gridStart, step, 0.9545)
            return SeqCandidate(
                id = Ids.of("cand", constraintLabel, which, "%.6f".format(p)),
                label = which,
                retainedMass = (massO + massY) / 2.0,
                intervals = ivls,
            )
        }

        val status: String
        val candidates = ArrayList<SeqCandidate>()
        val note: String
        when {
            p >= 0.95 -> {
                status = "consistent"
                note = "P(order as stated)=%.4f >= 0.95; no adjustment needed".format(p)
            }
            p <= 0.05 -> {
                status = "violated"
                candidates.add(conditional("condition-on-stated-order"))
                candidates.add(conditional("reverse-order-hypothesis"))
                note = "P(order as stated)=%.4f <= 0.05; stratigraphic conflict. Candidate adjustments proposed; measurements unchanged".format(p)
            }
            else -> {
                status = "ambiguous"
                candidates.add(conditional("condition-on-stated-order"))
                candidates.add(conditional("reverse-order-hypothesis"))
                note = "P(order as stated)=%.4f in (0.05,0.95); not uniquely decidable, all legal candidates retained".format(p)
            }
        }
        return SequencePayload(constraintLabel, olderLabel, youngerLabel, p, status, candidates, note)
    }
}
