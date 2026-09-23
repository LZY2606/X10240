package gsb.model

import kotlinx.serialization.Serializable

/* ============================ provenance ============================ */

/** A traceable citation to an input fragment: file, byte offset, raw text. */
@Serializable
data class SourceRef(
    val sourceId: String,          // stable id of the import batch / file
    val label: String,             // human label e.g. "lab sheet row 3"
    val locator: String,           // e.g. "cases/raw/lab-notes.md#L12"
    val rawText: String,           // verbatim fragment, never normalised
    val importedAtVirtual: String // virtual ISO timestamp
)

@Serializable
data class Quantity(
    val raw: String,               // original representation, preserved on import
    val value: Double?,            // normalised numeric value in [unit] (null => unconvertible)
    val unit: String,              // normalised unit ("14C_BP", "calBP", "yr", "cal_yr_astronomical")
    val diagnostics: List<String> = emptyList() // why value stayed null, if so
) {
    val isUsable: Boolean get() = value != null
}

/* ============================ inputs (versioned, immutable) ============================ */

@Serializable
data class CalibrationCurve(
    val id: String,
    val version: String,           // e.g. "SynthIntraCal v1" / "v2"
    val parentVersion: String? = null,
    val introducedByEvent: String,
    /** anchorBP: calBP value of grid[0].yearBP; grid is ascending calBP order. */
    val anchorBP: Double,
    val stepBP: Double,
    val c14BP: List<Double>,
    val sigmaBP: List<Double>,
    val source: SourceRef
) {
    val yearsBP: List<Double> get() = c14BP.indices.map { anchorBP + it * stepBP }
    val minBP: Double get() = anchorBP
    val maxBP: Double get() = anchorBP + (c14BP.size - 1) * stepBP
}

@Serializable
data class Measurement(
    val id: String,
    val code: String,              // lab code e.g. "Beta-A"
    val context: String,
    val c14Age: Quantity,          // radiocarbon age BP
    val c14Sigma: Quantity,
    val deltaR: Quantity?,         // optional reservoir offset as submitted (yr)
    val curveVersion: String,      // curve version this measurement is bound to initially
    val eraSystem: String,         // "calBP" or "BCE_CE" coordinate basis declared on import
    val parentId: String? = null,  // revision lineage
    val supersededBy: String? = null,
    val source: SourceRef
)

/** A reservoir offset hypothesis: Gaussian mixture of reservoir scenarios. */
@Serializable
data class OffsetScenario(val mean: Double, val sigma: Double, val weight: Double)

@Serializable
data class OffsetHypothesis(
    val id: String,
    val measurementId: String,
    val name: String,              // e.g. "marine20", "no-reservoir", "mixture"
    val scenarios: List<OffsetScenario>,
    val parentId: String? = null,
    val source: SourceRef
)

@Serializable
enum class RelationType { EARLIER_THAN, LATER_THAN }

@Serializable
data class SequenceConstraint(
    val id: String,
    val youngerCode: String,       // the stratigraphically younger sample
    val olderCode: String,         // the stratigraphically older sample
    val rationale: String,
    val source: SourceRef
) {
    /** constraint: calendar calBP of older >= calBP of younger. */
}

@Serializable
enum class ExcludeStatus { EXCLUDED, REVIVED }

@Serializable
data class ExclusionDecision(
    val id: String,
    val measurementCode: String,
    val status: ExcludeStatus,
    val reason: String,
    val parentId: String? = null,
    val source: SourceRef
)

/* ============================ derived artefacts ============================ */

@Serializable
data class HpdInterval(
    val index: Int,
    val fromCalBP: Double,
    val toCalBP: Double,
    val probability: Double,
    val modeCalBP: Double
)

@Serializable
data class CalibrationResult(
    val id: String,                // stable identity
    val measurementId: String,
    val measurementCode: String,
    val algorithmVersion: String,
    val curveId: String,
    val curveVersion: String,
    val offsetHypothesisId: String,
    val gridAnchorBP: Double,
    val gridStepBP: Double,
    val posterior: List<Double>,   // normalised density per grid cell midpoint
    val hpds: List<HpdInterval>,
    val multiModal: Boolean,
    val status: ResultStatus,
    val staleBecause: List<String> = emptyList(),
    val paramFingerprint: String,
    val directInputs: List<String>,// ids: measurement, curve, offset hypothesis
    val producedByEvent: String,
    val virtualTime: String
)

