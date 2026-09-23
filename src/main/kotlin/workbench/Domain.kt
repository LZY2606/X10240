package workbench

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class Provenance(val origin: String, val contributor: String, val recordedAt: String)

/** A quantity keeps its original representation plus the normalized value.
 *  Unconvertible values stay in place with a diagnostic instead of being dropped. */
@Serializable
data class Quantity(
    val raw: String,
    val unit: String,
    val basis: String? = null,
    val value: Double? = null,
    val diagnostic: String? = null,
)

fun parseQuantity(raw: String, unit: String, basis: String? = null): Quantity {
    val trimmed = raw.trim()
    val parsed = trimmed.toDoubleOrNull()
    return if (parsed != null) Quantity(raw = raw, unit = unit, basis = basis, value = parsed)
    else Quantity(raw = raw, unit = unit, basis = basis, value = null,
        diagnostic = "unconvertible: '$raw' is not a number in unit '$unit'; kept raw at source position")
}

object SourceKind {
    const val MEASUREMENT = "measurement"
    const val CURVE_VERSION = "curveVersion"
    const val OFFSET_DISTRIBUTION = "offsetDistribution"
    const val ERA_SYSTEM = "eraSystem"
    const val SEQUENCE_CONSTRAINT = "sequenceConstraint"
    const val EXCLUSION_DECISION = "exclusionDecision"
    val ALL = listOf(MEASUREMENT, CURVE_VERSION, OFFSET_DISTRIBUTION, ERA_SYSTEM, SEQUENCE_CONSTRAINT, EXCLUSION_DECISION)
}

/** An immutable source record. Revisions create a new record with [parentId]
 *  pointing at the previous version; the original is never overwritten. */
@Serializable
data class SourceRecord(
    val id: String,
    val kind: String,
    val slug: String,
    val version: Int,
    val parentId: String? = null,
    val rawText: String,
    val payload: JsonObject,
    val provenance: Provenance,
    val createdSeq: Long,
)

object ResultKind {
    const val CALIBRATION = "calibration"
    const val SEQUENCE_EVALUATION = "sequenceEvaluation"
    const val OFFSET_COMPARISON = "offsetComparison"
}

/** A derived value: carries algorithm version, parameter fingerprint and its
 *  direct sources so any project can be replayed step by step. */
@Serializable
data class DerivedResult(
    val id: String,
    val kind: String,
    val subject: String,
    val algorithm: String,
    val fingerprint: String,
    val sourceIds: List<String>,
    val payload: JsonObject,
    val createdSeq: Long,
)

@Serializable
data class EventRecord(
    val seq: Long,
    val id: String,
    val at: String,
    val kind: String,
    val actor: String,
    val payload: JsonObject,
)

object EventKind {
    const val PROJECT_CREATED = "PROJECT_CREATED"
    const val SOURCES_IMPORTED = "SOURCES_IMPORTED"
    const val SOURCE_REVISED = "SOURCE_REVISED"
    const val CALIBRATION_COMPUTED = "CALIBRATION_COMPUTED"
    const val SEQUENCE_EVALUATED = "SEQUENCE_EVALUATED"
    const val OFFSET_COMPARED = "OFFSET_COMPARED"
    const val CONFLICT_RAISED = "CONFLICT_RAISED"
    const val DECISION_RECORDED = "DECISION_RECORDED"
    const val DECISION_UNDONE = "DECISION_UNDONE"
    const val CURVE_UPGRADED = "CURVE_UPGRADED"
    const val EXPORTED = "EXPORTED"
    const val IMPORTED = "IMPORTED"
    const val CASEPACK_STEP_LOADED = "CASEPACK_STEP_LOADED"
}

object DecisionAction {
    const val ACCEPT = "accept"
    const val REJECT = "reject"
    const val ADJUST = "adjust"
}

/** A human decision. Undo is itself an event; nothing is deleted. */
@Serializable
data class DecisionRecord(
    val id: String,
    val targetKind: String,
    val targetId: String,
    val action: String,
    val rationale: String,
    val actor: String,
    val params: JsonObject? = null,
    val createdSeq: Long,
    val undoneBy: String? = null,
)
