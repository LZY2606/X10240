package org.chronobench.domain

import kotlinx.serialization.Serializable

const val SEQUENCE_ALGORITHM_VERSION = "sequence-ordered-dp-1.0.0"

@Serializable
enum class SequenceKind { STRICT_OLDER_BELOW, NON_STRICT_OLDER_BELOW }

@Serializable
data class SequenceConstraint(
    val id: String,
    val label: String,
    val measurementIdsOrdered: List<String>,
    val kind: SequenceKind,
    val minGapYears: Double = 0.0
) {
    init { require(measurementIdsOrdered.size >= 2) { "sequence needs at least two members" } }
}

@Serializable
data class SequenceMember(
    val measurementId: String,
    val calibratedRunId: String,
    val calibrationFingerprint: String
)

@Serializable
data class OrderedMarginal(
    val measurementId: String,
    val calBP: List<Double>,
    val density: List<Double>
)

@Serializable
data class AdjustmentCandidate(
    val stableId: String,
    val code: String,
    val label: String,
    val description: String,
    val targetMeasurementIds: List<String>,
    val feasibility: Double,
    val rewritesMeasurement: Boolean,
    val note: String
)

@Serializable
data class SequenceEvidence(
    val rule: String,
    val normalizationConstant: Double,
    val gridStart: Double,
    val gridEnd: Double,
    val gridStep: Double,
    val terms: Int
)

@Serializable
data class SequenceEvaluation(
    val constraintId: String,
    val kind: SequenceKind,
    val feasible: Boolean,
    val evidence: SequenceEvidence,
    val orderedMarginals: List<OrderedMarginal>,
    val conflictDiagnostic: Diagnostic?,
    val adjustmentCandidates: List<AdjustmentCandidate>,
    val algorithmVersion: String
)