@Serializable
enum class ResultStatus { CURRENT, STALE, REJECTED }

@Serializable
data class SequenceAnalysis(
    val id: String,
    val constraintId: String,
    val youngerResultId: String,
    val olderResultId: String,
    val consistentProbability: Double, // P(olderBP >= youngerBP) under posteriors
    val satisfied: Boolean,
    val conflict: Boolean,
    val candidateAdjustments: List<CandidateAdjustment>,
    val status: ResultStatus,
    val staleBecause: List<String> = emptyList(),
    val paramFingerprint: String,
    val directInputs: List<String>,
    val producedByEvent: String,
    val virtualTime: String
)

@Serializable
data class CandidateAdjustment(
    val id: String,
    val kind: String,              // "REVISE_OFFSET" | "CHANGE_CURVE" | "REVIEW_EXCLUSION" | "RECHECK_MEASUREMENT"
    val targetId: String,
    val description: String,
    val legality: String,          // "LEGAL" | "AMBIGUOUS"
    val accepted: Boolean? = null, // human verdict; null = undecided, all kept
    val rejectReason: String? = null
)

/* ============================ review / decisions ============================ */

@Serializable
enum class Verdict { ACCEPTED, REJECTED, PARAM_CHANGED }

@Serializable
data class IntervalEvidence(
    val resultId: String,
    val intervalIndex: Int,
    val fromCalBP: Double,
    val toCalBP: Double,
    val probability: Double,
    val quadrature: String,        // method + grid step fingerprint
    val cells: Int
)

@Serializable
data class ReviewDecision(
    val id: String,
    val verdict: Verdict,
    val targetResultId: String?,
    val targetAnalysisId: String?,
    val intervalIndex: Int?,
    val chosenParameterSet: String?, // fingerprint when PARAM_CHANGED
    val note: String,
    val undoneBy: String? = null,
    val parentDecisionId: String? = null,
    val source: SourceRef
)

/* ============================ events (the append-only log) ============================ */

@Serializable
sealed class DomainEvent {
    abstract val eventId: String
    abstract val seq: Long
    abstract val virtualTime: String
    abstract val actor: String
}

@Serializable
data class ProjectCreated(
    override val eventId: String, override val seq: Long, override val virtualTime: String,
    override val actor: String, val projectId: String, val name: String
) : DomainEvent()

@Serializable
data class CurveRegistered(
    override val eventId: String, override val seq: Long, override val virtualTime: String,
    override val actor: String, val curve: CalibrationCurve
) : DomainEvent()

@Serializable
data class MeasurementSubmitted(
    override val eventId: String, override val seq: Long, override val virtualTime: String,
    override val actor: String, val measurement: Measurement
) : DomainEvent()

@Serializable
data class OffsetSubmitted(
    override val eventId: String, override val seq: Long, override val virtualTime: String,
    override val actor: String, val hypothesis: OffsetHypothesis
) : DomainEvent()

@Serializable
data class ConstraintSubmitted(
    override val eventId: String, override val seq: Long, override val virtualTime: String,
    override val actor: String, val constraint: SequenceConstraint
) : DomainEvent()

@Serializable
data class ExclusionRecorded(
    override val eventId: String, override val seq: Long, override val virtualTime: String,
    override val actor: String, val decision: ExclusionDecision
) : DomainEvent()

@Serializable
data class CalibrationComputed(
    override val eventId: String, override val seq: Long, override val virtualTime: String,
    override val actor: String, val result: CalibrationResult, val evidence: IntervalEvidence
) : DomainEvent()

@Serializable
data class SequenceAnalyzed(
    override val eventId: String, override val seq: Long, override val virtualTime: String,
    override val actor: String, val analysis: SequenceAnalysis
) : DomainEvent()

@Serializable
data class ReviewRecorded(
    override val eventId: String, override val seq: Long, override val virtualTime: String,
    override val actor: String, val decision: ReviewDecision
) : DomainEvent()

@Serializable
data class DecisionUndone(
    override val eventId: String, override val seq: Long, override val virtualTime: String,
    override val actor: String, val undoneDecisionId: String, val reviewEventId: String
) : DomainEvent()

@Serializable
data class ProjectSnapshot(
    val projectId: String,
    val name: String,
    val algorithmVersion: String,
    val events: List<DomainEvent>
)
