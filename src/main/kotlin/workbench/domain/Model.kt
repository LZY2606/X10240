package workbench.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.security.MessageDigest

val CanonicalJson = Json {
    prettyPrint = false
    encodeDefaults = true
    explicitNulls = false
}

fun sha256(text: String): String =
    MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
        .joinToString("") { "%02x".format(it) }

/** Kinds of first-class sources. Every source keeps its original representation. */
enum class SourceKind { MEASUREMENT, CURVE, OFFSET, CHRONOLOGY, SEQUENCE, EXCLUSION }

/**
 * A submitted source. Raw inputs are immutable once submitted; a revision is a
 * new record with [parentId] pointing at the previous version.
 */
@Serializable
data class SourceRecord(
    val id: String,
    val kind: SourceKind,
    val lineageId: String,
    val version: Int,
    val parentId: String?,
    val submittedBy: String,
    val submittedAt: String,
    val raw: JsonObject,
)

/** A derived value: carries algorithm version, parameter fingerprint and direct sources. */
@Serializable
data class DerivedResult(
    val id: String,
    val kind: String, // CALIBRATION | SEQUENCE_CHECK
    val algorithmVersion: String,
    val paramFingerprint: String,
    val sourceIds: List<String>,
    val payload: JsonObject,
    val createdAt: String,
    val superseded: Boolean = false,
)

/** Machine-readable event. The event log is the source of truth. */
@Serializable
data class Event(
    val seq: Long,
    val id: String,
    val type: String,
    val at: String,
    val actor: String,
    val payload: JsonObject,
)

object EventTypes {
    const val SOURCE_SUBMITTED = "SourceSubmitted"
    const val SOURCE_REVISED = "SourceRevised"
    const val CALIBRATION_COMPUTED = "CalibrationComputed"
    const val SEQUENCE_CHECKED = "SequenceChecked"
    const val CONFLICT_DETECTED = "ConflictDetected"
    const val CANDIDATE_ADJUSTMENT_PROPOSED = "CandidateAdjustmentProposed"
    const val ADJUDICATION_MADE = "AdjudicationMade"
    const val ADJUDICATION_REVERTED = "AdjudicationReverted"
    const val IMPORT_REJECTED = "ImportRejected"
    const val EXPORTED = "Exported"
}

/** A validation diagnostic attached to rejected or partially accepted input. */
@Serializable
data class Diagnostic(val field: String, val message: String, val severity: String)

class ImportException(val diagnostics: List<Diagnostic>) :
    Exception(diagnostics.joinToString("; ") { "${it.field}: ${it.message}" })
