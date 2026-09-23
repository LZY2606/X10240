package workbench

import kotlinx.serialization.Serializable

/** 虚拟时钟：测试注入固定时间，生产用系统时间。 */
interface Clock { fun now(): Long }
class SystemClock : Clock { override fun now(): Long = System.currentTimeMillis() }
class VirtualClock(var t: Long = 1_700_000_000_000L) : Clock {
    override fun now(): Long = t
    fun advance(ms: Long) { t += ms }
}

enum class SourceKind { MEASUREMENT, CURVE, OFFSET_HYPOTHESIS, CHRONOLOGY, STRAT_CONSTRAINT, EXCLUSION_DECISION }

@Serializable
data class Provenance(val origin: String, val reference: String, val note: String = "")

@Serializable
data class SourceRecord(
    val id: String,
    val version: Int,
    val parentId: String? = null,
    val kind: String,
    val payload: String,
    val provenance: Provenance,
    val diagnostics: List<String> = emptyList(),
    val submittedAt: Long,
    val seq: Long = 0,
)

// ---- 各类源的 payload（原始表示，导入时原样保留） ----

@Serializable
data class MeasurementPayload(
    val labCode: String,
    val c14AgeBP: Double,
    val sigma: Double,
    val unit: String = "14C yr BP",
    val material: String = "",
)

@Serializable
data class CurvePoint(val calBP: Int, val c14BP: Double, val sigma: Double)

@Serializable
data class CurvePayload(
    val name: String,
    val points: List<CurvePoint>,
    val coordinate: String = "cal BP (1950)",
)

@Serializable
data class OffsetPayload(
    val label: String,
    val normalMean: Double? = null,
    val normalSigma: Double? = null,
    val samples: List<Double>? = null,
    val unit: String = "14C yr",
)

@Serializable
data class ChronologyPayload(val system: String, val bpReference: Int = 1950, val note: String = "")

@Serializable
data class StratConstraintPayload(
    val olderId: String,
    val youngerId: String,
    val relation: String = "older-than",
    val note: String = "",
)

@Serializable
data class ExclusionPayload(val targetId: String, val reason: String, val active: Boolean = true)

// ---- 派生层：校准结果（多峰密度，不压成平均年） ----

@Serializable
data class Peak(val startBP: Int, val endBP: Int, val modeBP: Int, val probability: Double)

@Serializable
data class CalibrationResult(
    val id: String,
    val measurementId: String,
    val measurementVersion: Int,
    val curveId: String,
    val curveVersion: Int,
    val offsetId: String,
    val offsetVersion: Int,
    val algoVersion: String,
    val fingerprint: String,
    val gridStartBP: Int,
    val gridEndBP: Int,
    val step: Int,
    val density: List<Double>,
    val peaks: List<Peak>,
    val diagnostics: List<String>,
    val createdAt: Long,
    val seq: Long = 0,
)

@Serializable
data class EvidencePoint(val calBP: Int, val likelihood: Double, val weight: Double)

@Serializable
data class IntervalEvidence(
    val id: String,
    val resultId: String,
    val fromBP: Int,
    val toBP: Int,
    val probability: Double,
    val normalization: Double,
    val points: List<EvidencePoint>,
    val algoVersion: String,
    val fingerprint: String,
    val createdAt: Long,
    val seq: Long = 0,
)

// ---- 层序冲突与候选调整 ----

@Serializable
data class CandidateChange(val measurementId: String, val fromOffsetId: String?, val toOffsetId: String)

@Serializable
data class ConflictRecord(
    val id: String,
    val constraintId: String,
    val olderResultId: String,
    val youngerResultId: String,
    val probabilityOlder: Double,
    val status: String, // OPEN / RESOLVED
    val createdAt: Long,
    val seq: Long = 0,
)

@Serializable
data class CandidateRecord(
    val id: String,
    val conflictId: String,
    val description: String,
    val changes: List<CandidateChange>,
    val resultingProbability: Double,
    val status: String, // PENDING / ACCEPTED / REJECTED
    val createdAt: Long,
    val seq: Long = 0,
)

@Serializable
data class DecisionRecord(
    val id: String,
    val candidateId: String,
    val accepted: Boolean,
    val actor: String,
    val note: String,
    val undone: Boolean = false,
    val createdAt: Long,
    val seq: Long = 0,
)

// ---- 事件日志（机器可读判断依据） ----

@Serializable
data class Event(
    val id: String,
    val seq: Long,
    val ts: Long,
    val type: String,
    val actor: String,
    val payload: String,
)

object EventTypes {
    const val SOURCE_SUBMITTED = "SOURCE_SUBMITTED"
    const val SOURCE_REVISED = "SOURCE_REVISED"
    const val CALIBRATION_RUN = "CALIBRATION_RUN"
    const val INTERVAL_EVALUATED = "INTERVAL_EVALUATED"
    const val CONSTRAINT_ADDED = "CONSTRAINT_ADDED"
    const val CONFLICT_DETECTED = "CONFLICT_DETECTED"
    const val CANDIDATE_PROPOSED = "CANDIDATE_PROPOSED"
    const val DECISION_MADE = "DECISION_MADE"
    const val DECISION_UNDONE = "DECISION_UNDONE"
    const val CURVE_UPGRADED = "CURVE_UPGRADED"
    const val PROJECT_EXPORTED = "PROJECT_EXPORTED"
    const val PROJECT_IMPORTED = "PROJECT_IMPORTED"
    const val CASEPACK_LOADED = "CASEPACK_LOADED"
}
