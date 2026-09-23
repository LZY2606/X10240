package workbench

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }

@Serializable
data class CurvePoint(val calBp: Int, val c14Age: Double, val sigma: Double)

@Serializable
data class CurveVersion(
    val id: String, val name: String, val version: Int,
    val points: List<CurvePoint>, val source: String, val unit: String = "cal BP / 14C BP"
)

@Serializable
data class Measurement(
    val id: String, val labCode: String, val ageBp: Double, val sigma: Double,
    val material: String, val source: String, val version: Int = 1,
    val parentId: String? = null, val unit: String = "14C yr BP"
)

@Serializable
data class OffsetModel(
    val id: String, val name: String, val deltaR: Double, val sigma: Double,
    val distribution: String = "normal", val source: String, val version: Int = 1,
    val parentId: String? = null, val unit: String = "14C yr"
)

@Serializable
data class EraSystem(val id: String, val system: String, val note: String, val source: String)

@Serializable
data class SequenceConstraint(
    val id: String, val olderId: String, val youngerId: String,
    val relation: String = "older-than", val source: String
)

@Serializable
data class ExclusionDecision(
    val id: String, val measurementId: String, val excluded: Boolean,
    val reason: String, val source: String
)

@Serializable
data class Peak(val calBpCenter: Int, val height: Double, val label: String)

@Serializable
data class EvidenceStep(val calBp: Int, val density: Double, val contribution: Double, val cumulative: Double)

@Serializable
data class IntervalEvidence(
    val index: Int, val fromCalBp: Int, val toCalBp: Int,
    val level: Double, val probability: Double,
    val fromLabel: String, val toLabel: String,
    val steps: List<EvidenceStep>, val note: String
)

@Serializable
data class Calibration(
    val id: String, val measurementId: String, val curveRef: String, val offsetRef: String,
    val eraRef: String, val algoVersion: String, val paramFingerprint: String,
    val sources: List<String>, val gridFrom: Int, val gridTo: Int,
    val density: List<Double>, val peaks: List<Peak>, val intervals: List<IntervalEvidence>,
    val boundary: Boolean, val effectiveAge: Double, val effectiveSigma: Double,
    val status: String = "current"
)

@Serializable
data class Adjudication(
    val id: String, val calibrationId: String, val action: String,
    val reason: String, val decidedBy: String, val supersedes: String? = null
)

@Serializable
data class ConflictCandidate(
    val id: String, val constraintId: String, val kind: String,
    val description: String, val status: String = "proposed"
)

@Serializable
data class Event(val seq: Int, val id: String, val type: String, val at: String, val payload: JsonObject)

fun bceCeLabel(calBp: Int): String {
    val ceYear = 1950 - calBp
    return if (ceYear > 0) "$ceYear CE" else "${1 - ceYear} BCE"
}
