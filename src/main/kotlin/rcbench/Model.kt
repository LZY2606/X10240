package rcbench

import kotlinx.serialization.Serializable

fun refOf(id: String, version: Int): String = "$id:$version"
fun parseRef(ref: String): Pair<String, Int> {
    val i = ref.lastIndexOf(':')
    return ref.substring(0, i) to ref.substring(i + 1).toInt()
}

@Serializable
data class Provenance(val source: String, val detail: String = "")

@Serializable
data class Measurement(
    val id: String,
    val version: Int,
    val parent: String? = null,
    val labCode: String,
    val ageBP: Double,
    val sigma: Double,
    val material: String = "",
    val context: String = "",
    val raw: String,
    val source: Provenance,
)

@Serializable
data class CurvePoint(val calBP: Double, val c14: Double, val sigma: Double)

@Serializable
data class CalibrationCurve(
    val id: String,
    val version: Int,
    val parent: String? = null,
    val name: String,
    val points: List<CurvePoint>,
    val source: Provenance,
)

@Serializable
data class ReservoirOffset(
    val id: String,
    val version: Int,
    val parent: String? = null,
    val mean: Double,
    val sd: Double,
    val source: Provenance,
)

@Serializable
data class EraSystem(
    val id: String,
    val version: Int,
    val parent: String? = null,
    val convention: String,
    val source: Provenance,
)

@Serializable
data class SequenceConstraint(
    val id: String,
    val version: Int,
    val parent: String? = null,
    val beforeId: String,
    val afterId: String,
    val note: String = "",
    val source: Provenance,
)

@Serializable
data class ExclusionDecision(
    val id: String,
    val version: Int,
    val parent: String? = null,
    val measurementId: String,
    val excluded: Boolean,
    val reason: String,
    val source: Provenance,
)

@Serializable
data class DensityGrid(
    val fromCalBP: Double,
    val step: Double,
    val values: List<Double>,
    val norm: Double,
)

@Serializable
data class Peak(val calBP: Double, val height: Double)

@Serializable
data class EvidenceSlice(val fromCalBP: Double, val toCalBP: Double, val density: Double, val mass: Double)

@Serializable
data class CalInterval(
    val id: String,
    val level: Double,
    val fromCalBP: Double,
    val toCalBP: Double,
    val probability: Double,
    val threshold: Double,
    val segments: List<EvidenceSlice>,
)

@Serializable
data class CalibrationResult(
    val id: String,
    val measurementRef: String,
    val curveRef: String,
    val offsetRef: String? = null,
    val eraRef: String,
    val algorithmVersion: String,
    val paramFingerprint: String,
    val grid: DensityGrid,
    val peaks: List<Peak>,
    val intervals: List<CalInterval>,
    val deps: List<String>,
    val computedAt: String,
)

@Serializable
data class CandidateAdjustment(
    val id: String,
    val kind: String,
    val measurementId: String,
    val intervalId: String? = null,
    val rationale: String,
    var status: String = "proposed",
)

@Serializable
data class SequenceConflict(
    val id: String,
    val constraintRef: String,
    val pOrder: Double,
    val candidates: List<CandidateAdjustment>,
)

@Serializable
data class SequenceAnalysis(
    val id: String,
    val algorithmVersion: String,
    val paramFingerprint: String,
    val conflicts: List<SequenceConflict>,
    val satisfied: List<String>,
    val deps: List<String>,
    val computedAt: String,
)

@Serializable
data class ReviewDecision(
    val id: String,
    val targetId: String,
    val action: String,
    val rationale: String,
    val actor: String,
    val at: String,
    val undone: Boolean = false,
)

@Serializable
data class Event(
    val seq: Long,
    val id: String,
    val type: String,
    val payload: String,
    val at: String,
    val actor: String,
)

@Serializable
data class Diagnostic(val stage: String, val field: String, val message: String, val raw: String = "")

@Serializable
data class ExportBundle(val format: String, val events: List<Event>)
