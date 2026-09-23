package rcw

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

const val ALGO_VERSION = "calib-1.0"
const val NORM_METHOD = "trapezoid-grid-1yr"

/* ---------------- Sources (immutable, versioned, provenance-bearing) ------------- */

@Serializable
data class Provenance(val ref: String, val recordedBy: String, val note: String = "")

@Serializable
data class MeasurementSource(
    val labCode: String,
    val layer: String,
    val material: String,
    val rawAge: String,          // original representation, e.g. "2300 ± 25 BP"
    val unit: String,            // declared unit, kept verbatim
    val provenance: Provenance
)

@Serializable
data class CurveSource(
    val name: String,
    val version: String,
    val calUnit: String,        // "calBP"
    val c14Unit: String,        // "14C yr BP"
    val points: List<List<Double>> // [calBP, c14Age, sigma] ascending calBP
)

@Serializable
data class OffsetSource(
    val name: String,
    val distribution: String,   // "normal"
    val deltaRMean: Double,     // 14C years
    val deltaRSigma: Double,
    val unit: String,           // "14C yr"
    val appliesTo: List<String>, // measurement series ids
    val provenance: Provenance
)

@Serializable
data class EraSource(
    val system: String,         // "BCE/CE"
    val yearZero: Boolean,      // false for conventional BCE/CE
    val reference: String,      // "calBP=1950"
    val provenance: Provenance
)

@Serializable
data class SequenceConstraint(val before: String, val after: String, val note: String = "")

@Serializable
data class StratigraphySource(
    val constraints: List<SequenceConstraint>,
    val provenance: Provenance
)

@Serializable
data class ExclusionSource(
    val sample: String,
    val excluded: Boolean,
    val reason: String,
    val provenance: Provenance
)

/* ---------------- Derived results ---------------- */

@Serializable
data class IntervalEvidence(
    val intervalId: String,
    val fromIdx: Int,
    val toIdx: Int,
    val gridStep: Double,
    val pointCount: Int,
    val trapezoidSum: Double,
    val totalMass: Double,
    val method: String,
    val minDensity: Double,
    val headLikelihoods: List<Double>,
    val tailLikelihoods: List<Double>
)

@Serializable
data class IntervalResult(
    val intervalId: String,       // stable: <derivedId>#i95-0
    val fromCalBP: Double,
    val toCalBP: Double,
    val fromEra: String,
    val toEra: String,
    val probability: Double,
    val evidence: IntervalEvidence
)

@Serializable
data class PeakResult(val xCalBP: Double, val density: Double, val era: String)

@Serializable
data class CalibrationResult(
    val sample: String,
    val status: String,           // OK | UNKNOWN | ERROR
    val diagnostics: List<String>,
    val measurementId: String,
    val curveId: String,
    val offsetId: String? = null,
    val eraId: String,
    val curveName: String,
    val curveVersion: String,
    val rawAge: String,
    val unit: String,
    val ageBP: Double? = null,
    val sigmaBP: Double? = null,
    val offsetMean: Double? = null,
    val offsetSigma: Double? = null,
    val effectiveAgeBP: Double? = null,
    val effectiveSigmaBP: Double? = null,
    val gridFrom: Double,
    val gridTo: Double,
    val gridStep: Double,
    val likelihoodMass: Double,
    val normalizationMethod: String,
    val density: List<Double>,
    val peaks: List<PeakResult>,
    val intervals68: List<IntervalResult>,
    val intervals95: List<IntervalResult>,
    val truncatedLeft: Boolean,
    val truncatedRight: Boolean
)

@Serializable
data class SequenceCheck(
    val before: String,
    val after: String,
    val beforeCalibration: String,
    val afterCalibration: String,
    val pBefore: Double,
    val conflict: Boolean,
    val note: String
)

@Serializable
data class StratigraphyResult(
    val stratigraphyId: String,
    val eraId: String,
    val checks: List<SequenceCheck>,
    val candidateIds: List<String>
)

@Serializable
data class OffsetHypothesisSummary(
    val key: String,             // source id or "whatif(dR=..)"
    val label: String,
    val deltaRMean: Double,
    val deltaRSigma: Double,
    val peaks: List<PeakResult>,
    val intervals95: List<IntervalResult>,
    val density: List<Double>,
    val archived: Boolean
)

@Serializable
data class OffsetComparisonResult(
    val sample: String,
    val gridFrom: Double,
    val gridTo: Double,
    val gridStep: Double,
    val hypotheses: List<OffsetHypothesisSummary>
)

/* ---------------- Candidates & events ---------------- */

@Serializable
data class CandidatePayload(
    val kind: String,            // exclude-sample | accept-overlap | revise-offset
    val sample: String? = null,
    val constraintBefore: String? = null,
    val constraintAfter: String? = null,
    val reason: String,
    val conflictKey: String
)

@Serializable
data class EventPayload(
    val summary: String,
    val data: JsonObject = JsonObject(emptyMap())
)

/* ---------------- Case pack ---------------- */

@Serializable
data class SourceInput(
    val series: String,
    val kind: String,            // MEASUREMENT | CURVE | OFFSET | ERA | STRATIGRAPHY | EXCLUSION
    val content: JsonObject
)

@Serializable
sealed class PackAction {
    @Serializable
    data class Import(val source: SourceInput) : PackAction()
    @Serializable
    data class Revise(val series: String, val content: JsonObject) : PackAction()
    @Serializable
    data class Recompute(val sample: String) : PackAction()
    @Serializable
    data class RecomputeStratigraphy(val unit: Int = 1) : PackAction()
    @Serializable
    data class Decide(
        val candidateKind: String,
        val sample: String? = null,
        val before: String? = null,
        val after: String? = null,
        val accept: Boolean,
        val reviewer: String,
        val note: String
    ) : PackAction()
    @Serializable
    data class Undo(val candidateKind: String, val sample: String? = null,
                    val before: String? = null, val after: String? = null,
                    val reviewer: String, val note: String) : PackAction()
}

@Serializable
data class PackChapter(val id: String, val title: String, val description: String, val actions: List<PackAction>)

@Serializable
data class CasePack(
    val format: String,
    val formatVersion: Int,
    val project: String,
    val narrative: String,
    val chapters: List<PackChapter>
)

class ImportException(val errors: List<String>) : RuntimeException(errors.joinToString("; "))
