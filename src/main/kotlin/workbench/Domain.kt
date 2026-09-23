package workbench

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

val JsonFmt = Json { prettyPrint = false; encodeDefaults = true; ignoreUnknownKeys = true }

enum class InputKind { MEASUREMENT, CURVE, RESERVOIR_OFFSET, ERA_SYSTEM, STRATIGRAPHY, EXCLUSION }

enum class DerivedKind { CALIBRATION, STRAT_CONFLICT, STRAT_CANDIDATE, OFFSET_COMPARISON }

enum class DerivedStatus { CURRENT, STALE }

enum class EventType {
    INPUT_SUBMITTED, INPUT_REVISED, CALIBRATION_COMPUTED, STRAT_CHECKED,
    DECISION_ACCEPT, DECISION_REJECT, PARAM_ADJUSTED, CONFLICT_RESOLVED,
    UNDO, EXPORTED, IMPORTED, SCENARIO_STEP
}

data class InputRecord(
    val id: String,          // "<streamId>@v<version>"
    val streamId: String,
    val version: Int,
    val parentId: String?,   // "<streamId>@v<version-1>"
    val kind: InputKind,
    val payload: JsonObject,
    val provenance: String,
    val createdSeq: Long
)

data class DerivedRecord(
    val id: String,
    val kind: DerivedKind,
    val algoVersion: String,
    val paramFingerprint: String,
    val sourceIds: List<String>,   // direct sources, each "<streamId>@v<n>"
    val payload: JsonObject,
    val createdSeq: Long
)

data class EventRecord(
    val seq: Long,
    val id: String,          // "evt-<seq>"
    val type: EventType,
    val payload: JsonObject,
    val vtime: Long
)

// ---------- typed input payloads ----------

@Serializable
data class MeasurementPayload(
    val labCode: String,
    val material: String,
    val ageBp: Double,
    val sigma: Double,
    val reservoirStreamId: String? = null,
    val unit: String = "14C yr BP",
    val rawNotation: String? = null
)

@Serializable
data class CurvePoint(val calBp: Int, val c14Age: Double, val sigma: Double)

@Serializable
data class CurvePayload(
    val curveName: String,
    val basedOn: String,
    val points: List<CurvePoint>
)

@Serializable
data class ReservoirPayload(
    val name: String,
    val deltaR: Double,
    val deltaRSigma: Double,
    val unit: String = "14C yr"
)

@Serializable
data class EraSystemPayload(
    val name: String,
    val referenceYearCe: Int = 1950,
    val hasYearZero: Boolean = false
)

@Serializable
data class StratConstraint(val olderStreamId: String, val youngerStreamId: String, val note: String = "")

@Serializable
data class StratigraphyPayload(val name: String, val constraints: List<StratConstraint>)

@Serializable
data class ExclusionPayload(val measurementStreamId: String, val excluded: Boolean, val reason: String)

// ---------- typed derived payloads ----------

@Serializable
data class CalPeak(val calBp: Int, val height: Double)

@Serializable
data class CalInterval(
    val id: String,
    val level: Double,       // 0.682 / 0.954
    val fromBp: Int,
    val toBp: Int,
    val probability: Double
)

@Serializable
data class CalibrationPayload(
    val measurementId: String,
    val curveId: String,
    val reservoirId: String?,
    val eraId: String,
    val gridStartBp: Int,
    val gridEndBp: Int,
    val gridStep: Int,
    val densities: List<Double>,
    val peaks: List<CalPeak>,
    val intervals: List<CalInterval>,
    val diagnostics: List<String>,
    val excluded: Boolean = false
)

@Serializable
data class StratConflictPayload(
    val constraint: StratConstraint,
    val olderInterval: CalInterval,
    val youngerInterval: CalInterval,
    val detail: String
)

@Serializable
data class StratCandidatePayload(
    val conflictId: String,
    val strategy: String,    // TRUNCATE_OLDER | TRUNCATE_YOUNGER | JOINT_REWEIGHT
    val description: String,
    val adjustedFromBp: Int,
    val adjustedToBp: Int,
    val targetStreamId: String,
    val measurementRewritten: Boolean = false
)

@Serializable
data class OffsetComparisonRow(
    val reservoirId: String,
    val deltaR: Double,
    val deltaRSigma: Double,
    val calibrationId: String,
    val intervals: List<CalInterval>
)

@Serializable
data class OffsetComparisonPayload(val measurementId: String, val rows: List<OffsetComparisonRow>)
