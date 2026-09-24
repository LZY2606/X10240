package workbench

import kotlinx.serialization.Serializable

@Serializable
data class CurvePoint(val calBp: Double, val c14Age: Double, val sigma: Double)

@Serializable
data class MeasurementV(
    val id: String,
    val measurementId: String,
    val version: Int,
    val parent: String? = null,
    val labCode: String,
    val c14AgeBp: Double,
    val stdDev: Double? = null,
    val stdDevNote: String = "",
    val material: String,
    val source: String,
    val note: String = ""
)

@Serializable
data class CurveV(
    val id: String,
    val curveId: String,
    val version: Int,
    val name: String,
    val points: List<CurvePoint>,
    val source: String
)

@Serializable
data class OffsetV(
    val id: String,
    val offsetId: String,
    val version: Int,
    val parent: String? = null,
    val label: String,
    val meanYears: Double,
    val sigmaYears: Double,
    val source: String
)

@Serializable
data class ConstraintV(
    val id: String,
    val kind: String, // "BEFORE": aMeasurementId 早于 bMeasurementId (calBP 更大)
    val aMeasurementId: String,
    val bMeasurementId: String,
    val source: String
)

@Serializable
data class ExclusionV(
    val id: String,
    val measurementId: String,
    val fromCalBp: Double,
    val toCalBp: Double,
    val reason: String,
    val source: String
)

@Serializable
data class EraSystemV(val id: String, val era: String, val source: String)

@Serializable
data class CalPeak(
    val index: Int,
    val fromCalBp: Double,
    val toCalBp: Double,
    val probability: Double,
    val fromLabel: String,
    val toLabel: String
)

@Serializable
data class IntegrationEvidence(
    val gridStep: Double,
    val gridStart: Double,
    val gridEnd: Double,
    val normConstant: Double,
    val intervalMass: Double,
    val pointsInInterval: Int,
    val boundaryRule: String,
    val algoVersion: String
)

@Serializable
data class CalibrationResult(
    val id: String,
    val measurementId: String,
    val measurementVersion: Int,
    val curveId: String,
    val curveVersion: Int,
    val offsetId: String? = null,
    val offsetVersion: Int? = null,
    val eraSystem: String,
    val algoVersion: String,
    val fingerprint: String,
    val status: String, // OK | UNKNOWN | ERROR
    val diagnostics: List<String> = emptyList(),
    val peaks: List<CalPeak> = emptyList(),
    val evidence: Map<String, IntegrationEvidence> = emptyMap(),
    val gridStart: Double = 0.0,
    val gridEnd: Double = 0.0,
    val gridStep: Double = 1.0,
    val density: List<Double> = emptyList()
) {
    fun peakStableId(p: CalPeak) = "$id#p${p.index}"
}

@Serializable
data class CandidateAdjustment(
    val id: String,
    val measurementId: String,
    val fromCalBp: Double,
    val toCalBp: Double,
    val basis: String,
    val status: String = "PROPOSED" // PROPOSED | ACCEPTED | REJECTED
)

@Serializable
data class StratConflict(
    val constraintId: String,
    val description: String,
    val adjustments: List<CandidateAdjustment>
)

@Serializable
data class StratCheckResult(
    val id: String,
    val checkedConstraints: List<String>,
    val conflicts: List<StratConflict>,
    val basedOnResults: Map<String, String>
)

@Serializable
data class DecisionV(
    val id: String,
    val resultId: String,
    val peakIndex: Int? = null,
    val action: String, // ACCEPT_PEAK | REJECT_PEAK | ADJUST_PARAM | ACCEPT_ADJUSTMENT | REJECT_ADJUSTMENT
    val reason: String,
    val reviewer: String
)

@Serializable
data class UndoV(val id: String, val undoOf: String, val reason: String)
