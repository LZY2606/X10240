package workbench

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }

@Serializable
data class CurvePoint(val calBP: Double, val c14Age: Double, val sigma: Double)

@Serializable
data class CurveData(
    val curveId: String,
    val version: String,
    val name: String = "",
    val unit: String = "calBP",
    val reference: String = "",
    val points: List<CurvePoint>
) {
    fun key() = "$curveId@$version"
}

@Serializable
data class Measurement(
    val sampleId: String,
    val version: Int = 1,
    val labCode: String,
    val c14Age: Double,
    val sigma: Double,
    val material: String = "",
    val chronology: String = "BP",
    val sourceRef: String = "",
    val parentVersion: Int? = null,
    val raw: String = ""
)

@Serializable
data class OffsetAssumption(
    val offsetId: String,
    val version: Int = 1,
    val label: String,
    val deltaR: Double,
    val deltaRSigma: Double,
    val unit: String = "yr",
    val sourceRef: String = "",
    val parentVersion: Int? = null
)

@Serializable
data class SequenceConstraint(
    val constraintId: String,
    val olderSampleId: String,
    val youngerSampleId: String,
    val basis: String = "",
    val sourceRef: String = ""
)

@Serializable
data class CalInterval(
    val index: Int,
    val level: Double,
    val fromCalBP: Double,
    val toCalBP: Double,
    val probability: Double,
    val fromCalendar: String,
    val toCalendar: String
)

@Serializable
data class CalibrationResult(
    val calibrationId: String,
    val sampleId: String,
    val measurementVersion: Int,
    val curveId: String,
    val curveVersion: String,
    val offsetId: String? = null,
    val offsetVersion: Int? = null,
    val algorithm: String,
    val paramFingerprint: String,
    val gridStart: Double,
    val gridStep: Double,
    val density: List<Double>,
    val intervals: List<CalInterval>,
    val sourceEventIds: List<String>,
    val note: String = ""
) {
    fun grid(): List<Double> = density.indices.map { gridStart + it * gridStep }
}

@Serializable
data class EvidenceSegment(
    val fromCalBP: Double,
    val toCalBP: Double,
    val meanDensity: Double,
    val contribution: Double
)

@Serializable
data class IntegrationEvidence(
    val calibrationId: String,
    val intervalIndex: Int,
    val level: Double,
    val fromCalBP: Double,
    val toCalBP: Double,
    val probability: Double,
    val algorithm: String,
    val gridStep: Double,
    val segments: List<EvidenceSegment>
)

@Serializable
data class Conflict(
    val conflictId: String,
    val constraintId: String,
    val olderSampleId: String,
    val youngerSampleId: String,
    val probabilityOrderHolds: Double,
    val status: String = "open"
)

@Serializable
data class Candidate(
    val candidateId: String,
    val conflictId: String,
    val kind: String,
    val description: String,
    val params: Map<String, String> = emptyMap(),
    val status: String = "pending"
)

@Serializable
data class Decision(
    val decisionId: String,
    val kind: String,
    val targetId: String,
    val actor: String,
    val rationale: String,
    val revoked: Boolean = false
)