object SequenceEngine {
    /**
     * Evaluate an ordered sequence against independently calibrated marginals on a common
     * grid. The ordered likelihood is the product of marginal densities subject to the
     * ordering; its normalization is computed exactly on the grid by dynamic programming.
     *
     * When the strict ordering has zero support the constraint contradicts the measurements:
     * [feasible] is false, candidate *adjustments* are listed, and no measurement value is
     * rewritten. Every legal candidate is retained (never silently auto-selected).
     */
    fun evaluate(constraint: SequenceConstraint, runs: List<CalibrationResult>): SequenceEvaluation {
        val byId = runs.associateBy { it.request.measurement.measurementId }
        require(constraint.measurementIdsOrdered.all { it in byId }) {
            "sequence ${constraint.id} references measurement without a calibrated run"
        }
        val ordered = constraint.measurementIdsOrdered.map { byId.getValue(it) }
        val start = ordered.maxOf { it.request.gridStart }
        val end = ordered.minOf { it.request.gridEnd }
        val step = ordered.first().request.gridStep
        require(ordered.all { it.request.gridStep == step }) { "calibration grids must share step" }

        val n = Math.round((end - start) / step).toInt() + 1
        fun sample(res: CalibrationResult, j: Int): Double {
            val bp = start + j * step
            val idx = Math.round((bp - res.request.gridStart) / step).toInt()
            return res.points.getOrNull(idx)?.takeIf { Math.abs(it.calBP - bp) < 1e-9 }?.density ?: 0.0
        }

        val marginals = ordered.map { res ->
            val dens = DoubleArray(n) { j -> sample(res, j) }
            OrderedMarginal(res.request.measurement.measurementId, (0 until n).map { start + it * step }, dens.toList())
        }

        val strictZ = orderedNormalization(marginals.map { it.density }, n, strict = true, minGap = constraint.minGapYears, step = step)
        val appliedStrict = constraint.kind == SequenceKind.STRICT_OLDER_BELOW
        val feasible = if (appliedStrict) strictZ > 0.0 else
            orderedNormalization(marginals.map { it.density }, n, strict = false, minGap = constraint.minGapYears, step = step) > 0.0

        val evidence = SequenceEvidence(
            rule = "product-of-marginals under ${constraint.kind} ordering, normalized by grid DP",
            normalizationConstant = if (appliedStrict) strictZ else
                orderedNormalization(marginals.map { it.density }, n, strict = false, minGap = constraint.minGapYears, step = step),
            gridStart = start,
            gridEnd = end,
            gridStep = step,
            terms = n * ordered.size
        )

        var conflict: Diagnostic? = null
        val candidates = mutableListOf<AdjustmentCandidate>()
        if (!feasible) {
            conflict = Diagnostic(
                "SEQUENCE_CONTRADICTION",
                "Strict ordering of ${constraint.measurementIdsOrdered.joinToString(" -> ")} has zero posterior support " +
                    "under runs ${ordered.joinToString { it.request.measurement.measurementId }}; measurements are left unchanged, " +
                    "legal adjustment candidates are retained for review."
            )
            val relaxedZ = orderedNormalization(marginals.map { it.density }, n, strict = false, 0.0, step)
            if (relaxedZ > 0.0) candidates.add(
                AdjustmentCandidate(
                    stableId = "ac-" + shortHash("relax|${constraint.id}"),
                    code = "RELAX_TO_NON_STRICT",
                    label = "Relax strict order to non-strict (allow contemporary layers)",
                    description = "Re-evaluate with NON_STRICT_OLDER_BELOW; no input measurement or sigma is modified.",
                    targetMeasurementIds = constraint.measurementIdsOrdered,
                    feasibility = relaxedZ,
                    rewritesMeasurement = false,
                    note = "Candidate adjustment to the *constraint*, not to laboratory values."
                )
            )
            // Swapping adjacent members: test every adjacent inversion.
            for (k in 0 until ordered.size - 1) {
                val ids = constraint.measurementIdsOrdered.toMutableList()
                val tmp = ids[k]; ids[k] = ids[k + 1]; ids[k + 1] = tmp
                val swapped = ids.map { id -> marginals.first { it.measurementId == id } }
                val zSwap = orderedNormalization(swapped.map { it.density }, n, true, constraint.minGapYears, step)
                if (zSwap > 0.0) candidates.add(
                    AdjustmentCandidate(
                        stableId = "ac-" + shortHash("swap|${constraint.id}|$k"),
                        code = "SWAP_ADJACENT_MEMBERS",
                        label = "Swap adjacent members ${constraint.measurementIdsOrdered[k]} <-> ${constraint.measurementIdsOrdered[k + 1]}",
                        description = "Proposed stratigraphic re-reading; measurements unchanged, awaiting archaeologist decision.",
                        targetMeasurementIds = listOf(ids[k], ids[k + 1]),
                        feasibility = zSwap,
                        rewritesMeasurement = false,
                        note = "Candidate affects only the ordering record; creates a new constraint version if accepted."
                    )
                )
            }
            // Widening uncertainty is offered only as a parameter revision of the constraint/offset,
            // never by mutating the submitted lab sigma.
            val widerZ = orderedNormalization(
                widen(marginals, step, extraYears = 10.0).map { it.density }, n, true, constraint.minGapYears, step
            )
            if (widerZ > 0.0) candidates.add(
                AdjustmentCandidate(
                    stableId = "ac-" + shortHash("widen|${constraint.id}"),
                    code = "REVIEW_UNCERTAINTY_ASSUMPTIONS",
                    label = "Re-open uncertainty / reservoir assumptions (+10 yr sensitivity)",
                    description = "A sensitivity run with broadened assumptions becomes feasible; " +
                        "implement as a new reservoir hypothesis or new measurement revision, never an overwrite.",
                    targetMeasurementIds = constraint.measurementIdsOrdered,
                    feasibility = widerZ,
                    rewritesMeasurement = false,
                    note = "The submitted raw value and standard deviation remain the immutable source of truth."
                )
            )
            candidates.add(
                AdjustmentCandidate(
                    stableId = "ac-" + shortHash("hold|${constraint.id}"),
                    code = "KEEP_CONFLICT_OPEN",
                    label = "Keep the contradiction open (no unique decision)",
                    description = "Record the conflict as unresolved; all marginals stay viewable but flagged, " +
                        "nothing is forced to fit.",
                    targetMeasurementIds = constraint.measurementIdsOrdered,
                    feasibility = 0.0,
                    rewritesMeasurement = false,
                    note = "Unknown/undecided is a first-class state."
                )
            )
        }

        return SequenceEvaluation(
            constraintId = constraint.id,
            kind = constraint.kind,
            feasible = feasible,
            evidence = evidence,
            orderedMarginals = marginals,
            conflictDiagnostic = conflict,
            adjustmentCandidates = candidates
                .sortedWith(compareByDescending<AdjustmentCandidate> { it.feasibility }.thenBy { it.stableId }),
            algorithmVersion = SEQUENCE_ALGORITHM_VERSION
        )
    }

    private fun widen(marginals: List<OrderedMarginal>, step: Double, extraYears: Double): List<OrderedMarginal> =
        marginals.map { m ->
            val spread = Math.round(extraYears / step).toInt()
            val d = m.density.toDoubleArray()
            val out = DoubleArray(d.size)
            for (j in d.indices) {
                var acc = 0.0
                var cnt = 0
                for (k in (j - spread)..(j + spread)) if (k in d.indices) { acc += d[k]; cnt++ }
                out[j] = acc / cnt
            }
            m.copy(density = out.toList())
        }

    /**
     * DP normalization of p1(t1) p2(t2) ... pm(tm) subject to:
     * strict  -> t_{k+1} <= t_k - gap - step (younger below, BP decreases)
     * non-strict -> t_{k+1} <= t_k - gap
     */
    private fun orderedNormalization(marginals: List<List<Double>>, n: Int, strict: Boolean, minGap: Double, step: Double): Double {
        val gapCells = Math.round(minGap / step).toInt()
        val shift = gapCells + (if (strict) 1 else 0)
        var prev = marginals.first().toDoubleArray()
        for (k in 1 until marginals.size) {
            val prefix = DoubleArray(n)
            prefix[0] = prev[0]
            for (j in 1 until n) prefix[j] = prefix[j - 1] + prev[j]
            val cur = DoubleArray(n)
            for (j in 0 until n) {
                val eligibleMax = j - shift
                cur[j] = if (eligibleMax < 0) 0.0 else marginals[k][j] * prefix[eligibleMax]
            }
            prev = cur
        }
        return prev.sum() * Math.pow(step, marginals.size.toDouble())
    }

    private fun shortHash(input: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }.take(12)
    }
}
