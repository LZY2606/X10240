package chrono.state

import chrono.calib.ALGORITHM_VERSION
import chrono.calib.CalibrationEngine
import chrono.domain.AdjustmentProposal
import chrono.domain.CalibrationCurve
import chrono.domain.CalibrationRun
import chrono.domain.CalendarInterval
import chrono.domain.GridPoint
import chrono.domain.Hypothesis
import chrono.domain.Measurement
import chrono.domain.Reservoir
import chrono.domain.SequenceConstraint
import chrono.domain.SequenceEvaluation
import chrono.domain.canonicalJson
import java.security.MessageDigest

/**
 * Deterministic identity for derived artifacts.
 * Re-running at the same inputs yields the SAME ids; any one-source change yields
 * a different run id only for dependent hypotheses.
 */
object Derivation {

    private val HEX = "0123456789abcdef".toCharArray()

    fun sha12(s: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(s.toByteArray())
        val sb = StringBuilder(12)
        for (i in 0 until 6) {
            sb.append(HEX[(digest[i].toInt() ushr 4) and 0xF])
            sb.append(HEX[digest[i].toInt() and 0xF])
        }
        return sb.toString()
    }

    data class FingerprintMaterial(
        val algorithm: String,
        val measurementId: String,
        val ageBP: Double,
        val sigmaBP: Double,
        val reservoirJson: String?,
        val curveId: String,
        val curveVersion: String,
        val curvePointHash: String,
        val gridStepBP: Int,
        val levels: List<String>
    )

    fun curvePointHash(curve: CalibrationCurve): String =
        sha12(canonicalJson.encodeToString(
            CalibrationCurve.CurvePoint.serializer().let { ser ->
                kotlinx.serialization.builtins.ListSerializer(ser)
            },
            curve.points
        ))

    fun parameterFingerprint(
        measurement: Measurement,
        reservoir: Reservoir?,
        curve: CalibrationCurve
    ): String {
        val material = FingerprintMaterial(
            algorithm = ALGORITHM_VERSION,
            measurementId = measurement.id,
            ageBP = measurement.ageBP,
            sigmaBP = measurement.sigmaBP,
            reservoirJson = reservoir?.let {
                canonicalJson.encodeToString(Reservoir.serializer(), it)
            },
            curveId = curve.id,
            curveVersion = curve.version,
            curvePointHash = curvePointHash(curve),
            gridStepBP = CalibrationEngine.GRID_STEP,
            levels = listOf("68.2%", "95.4%")
        )
        return sha12(canonicalJson.encodeToString(FingerprintMaterial.serializer(), material))
    }

    fun runId(hypothesisId: String, curveId: String, fingerprint: String): String =
        "run-${hypothesisId}-${curveId}-$fingerprint"

    fun intervalId(runId: String, level: String, index: Int): String {
        val lvl = if (level == "68.2%") "68" else "95"
        return "int-${runId.drop(4)}-$lvl-$index"
    }

    fun evaluationId(constraintId: String, runAId: String, runBId: String): String =
        "seqeval-$constraintId-${sha12(runAId + "|" + runBId)}"

    fun proposalId(evaluationId: String, hypothesisId: String, kind: String, delta: Double?): String =
        "prop-$evaluationId-$hypothesisId-$kind-${delta?.let { String.format("%.0f", it) } ?: "x"}"

    /**
     * Build a full calibration run for one (hypothesis, curve) pair.
     * All interval ids are assigned deterministically here.
     */
    fun buildRun(
        hypothesis: Hypothesis,
        measurement: Measurement,
        reservoir: Reservoir?,
        curve: CalibrationCurve,
        excluded: Boolean,
        createdAtEvent: Long,
        sources: List<String>
    ): CalibrationRun {
        val fp = parameterFingerprint(measurement, reservoir, curve)
        val rid = runId(hypothesis.id, curve.id, fp)
        val gridResult = CalibrationEngine.computeGrid(measurement, reservoir, curve)
        val grid = gridResult.calBP.indices.map {
            GridPoint(gridResult.calBP[it], gridResult.density[it])
        }
        val intervals68 = CalibrationEngine.highestDensityIntervals(gridResult, "68.2%")
        val intervals95 = CalibrationEngine.highestDensityIntervals(gridResult, "95.4%")
        val intervals = (intervals68 + intervals95).mapIndexed { globalIndex, iv ->
            iv.copy(intervalId = intervalId(rid, iv.level, iv.index))
        }
        return CalibrationRun(
            runId = rid,
            hypothesisId = hypothesis.id,
            measurementId = measurement.id,
            reservoirId = reservoir?.id,
            curveId = curve.id,
            curveVersion = curve.version,
            algorithm = ALGORITHM_VERSION,
            parameterFingerprint = fp,
            gridStepBP = CalibrationEngine.GRID_STEP,
            grid = grid,
            intervals = intervals,
            totalMass = gridResult.density.sum(),
            boundaryLowTruncated = gridResult.lowTruncated,
            boundaryHighTruncated = gridResult.highTruncated,
            modeCount = CalibrationEngine.countModes(gridResult),
            excluded = excluded,
            diagnostic = if (measurement.unconvertible)
                measurement.diagnostic else null,
            sources = sources,
            createdAtEvent = createdAtEvent
        )
    }

