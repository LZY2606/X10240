package workbench.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

const val SEQUENCE_ALGORITHM_VERSION = "seq-1.1.0"

@Serializable
data class SequenceConstraint(val youngerSampleId: String, val olderSampleId: String, val note: String = "")

@Serializable
data class CandidateAdjustment(
    val id: String,
    val sampleId: String,
    val kind: String, // TRUNCATE_OLDER_TAIL | TRUNCATE_YOUNGER_TAIL
    val cutCalBp: Int,
    val cutLabel: String,
    val retainedMass: Double,
    val rationale: String,
)

@Serializable
data class SequenceCheckPayload(
    val constraint: SequenceConstraint,
    val youngerCalibrationId: String,
    val olderCalibrationId: String,
    val pOrder: Double, // P(younger truly younger) under independent calibrated distributions
    val status: String, // CONSISTENT | CONFLICT
    val candidates: List<CandidateAdjustment>,
)

object SequenceAnalyzer {
    /**
     * Checks an ordering constraint against two calibrated distributions.
     * Conflicts produce candidate adjustments (truncations of the calibrated
     * distributions); measurements themselves are never rewritten. When the
     * decision is not unique, all legal candidates are kept.
     */
    fun check(
        constraint: SequenceConstraint,
        younger: CalibrationPayload,
        youngerCalibrationId: String,
        older: CalibrationPayload,
        olderCalibrationId: String,
    ): SequenceCheckPayload {
        val pOrder = probYounger(younger, older)
        val status = if (pOrder >= 0.5) "CONSISTENT" else "CONFLICT"
        val candidates = ArrayList<CandidateAdjustment>()
        if (status == "CONFLICT") {
            // Candidate 1: truncate the younger sample's old tail at the older sample's youngest mode.
            val cutY = older.modes.maxByOrNull { it.calBp }?.calBp ?: older.gridFromCalBp
            val c1 = truncation(constraint.youngerSampleId, younger, cutY, "TRUNCATE_OLDER_TAIL",
                "Keep only part of ${constraint.youngerSampleId} younger than oldest mode of ${constraint.olderSampleId}")
            if (c1 != null) candidates.add(c1)
            // Candidate 2: truncate the older sample's young tail at the younger sample's oldest mode.
            val cutO = younger.modes.minByOrNull { it.calBp }?.calBp ?: younger.gridToCalBp
            val c2 = truncation(constraint.olderSampleId, older, cutO, "TRUNCATE_YOUNGER_TAIL",
                "Keep only part of ${constraint.olderSampleId} older than youngest mode of ${constraint.youngerSampleId}")
            if (c2 != null) candidates.add(c2)
        }
        return SequenceCheckPayload(constraint, youngerCalibrationId, olderCalibrationId, pOrder, status, candidates)
    }

    private fun truncation(sampleId: String, cal: CalibrationPayload, cutCalBp: Int, kind: String, rationale: String): CandidateAdjustment? {
        val fromIdx = 0
        val toIdx = (cal.gridToCalBp - cal.gridFromCalBp) / cal.gridStep
        val cutIdx = ((cutCalBp - cal.gridFromCalBp) / cal.gridStep).coerceIn(fromIdx, toIdx)
        val retained = when (kind) {
            // keep younger part: calBp <= cut
            "TRUNCATE_OLDER_TAIL" -> cal.densities.subList(0, cutIdx + 1).sum()
            // keep older part: calBp >= cut
            else -> cal.densities.subList(cutIdx, cal.densities.size).sum()
        }
        if (retained < 0.05) return null // not a legal candidate: almost nothing retained
        return CandidateAdjustment(
            id = "cand-" + sha256("$sampleId|$kind|$cutCalBp|${cal.curveVersion}").take(10),
            sampleId = sampleId,
            kind = kind,
            cutCalBp = cutCalBp,
            cutLabel = Chronology.calBpToCalYear(cutCalBp.toDouble()).toString(),
            retainedMass = retained,
            rationale = rationale,
        )
    }

    /** P(younger sample's true date is younger than older sample's) via grid convolution. */
    fun probYounger(younger: CalibrationPayload, older: CalibrationPayload): Double {
        // younger.calBp < older.calBp means truly younger
        val yo = younger.gridFromCalBp
        val oo = older.gridFromCalBp
        var p = 0.0
        for (i in younger.densities.indices) {
            val yCal = yo + i * younger.gridStep
            // mass of older distribution at calBp > yCal
            val jStart = ((yCal - oo) / older.gridStep) + 1
            if (jStart >= older.densities.size) continue
            var tail = 0.0
            var j = maxOf(jStart, 0)
            while (j < older.densities.size) { tail += older.densities[j]; j++ }
            p += younger.densities[i] * tail
        }
        return p
    }
}
