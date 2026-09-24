package org.chrono.workbench.domain

import kotlinx.serialization.Serializable
import org.chrono.workbench.model.CalibrationResult

const val ORDER_CONFIDENCE = 0.95

@Serializable
data class CandidateAdjustment(
    val candidateId: String,
    val type: String,
    val targetObjectId: String,
    val description: String,
    val suggestedDeltaYears: Double,
    val currentValueYears: Double,
    val evidenceEventRef: String,
    val appliesTo: String
)

@Serializable
data class SequenceVerdict(
    val constraintId: String,
    val olderMeasurementId: String,
    val youngerMeasurementId: String,
    val probabilityOlder: Double,
    val satisfied: Boolean,
    val status: String,
    val candidates: List<CandidateAdjustment>,
    val note: String
)

object SequenceEngine {

    fun probabilityOlder(older: CalibrationResult, younger: CalibrationResult): Double {
        val yo = older.density
        val py = younger.density
        val cdf = DoubleArray(py.size + 1)
        for (j in py.indices) cdf[j + 1] = cdf[j] + py[j]
        var p = 0.0
        for (i in yo.indices) {
            p += yo[i] * (1.0 - cdf[i + 1])
        }
        return p
    }

    fun evaluate(
        constraintId: String,
        olderMeasurementId: String,
        youngerMeasurementId: String,
        older: CalibrationResult?,
        younger: CalibrationResult?,
        olderReservoirYears: Double,
        youngerReservoirYears: Double,
        eventRef: String
    ): SequenceVerdict {
        if (older == null || younger == null || older.status == "OUTSIDE_CURVE_UNKNOWN" || younger.status == "OUTSIDE_CURVE_UNKNOWN") {
            return SequenceVerdict(
                constraintId, olderMeasurementId, youngerMeasurementId,
                probabilityOlder = Double.NaN,
                satisfied = false,
                status = "UNKNOWN",
                candidates = emptyList(),
                note = "At least one member has no calendar support; ordering cannot be decided uniquely and all possibilities remain open."
            )
        }
        val p = probabilityOlder(older, younger)
        if (p >= ORDER_CONFIDENCE) {
            return SequenceVerdict(
                constraintId, olderMeasurementId, youngerMeasurementId, p, true,
                "SATISFIED", emptyList(),
                "P(older member is stratigraphically older) = ${"%.4f".fmt(p)} >= $ORDER_CONFIDENCE."
            )
        }
        if (p <= 1.0 - ORDER_CONFIDENCE) {
            val delta = (olderReservoirYears - youngerReservoirYears)
            val candidate = CandidateAdjustment(
                candidateId = "$constraintId#reservoir-adjust",
                type = "RESERVOIR_PARAMETER_REVISION",
                targetObjectId = olderMeasurementId,
                description = "Stratigraphy requires '$olderMeasurementId' below (older than) '$youngerMeasurementId', but calibrated order is reversed with probability ${"%.4f".fmt(p)}. " +
                    "Re-evaluate the reservoir offset of the deeper sample rather than altering the measurement.",
                suggestedDeltaYears = if (delta == 0.0) 250.0 else delta,
                currentValueYears = olderReservoirYears,
                evidenceEventRef = eventRef,
                appliesTo = "reservoir-offset of $olderMeasurementId"
            )
            return SequenceVerdict(
                constraintId, olderMeasurementId, youngerMeasurementId, p, false,
                "CONFLICTED", listOf(candidate),
                "Order reversed; the measurement itself is immutable, only a new reservoir/parameter revision may resolve it."
            )
        }
        return SequenceVerdict(
            constraintId, olderMeasurementId, youngerMeasurementId, p, false,
            "AMBIGUOUS", emptyList(),
            "Ordering probability ${"%.4f".fmt(p)} is not decisive; both orderings remain legal candidates."
        )
    }

    private fun String.fmt(v: Double): String = String.format(this, v)
}
