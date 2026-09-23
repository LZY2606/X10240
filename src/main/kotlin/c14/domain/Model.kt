package c14.domain

import kotlinx.serialization.Serializable

const val ALGO_VERSION = "c14-calib-1"
const val DEFAULT_GRID_STEP_YR = 5

/**
 * Every imported document carries a source record. Values never stand alone:
 * they point back to the document (lab sheet, curve file, stratigraphic note)
 * they came from.
 */
@Serializable
data class Source(
    val id: String,
    val kind: String,
    val title: String,
    val ref: String,
    val receivedRevision: Int = 1
)

@Serializable
data class Knot(val calBp: Int, val c14AgeBp: Double)

/**
 * A fixed, versioned calibration curve (cal BP -> radiocarbon BP).
 * Knots are interpolated linearly; the fixed version + knot list is what makes
 * a calibration exactly reproducible.
 */
@Serializable
data class CalibrationCurve(
    val id: String,
    val name: String,
    val version: String,
    val sourceId: String,
    val knots: List<Knot>,
    val parentCurveId: String? = null,
    val ageUnit: String = "14C yr BP",
    val calAxis: String = "cal BP (years before 1950 CE)"
) {
    val minBp: Int get() = knots.minOf { it.calBp }
    val maxBp: Int get() = knots.maxOf { it.calBp }
}

/**
 * A laboratory radiocarbon measurement. Stored exactly as submitted; later
 * corrections are expressed as revisions (new measurement with parent link),
 * never by overwriting the original.
 */
@Serializable
data class Measurement(
    val id: String,
    val sampleId: String,
    val labCode: String,
    val ageBp: Double,
    val sigmaBp: Double,
    val sourceId: String,
    val material: String? = null,
    val parentMeasurementId: String? = null,
    val ageUnit: String = "14C yr BP",
    val rawText: String? = null
)

/**
 * Reservoir offset model. R is subtracted from the measured radiocarbon age;
 * the uncertainty adds in variance. May be Gaussian or discrete (a mixture
 * of hypotheses).
 */
@Serializable
data class OffsetComponent(val shiftBp: Double, val sigmaBp: Double, val weight: Double = 1.0)

@Serializable
data class OffsetModel(
    val id: String,
    val name: String,
    val sourceId: String,
    val meanBp: Double,
    val sigmaBp: Double,
    val components: List<OffsetComponent> = emptyList(),
    val discrete: Boolean = components.isNotEmpty()
)

/** The chosen offset hypothesis for a sample. */
@Serializable
data class Assumption(
    val sampleId: String,
    val offsetId: String?,
    val eventSeq: Int,
    val reason: String,
    val sourceId: String?
)

/** Directed ordering link: older sample -> younger sample. */
@Serializable
data class SequenceLink(
    val id: String,
    val olderSample: String,
    val youngerSample: String,
    val sourceId: String,
    val note: String = ""
)

/** One normalized posterior grid cell. */
@Serializable
data class Cell(
    val calBp: Int,
    val density: Double,
    val cumLeft: Double,
    val cumRight: Double
)

/** Highest posterior density interval; interval ids are stable by sorted rank. */
@Serializable
data class Interval(
    val id: String,
    val fromBp: Int,
    val toBp: Int,
    val probability: Double,
    val level: Double,
    val rank: Int,
    val modeBp: Int,
    val evidenceFromIndex: Int,
    val evidenceToIndex: Int,
    val cells: List<Cell> = emptyList()
)

@Serializable
data class Mode(val calBp: Int, val label: String, val density: Double)

/**
 * A calibration result. Inputs (measurement, curve, offset, grid, algorithm)
 * are frozen via fingerprints so the result can always be re-derived.
 */
@Serializable
data class CalibrationResult(
    val id: String,
    val sampleId: String,
    val measurementId: String,
    val curveId: String,
    val offsetId: String?,
    val algoVersion: String,
    val gridStep: Int,
    val paramFingerprint: String,
    val cells: List<Cell>,
    val intervals68: List<Interval>,
    val intervals95: List<Interval>,
    val modes: List<Mode>,
    val supportMass: Double,
    val outsideMass: Double,
    val boundaryTruncated: Boolean,
    val eraCross: Boolean,
    val directSources: List<String>,
    val eventSeq: Int
) {
    fun cellAt(bp: Int): Cell? = cells.firstOrNull { it.calBp == bp }
}

/** A candidate adjustment proposed in response to a sequence contradiction. */
@Serializable
data class CandidateAdjustment(
    val id: String,
    val analysisId: String,
    val kind: String, // REVISE_OFFSET | REVISE_ORDER | REVISE_MEASUREMENT
    val description: String,
    val affectedObject: String,
    val proposedOffsetId: String? = null,
    val proposedMeasurementId: String? = null
)

@Serializable
data class PairProbability(
    val linkId: String,
    val older: String,
    val younger: String,
    val consistent: Boolean,
    val probability: Double,
    val jointIntervals: List<List<Int>>
)

@Serializable
data class SequenceAnalysis(
    val id: String,
    val links: List<String>,
    val pairProbabilities: List<PairProbability>,
    val consistent: Boolean,
    val candidates: List<CandidateAdjustment>,
    val resolvedBy: String? = null,
    val eventSeq: Int
)

@Serializable
data class Exclusion(
    val sampleId: String,
    val reason: String,
    val sourceId: String,
    val decisionId: String,
    val eventSeq: Int = -1
)

@Serializable
data class DecisionView(
    val id: String,
    val kind: String, // ACCEPT | REJECT_CALENDAR_INTERVAL | EXCLUDE_SAMPLE | ADJUST_PARAMETER | REVOKE
    val subject: String,
    val rationale: String,
    val sourceId: String,
    val supersedesDecisionId: String? = null,
    val eventSeq: Int = -1
)

/** Stored event envelope; the append-only log is the system of record. */
@Serializable
data class Event(
    val seq: Int,
    val id: String,
    val type: String,
    val payloadJson: String,
    val sourceId: String?,
    val actor: String,
    val atMillis: Long,
    val parentHash: String,
    val hash: String
)

/** Command envelope. Commands express intent; the engine derives events. */
@Serializable
data class Command(
    val type: String,
    val payloadJson: String,
    val sourceId: String? = null,
    val actor: String = "reviewer"
)
