package workbench.model

import kotlinx.serialization.Serializable

/**
 * Domain models for the radiocarbon calibration workbench.
 *
 * Coordinate conventions
 * ----------------------
 * All internal calendar coordinates are "cal BP": years before 1950 CE (1950 = 0 cal BP).
 * Display conversion to BCE/CE uses the astronomical convention:
 *   calBP 1949 -> year 1 CE ; calBP 1950 -> year 0 (= 1 BCE) ; calBP 1951 -> year -1 (= 2 BCE).
 *
 * Every domain quantity carries a unit; original representations are kept verbatim on import.
 */

@Serializable
data class Quantity(
    val value: Double,
    val unit: String,
    val rawText: String? = null,
    val conversionDiagnostic: String? = null
) {
    init {
        require(unit.isNotBlank()) { "quantity unit must not be blank" }
    }
}

/** Source provenance attached to every input fragment. */
@Serializable
data class Source(
    val sourceId: String,
    val kind: String,                 // lab-report | curve-publication | reservoir-study | field-log | analyst-note
    val reference: String,            // human readable citation / document id
    val pageOrField: String? = null,
    val retrievedAt: String? = null   // ISO timestamp, virtual clock allowed
)

@Serializable
data class CurveKnot(val calBP: Double, val radiocarbonBP: Double, val sigma14C: Double)

/**
 * A named calibration curve version. Knots are interpolated linearly (piecewise-linear).
 * Two curves with the same [versionId] are byte-identical inputs and therefore reproducible.
 */
@Serializable
data class CalibrationCurve(
    val curveId: String,              // stable id, e.g. synthetic-wiggle
    val versionId: String,            // e.g. v1, v2
    val label: String,
    val knots: List<CurveKnot>,
    val source: Source,
    val supersedesVersion: String? = null,
    val notes: String? = null
) {
    val fingerprint: String get() = stableFingerprint()

    fun stableFingerprint(): String =
        workbench.util.Hashing.sha256(
            buildString {
                append(curveId); append('|'); append(versionId)
                for (k in knots) append('|').append(k.calBP).append(',').append(k.radiocarbonBP).append(',').append(k.sigma14C)
            }
        )

    /** Domain range. */
    val minCalBP: Double get() = knots.first().calBP
    val maxCalBP: Double get() = knots.last().calBP

    /** Linear interpolation with boundary handling: outside the knot range we return null (no extrapolation). */
    fun interpolate(calBP: Double): CurveKnot? {
        if (knots.isEmpty()) return null
        if (calBP < knots.first().calBP || calBP > knots.last().calBP) return null
        var lo = 0
        var hi = knots.size - 1
        if (calBP <= knots.first().calBP) return knots.first()
        if (calBP >= knots.last().calBP) return knots.last()
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (knots[mid].calBP <= calBP) lo = mid else hi = mid
        }
        val a = knots[lo]; val b = knots[hi]
        if (b.calBP == a.calBP) return a
        val t = (calBP - a.calBP) / (b.calBP - a.calBP)
        return CurveKnot(
            calBP = calBP,
            radiocarbonBP = a.radiocarbonBP + t * (b.radiocarbonBP - a.radiocarbonBP),
            sigma14C = a.sigma14C + t * (b.sigma14C - a.sigma14C)
        )
    }
}

/**
 * A reservoir offset distribution in 14C years (years subtracted from the measured 14C age).
 * Gaussian or empirical (discrete weighted) forms are first-class.
 */
@Serializable
sealed class OffsetDistribution {
    abstract val offsetId: String
    abstract val label: String
    abstract val source: Source

    @Serializable
    data class Gaussian(
        override val offsetId: String,
        override val label: String,
        override val source: Source,
        val meanYears: Double,
        val sigmaYears: Double
    ) : OffsetDistribution()

    @Serializable
    data class Empirical(
        override val offsetId: String,
        override val label: String,
        override val source: Source,
        /** offset in 14C years -> weight (need not be normalized; calibrator normalizes). */
        val weights: Map<Double, Double>
    ) : OffsetDistribution()
}

@Serializable
data class RadiocarbonMeasurement(
    val measurementId: String,
    val label: String,
    /** Lab-reported conventional radiocarbon age, e.g. 2050 14C yr BP. */
    val age14C: Quantity,
    val sigma14C: Quantity,
    val ageSystem: String,           // "14C BP" etc — declared era system of the lab report
    val source: Source,
    val parentMeasurementId: String? = null
)

@Serializable
data class SequenceConstraint(
    val youngerMeasurementId: String,
    val olderMeasurementId: String,
    val source: Source,
    val note: String? = null
)

/** A contiguous calendar interval of a calibrated (or constrained) density. */
@Serializable
data class CalendarInterval(
    val intervalId: String,
    val fromCalBP: Double,
    val toCalBP: Double,
    val probabilityMass: Double,
    val level: String,               // "68%" | "95%"
    val rank: Int                    // ordering within the result, by calendar position
)

/** One discrete integration term: probability contribution of one calendar-year bin. */
@Serializable
data class IntegrationTerm(val calBP: Int, val density: Double, val contribution: Double)

@Serializable
data class CalibrationResult(
    val resultId: String,
    val measurementId: String,
    val curveVersionId: String,
    val curveFingerprint: String,
    val offsetId: String?,
    val algorithmVersion: String,
    val paramFingerprint: String,
    val gridStepYears: Double,
    val rangeCalBP: Pair<Double, Double>,
    /** normalized density on a fixed 1-yr grid */
    val density: List<Pair<Int, Double>>,
    val totalMass: Double,
    val intervals: List<CalendarInterval>,
    val peaks: List<Peak>,
    val status: ResultStatus,
    val createdAtEventSeq: Long,
    val staleBecause: List<String> = emptyList(),
    val diagnostics: List<String> = emptyList()
)

@Serializable
data class Peak(val centerCalBP: Int, val mass: Double, val rank: Int)

@Serializable
enum class ResultStatus { CURRENT, STALE, REJECTED, UNKNOWN }

/** Candidate adjustment produced by a sequence check — never mutates a measurement. */
@Serializable
data class SequenceCandidate(
    val candidateId: String,
    val constraintSourceId: String,
    val youngerMeasurementId: String,
    val olderMeasurementId: String,
    val kind: String,                 // EXCLUDE-TAIL | RESTRICT-INTERVAL | CONFLICT
    val proposal: String,
    val affectedIntervalIds: List<String>,
    val legalIntervals: List<CalendarInterval>,
    val conflict: Boolean,
    val rationale: String,
    val status: CandidateStatus,
    val createdAtEventSeq: Long
)

@Serializable
enum class CandidateStatus { PROPOSED, ACCEPTED, REJECTED, SUPERSEDED }

@Serializable
data class Decision(
    val decisionId: String,
    val targetType: String,          // INTERVAL | CANDIDATE | RESULT
    val targetId: String,
    val action: String,              // ACCEPT | REJECT | ADJUST
    val reason: String,
    val source: Source,
    val parentDecisionId: String?,
    val eventSeq: Long,
    val undone: Boolean = false,
    val undoOfEventSeq: Long? = null
)

@Serializable
data class ProvenanceEdge(
    val fromId: String,              // direct source id
    val toId: String,                // derived id
    val relation: String             // USES-CURVE | USES-MEASUREMENT | USES-OFFSET | DERIVED-BY | DECIDED
)
