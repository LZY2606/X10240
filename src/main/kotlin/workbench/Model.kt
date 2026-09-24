package workbench

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** 每条材料都携带来源。 */
@Serializable
data class SourceRef(
    val citation: String,
    val uri: String? = null,
    val note: String? = null
)

@Serializable
data class Measurement(
    val id: String,
    val version: Int = 1,
    val parentId: String? = null,          // "id:version" of the revised parent
    val labCode: String,
    val c14Age: Double? = null,            // null when the raw value cannot be converted
    val sigma: Double? = null,
    val unit: String = "BP",
    val original: String,                  // 原始表示，导入后不可覆盖
    val source: SourceRef,
    val diagnostic: String? = null,        // 无法转换时保留在原位置并附诊断
    val createdSeq: Long = 0
)

@Serializable
data class CurvePoint(val calBp: Double, val c14Age: Double, val sigma: Double)

@Serializable
data class Curve(
    val id: String,
    val version: Int = 1,
    val parentId: String? = null,
    val name: String,
    val points: List<CurvePoint>,
    val source: SourceRef,
    val createdSeq: Long = 0
)

@Serializable
data class OffsetDist(
    val id: String,
    val version: Int = 1,
    val parentId: String? = null,
    val name: String,
    val mean: Double,
    val sigma: Double,
    val source: SourceRef,
    val createdSeq: Long = 0
)

@Serializable
data class EraSystem(
    val id: String,
    val name: String,
    val bpReference: Int = 1950,
    val source: SourceRef,
    val createdSeq: Long = 0
)

@Serializable
data class SequenceConstraint(
    val id: String,
    val olderId: String,     // measurement id that stratigraphy says is older
    val youngerId: String,   // measurement id that stratigraphy says is younger
    val note: String,
    val source: SourceRef,
    val createdSeq: Long = 0
)

@Serializable
data class ExclusionDecision(
    val id: String,
    val version: Int = 1,
    val measurementId: String,
    val excluded: Boolean,
    val rationale: String,
    val source: SourceRef,
    val createdSeq: Long = 0
)

@Serializable
data class IntegrationEvidence(
    val gridFrom: Int,
    val gridTo: Int,
    val fromCalBp: Double,
    val toCalBp: Double,
    val mass: Double,
    val boundaryClamped: Boolean,
    val curvePoints: List<CurvePoint>,
    val note: String
)

@Serializable
data class HpdInterval(
    val level: Double,
    val fromCalBp: Double,
    val toCalBp: Double,
    val mass: Double,
    val fromEra: String,
    val toEra: String,
    val evidence: IntegrationEvidence
)

@Serializable
data class ProbGrid(
    val startCalBp: Double,
    val step: Double,
    val probs: List<Double>      // normalized: sums to 1 for a fixed algorithm version
)

@Serializable
data class CalibrationRun(
    val id: String,
    val version: Int = 1,
    val measurementId: String,
    val measurementVersion: Int,
    val curveId: String,
    val curveVersion: Int,
    val offsetId: String? = null,
    val offsetVersion: Int? = null,
    val eraId: String,
    val algorithmVersion: String,
    val paramFingerprint: String,
    val grid: ProbGrid,
    val intervals: List<HpdInterval>,
    val kind: String = "calibration",          // "calibration" | "adjustment"
    val derivedFromCandidateId: String? = null,
    val createdSeq: Long = 0
)

@Serializable
data class CandidateAdjustment(
    val id: String,
    val kind: String,                          // "truncate-older" | "truncate-younger"
    val description: String,
    val targetMeasurementId: String,
    val clampMinCalBp: Double? = null,
    val clampMaxCalBp: Double? = null
)

@Serializable
data class Conflict(
    val id: String,
    val constraintId: String,
    val description: String,
    val candidates: List<CandidateAdjustment>,
    val createdSeq: Long = 0
)

@Serializable
data class Event(
    val seq: Long,
    val id: String,
    val ts: String,
    val actor: String,
    val type: String,
    val payload: JsonObject
)
