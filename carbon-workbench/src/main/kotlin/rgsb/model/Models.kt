package rgsb.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * A domain value that keeps both the original textual representation submitted by the team
 * and the normalized representation used by the calculation layer.
 * Nothing is discarded "to make plotting work".
 */
@Serializable
data class Sourced(
    val raw: String,
    val unit: String,
    val source: String,
    val sourceLabel: String,
)

@Serializable
data class Quantity(
    val value: Double,
    val unit: String,
    val raw: String,
)

@Serializable
data class RawMeasurement(
    val id: String,
    val labCode: String,
    val context: String,
    val age: Sourced,                 // radiocarbon age, 14C BP
    val sigma: Sourced,               // lab standard deviation, 14C years
    val delta13c: Sourced? = null,    // optional, permil (kept verbatim)
    val parentId: String? = null,     // a revision points at the record it supersedes ("M1@v1")
    val rev: Int = 1,                 // stable id across revisions; rev grows
    val note: String = "",
)

/** Discrete mixture reservoir offset, e.g. marine reservoir delta R drawn from weighted experts. */
@Serializable
data class OffsetComponent(
    val deltaR: Double,               // 14C years
    val sigma: Double,                // 14C years
    val weight: Double,
    val rationale: String = "",
)

@Serializable
data class OffsetSpec(
    val id: String,
    val label: String,
    val components: List<OffsetComponent>,
    val source: String,
    val sourceLabel: String,
    val parentId: String? = null,
    val rev: Int = 1,
)

@Serializable
data class EraSystem(
    val id: String,
    val label: String,
    val zeroYearConvention: String,   // e.g. "cal BP 0 = 1950 CE (astronomical)"
    val bceCeCrossingAstronomicalYear: Int = 0,
    val source: String,
    val sourceLabel: String,
)

/** before(a,b) means calendar age of a is expected older than b (deeper in the section). */
@Serializable
data class SequenceLink(
    val before: String,
    val after: String,
    val relation: String = "older-than",
)

@Serializable
data class SequenceSpec(
    val id: String,
    val label: String,
    val links: List<SequenceLink>,
    val source: String,
    val sourceLabel: String,
)

@Serializable
data class ExclusionSpec(
    val id: String,
    val measurementIds: List<String>,
    val calendarYearFrom: Int,        // astronomical years, cal BP convention conversion documented in era
    val calendarYearTo: Int,
    val rationale: String,
    val source: String,
    val sourceLabel: String,
)

@Serializable
data class CurvePoint(
    val calYear: Int,                 // astronomical year (0 = 1 BCE, negatives allowed)
    val carbonAge: Double,            // 14C BP
    val uncertainty: Double,          // 14C years
)

@Serializable
data class CalibrationCurve(
    val id: String,
    val name: String,
    val version: String,
    val points: List<CurvePoint>,
    val source: String,
    val sourceLabel: String,
    val parentVersion: String? = null,
)

/**
 * Offline domain case pack: the "碳年候选席" material. Every section is independently sourced.
 */
@Serializable
data class CasePack(
    val packFormat: String,
    val packName: String,
    val packVersion: String,
    val era: EraSystem,
    val curves: List<CalibrationCurve>,
    val measurements: List<RawMeasurement>,
    val offsets: List<OffsetSpec>,
    val sequences: List<SequenceSpec> = emptyList(),
    val exclusions: List<ExclusionSpec> = emptyList(),
    val narrative: List<String> = emptyList(),
)

@Serializable
data class ImportDiagnostic(
    val severity: String,             // ERROR | WARNING | UNCONVERTED
    val section: String,
    val location: String,
    val message: String,
    val rawValue: String? = null,
)

@Serializable
data class ImportResult(
    val accepted: Boolean,
    val projectId: String,
    val diagnostics: List<ImportDiagnostic>,
    val importedEventIds: List<String>,
)

// ---------- derived calibration data ----------

@Serializable
data class IntervalEvidence(
    val intervalId: String,
    val yearFrom: Int,                // astronomical
    val yearTo: Int,
    val probability: Double,
    val hpdLevel: Double,             // 0.68 or 0.95
    val sumUnnormalizedWeights: Double,
    val gridCells: Int,
    val integrandFingerprint: String,
)

@Serializable
data class GridCell(
    val year: Int,
    val weight: Double,               // normalized
    val unnormalized: Double,
    val curveCarbonAge: Double,
    val curveSigma: Double,
)

@Serializable
data class CalibrationResult(
    val resultId: String,
    val measurementId: String,
    val curveId: String,
    val curveVersion: String,
    val offsetId: String?,
    val algorithmVersion: String,
    val parameterFingerprint: String,
    val directSources: List<String>,
    val gridStepYears: Int,
    val grid: List<GridCell>,
    val intervals: List<IntervalEvidence>,
    val multiModal: Boolean,
    val modeCount: Int,
    val normalizationConstant: Double,
    val crossesBceCe: Boolean,
    val status: String,               // CURRENT | STALE | ERROR | UNKNOWN
    val unknownReason: String? = null,
    val computationEventId: String,
)

@Serializable
data class SequenceVerdict(
    val link: SequenceLink,
    val satisfied: Boolean,
    val evidence: String,
    val candidateAdjustments: List<CandidateAdjustment>,
)

@Serializable
data class CandidateAdjustment(
    val candidateId: String,
    val kind: String,                 // OFFSET_CHANGE | EXCLUDE_RANGE | RE_MEASURE | REORDER
    val targetMeasurementId: String,
    val proposal: JsonElement,
    val rationale: String,
    val legal: Boolean,
)

@Serializable
sealed class DecisionOutcome {
    @Serializable @SerialName("ACCEPTED") data class Accepted(val intervalId: String? = null) : DecisionOutcome()
    @Serializable @SerialName("REJECTED") data class Rejected(val reason: String) : DecisionOutcome()
    @Serializable @SerialName("ADJUSTED") data class Adjusted(val candidateId: String, val patch: JsonElement) : DecisionOutcome()
}

@Serializable
data class DecisionRecord(
    val decisionId: String,
    val eventId: String,
    val targetResultId: String,
    val outcome: DecisionOutcome,
    val note: String,
    val conflictsWith: List<String> = emptyList(),
    val supersededBy: String? = null,
)

@Serializable
data class ViewSnapshot(
    val projectId: String,
    val viewEventId: String,
    val asOfVirtualTime: String,
    val measurements: List<RawMeasurement>,
    val curves: List<CalibrationCurve>,
    val offsets: List<OffsetSpec>,
    val era: EraSystem?,
    val sequences: List<SequenceSpec>,
    val exclusions: List<ExclusionSpec>,
    val results: List<CalibrationResult>,
    val sequenceVerdicts: List<SequenceVerdict>,
    val decisions: List<DecisionRecord>,
    val events: List<EventEnvelope>,
    val tips: Map<String, String>,
)
