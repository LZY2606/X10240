package rc.model

import kotlinx.serialization.Serializable

@Serializable
data class CurvePoint(val calBP: Int, val ageBP: Double, val sigmaBP: Double)

/**
 * A fixed, named version of an offline calibration curve. Calendar years are
 * always cal BP; radiocarbon values are 14C BP. Points are strictly ordered
 * and no extrapolation is allowed beyond [firstYear]/[lastYear].
 */
@Serializable
data class CalibrationCurve(
    val curveId: String,
    val version: String,
    val label: String,
    val yearUnit: String = "cal BP",
    val ageUnit: String = "14C BP",
    val points: List<CurvePoint>,
    val sourceRef: String,
    val releasedAt: Long
) {
    val firstYear: Int get() = points.first().calBP
    val lastYear: Int get() = points.last().calBP
}

@Serializable
data class OffsetComponent(
    val weight: Double,
    val mean: Quantity,
    val sigma: Quantity,
    val sourceRef: String
)

/** A reservoir offset hypothesis: either a normal Delta R or an empirical mixture. */
@Serializable
data class OffsetHypothesis(
    val offsetId: String,
    val versionId: String,
    val label: String,
    val kind: String,
    val mean: Quantity? = null,
    val sigma: Quantity? = null,
    val components: List<OffsetComponent> = emptyList(),
    val sourceRef: String,
    val introducedEventSeq: Long = -1L
) {
    val key: String get() = "offset:$offsetId"
}

/**
 * A radiocarbon laboratory measurement. Amendments never overwrite the prior
 * row: they add a new version with [parentVersionId] set.
 */
@Serializable
data class Measurement(
    val measurementId: String,
    val versionId: String,
    val parentVersionId: String? = null,
    val material: String,
    val age: Quantity,
    val sigma: Quantity,
    val defaultOffsetId: String? = null,
    val eraSystemId: String? = null,
    val sourceRef: String,
    val note: String = "",
    val introducedEventSeq: Long = -1L
) {
    val key: String get() = "measurement:$measurementId"
}

@Serializable
data class SequenceConstraint(
    val sequenceId: String,
    val label: String,
    val orderedOlderToYounger: List<String>,
    val sourceRef: String,
    val note: String = "",
    val introducedEventSeq: Long = -1L
) {
    val key: String get() = "sequence:$sequenceId"
}

@Serializable
data class ExclusionDecision(
    val decisionId: String,
    val measurementId: String,
    val excluded: Boolean,
    val reason: String,
    val sourceRef: String,
    val note: String = "",
    val introducedEventSeq: Long = -1L
) {
    val key: String get() = "exclusion:$measurementId"
}

/** Human ruling on a sequence-conflict candidate adjustment. */
@Serializable
data class Adjudication(
    val adjudicationId: String,
    val sequenceId: String,
    val candidateId: String,
    val action: String, // accept | reject
    val note: String,
    val sourceRef: String,
    val conflictsWith: List<String> = emptyList(),
    val revoked: Boolean = false,
    val introducedEventSeq: Long = -1L
) {
    val key: String get() = "adjudication:$sequenceId"
}

/** Machine-readable judgment recorded during review; basis refs are resolved at append time. */
@Serializable
data class ReviewJudgment(
    val judgmentId: String,
    val statementCode: String,
    val status: String, // supported | refuted | undecided
    val note: String,
    val sourceRef: String,
    val basisRefs: List<String>,
    val introducedEventSeq: Long = -1L
)

@Serializable
data class ProjectInfo(
    val projectId: String,
    val title: String,
    val createdAt: Long,
    val activeCurveId: String,
    val activeCurveVersion: String,
    val defaultEraSystemId: String?
)
