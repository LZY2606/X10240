package org.chronocarbon

import kotlinx.serialization.Serializable

/** Named reservoir hypothesis applied to a measurement (a parameter, not a raw-data edit). */
@Serializable
data class OffsetHypothesis(
    val id: String,
    val measurementId: String,
    val name: String,
    val offset: OffsetDist,
    val provenance: Provenance,
    val parentId: String? = null,
    val active: Boolean = true,
    val superseded: Boolean = false
)

/** A claimed before/after (stratigraphic) relation between two measurements. */
@Serializable
data class SequenceConstraint(
    val id: String,
    val olderMeasurementId: String,
    val youngerMeasurementId: String,
    val rationale: String,
    val provenance: Provenance,
    val active: Boolean = true
)

/** Human exclusion / admission decision. Measurements are never rewritten by a decision. */
@Serializable
data class Decision(
    val id: String,
    val targetMeasurementId: String,
    val exclude: Boolean,
    val reason: String,
    val provenance: Provenance,
    val parentDecisionId: String? = null,
    val active: Boolean = true,
    val undone: Boolean = false
)

/** Rejection reason is first-class data. */
@Serializable
data class Rejection(val code: String, val message: String)

/** A legal adjustment proposed when sequence evidence conflicts. Applying it emits new input events. */
@Serializable
data class AdjustmentCandidate(
    val id: String,
    val analysisId: String,
    val kind: String, // change_offset | add_exclusion | relax_constraint | recheck_measurement
    val subjectId: String,
    val description: String,
    val proposedOffset: OffsetDist? = null,
    val status: String = "open", // open | accepted | rejected
    val decidedByEventId: String? = null,
    val rejection: Rejection? = null,
    val createdAtEventSeq: Long
)

/** Pairwise ordering evidence for one constraint under one derivation each. */
@Serializable
data class OrderingEvidence(
    val constraintId: String,
    val olderMeasurementId: String,
    val youngerMeasurementId: String,
    val probabilityOlderFirst: Double,
    val status: String, // consistent | weak | contradicted
    val message: String
)

@Serializable
data class SequenceAnalysis(
    val id: String,
    val evidence: List<OrderingEvidence>,
    val candidateIds: List<String>,
    val createdAtEventSeq: Long
)

/** Persisted derivation record. [stale] marks that a direct source changed; stale rows stay viewable. */
@Serializable
data class DerivationRecord(
    val id: String,
    val eventSeq: Long,
    val kind: String, // calibration | sequence
    val requestFingerprint: String,
    val algorithmVersion: String,
    val directSources: List<String>,
    val resultJson: String,
    val stale: Boolean = false,
    val supersededByDerivationId: String? = null
)