    /**
     * Evaluate one stratigraphic relationship against two runs.
     * Conflicts yield *candidate adjustments*, never measurement rewrites.
     */
    fun evaluateConstraint(
        constraint: SequenceConstraint,
        runA: CalibrationRun,
        runB: CalibrationRun,
        gridOf: (CalibrationRun) -> CalibrationEngine.GridResult
    ): SequenceEvaluation {
        val gA = gridOf(runA)
        val gB = gridOf(runB)
        val kind = if (constraint.isCoeval) "coeval" else "order"
        val (older, younger, same) = CalibrationEngine.evaluateSequence(gA, gB, constraint.isCoeval)

        val violated: Boolean
        val proposals = mutableListOf<AdjustmentProposal>()
        val evalBase = evaluationId(constraint.id, runA.runId, runB.runId)

        if (constraint.isCoeval) {
            violated = same < 0.5
            if (violated) {
                // cannot decide a unique fix; surface candidates for both directions
                proposals += AdjustmentProposal(
                    proposalId = proposalId(evalBase, runA.hypothesisId, "offset-shift", 50.0),
                    hypothesisId = runA.hypothesisId,
                    kind = "offset-shift",
                    suggestedDeltaBP = 50.0,
                    expectedImprovement = same,
                    note = "尝试将 A 的储库偏移 +50 14C yr BP（候选，不改写测量）"
                )
                proposals += AdjustmentProposal(
                    proposalId = proposalId(evalBase, runB.hypothesisId, "offset-shift", -50.0),
                    hypothesisId = runB.hypothesisId,
                    kind = "offset-shift",
                    suggestedDeltaBP = -50.0,
                    expectedImprovement = same,
                    note = "尝试将 B 的储库偏移 -50 14C yr BP（候选，不改写测量）"
                )
            }
        } else {
            // A declared older than B: violation when P(A older) is not dominant
            violated = older < 0.5
            if (violated) {
                val shortfall = 0.5 - older
                proposals += AdjustmentProposal(
                    proposalId = proposalId(evalBase, runA.hypothesisId, "offset-increase", 50.0),
                    hypothesisId = runA.hypothesisId,
                    kind = "offset-increase",
                    suggestedDeltaBP = 50.0,
                    expectedImprovement = shortfall,
                    note = "将较老样本的储库偏移上调 50 14C yr BP 可能恢复层序（候选）"
                )
                proposals += AdjustmentProposal(
                    proposalId = evalBase.let { proposalId(it, runB.hypothesisId, "offset-decrease", 50.0) },
                    hypothesisId = runB.hypothesisId,
                    kind = "offset-decrease",
                    suggestedDeltaBP = 50.0,
                    expectedImprovement = shortfall,
                    note = "将较年轻样本的储库偏移下调 50 14C yr BP 可能恢复层序（候选）"
                )
                proposals += AdjustmentProposal(
                    proposalId = proposalId(evalBase, runA.hypothesisId, "recheck-lab", null),
                    hypothesisId = runA.hypothesisId,
                    kind = "recheck-lab",
                    expectedImprovement = shortfall,
                    note = "无法唯一决定：保留全部合法候选，建议复核实验室报告，不直接改写测量"
                )
            }
        }

        return SequenceEvaluation(
            evaluationId = evalBase,
            constraintId = constraint.id,
            kind = kind,
            runAId = runA.runId,
            runBId = runB.runId,
            pOlder = older,
            pYounger = younger,
            pCoeval = if (constraint.isCoeval) same else null,
            violated = violated,
            proposals = proposals
        )
    }
}
