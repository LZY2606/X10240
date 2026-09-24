package org.gsb.radiocarbon.model

/**
 * Domain model for the radiocarbon calibration workbench.
 * Every domain quantity keeps its unit/coordinate basis; raw textual input is
 * preserved on the source records so a failed normalization never deletes data.
 */

/** A value together with the physical unit it was expressed in. */
data class UnitValue(val value: Double, val unit: String) {
    init {
        require(unit.isNotBlank()) { "unit must not be blank" }
    }
}

// ---------------------------------------------------------------------------
// Era systems (BCE/CE bookkeeping)
// ---------------------------------------------------------------------------

enum class EraConvention(val code: String) {
    /** Astronomical counting: year 0 exists (1 BCE -> 0, 2 BCE -> -1). */
    ASTRONOMICAL("AstronomicalSigned"),

    /** Traditional historian counting: no year zero; 1 BCE is followed by 1 CE. */
    CULTURAL_BCE_CE("BCE/CE-no-year-zero");

    companion object {
        fun fromCode(code: String): EraConvention =
            entries.firstOrNull { it.code == code }
                ?: throw IllegalArgumentException("unknown era convention: $code")
    }
}

data class EraSystem(
    val eraSystemId: String,
    val label: String,
    val convention: EraConvention,
    /** Anchor: calBP = 0 corresponds to this Gregorian year (1950 by definition). */
    val bpEpochYear: Int = 1950,
    val sourceRef: String = ""
)

/** One calendar position rendered under a particular era convention. */
data class EraLabel(val signedYear: Int, val text: String, val isBce: Boolean)

object EraConverter {
    /** Convert calBP years before [era.bpEpochYear] to a label under [era]. */
    fun label(calBP: Int, era: EraSystem): EraLabel {
        val signed = era.bpEpochYear - calBP // astronomical signed year
        return when (era.convention) {
            EraConvention.ASTRONOMICAL -> {
                val text = when {
                    signed >= 0 -> "$signed CE (astronomical)"
                    else -> "${-signed} BCE-astronomical"
                }
                EraLabel(signed, text, signed < 0)
            }
            EraConvention.CULTURAL_BCE_CE -> when {
                signed >= 1 -> EraLabel(signed, "$signed CE", false)
                signed == 0 -> EraLabel(0, "1 BCE (cultural; no year zero)", true)
                else -> EraLabel(signed, "${1 - signed} BCE", true) // -1 -> 2 BCE
            }
        }
    }

    /** True when the interval [startCalBP, endCalBP] straddles the 1 BCE/1 CE boundary. */
    fun crossesBceCe(startCalBP: Int, endCalBP: Int, era: EraSystem): Boolean {
        require(startCalBP >= endCalBP) { "calBP interval must be older->younger: $startCalBP..$endCalBP" }
        // The cultural boundary lies between signed year 0 (1 BCE) and 1 CE,
        // i.e. between calBP 1950 and 1949.
        val boundary = era.bpEpochYear
        return startCalBP >= boundary && endCalBP <= boundary - 1
    }
}

// ---------------------------------------------------------------------------
// Calibration curves
// ---------------------------------------------------------------------------

data class CurvePoint(val calBP: Int, val c14Age: Double, val sigma: Double) {
    init {
        require(sigma >= 0.0) { "curve sigma must be non-negative at calBP=$calBP" }
    }
}

data class CalibrationCurve(
    val curveId: String,
    val version: String,
    val publishedLabel: String,
    val points: List<CurvePoint>,
    val sourceRef: String
) {
    val span: IntRange = points.first().calBP..points.last().calBP

    /** Linear interpolation of the curve mean; null outside the sampled span. */
    fun muAt(calBP: Int): Double? = interp(calBP) { it.c14Age }
    fun sigmaAt(calBP: Int): Double? = interp(calBP) { it.sigma }

    private inline fun interp(calBP: Int, pick: (CurvePoint) -> Double): Double? {
        if (points.isEmpty() || calBP < points.first().calBP || calBP > points.last().calBP) return null
        var lo = 0
        var hi = points.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (points[mid].calBP <= calBP) lo = mid else hi = mid
        }
        val a = points[lo]
        val b = points[hi]
        if (a.calBP == calBP) return pick(a)
        if (b.calBP == calBP) return pick(b)
        if (a.calBP == b.calBP) return pick(a)
        val f = (calBP - a.calBP).toDouble() / (b.calBP - a.calBP)
        return pick(a) + f * (pick(b) - pick(a))
    }

    fun ref(): String = "$curveId@$version"
}

// ---------------------------------------------------------------------------
// Measurements (immutable, versioned)
// ---------------------------------------------------------------------------

enum class DiagnosticSeverity { INFO, WARN, ERROR }

data class Diagnostic(
    val code: String,
    val severity: DiagnosticSeverity,
    val message: String,
    val calBP: Int? = null
)

data class MeasurementVersion(
    val measurementId: String,
    val version: Int,
    val parentVersion: Int?,
    val labCode: String,
    val material: String,
    val c14Age: Double,
    val sigma: Double,
    val unit: String,
    /** Exactly what the importing file contained, retained before any normalization. */
    val rawRepresentation: String,
    val sourceRef: String,
    val diagnostics: List<Diagnostic> = emptyList()
) {
    fun ref(): String = "$measurementId@v$version"
}

// ---------------------------------------------------------------------------
// Reservoir offset distributions
// ---------------------------------------------------------------------------

sealed interface OffsetDistribution {
    val unit: String

    data class Gaussian(val deltaR: Double, val sigma: Double, override val unit: String) : OffsetDistribution
    data class Empirical(val samples: List<Double>, override val unit: String) : OffsetDistribution {
        init { require(samples.isNotEmpty()) { "empirical offset needs at least one sample" } }
    }
}

data class OffsetHypothesis(
    val offsetId: String,
    val version: Int,
    val parentVersion: Int?,
    val label: String,
    val appliesToMaterial: String?,
    val distribution: OffsetDistribution,
    val sourceRef: String
) {
    fun ref(): String = "$offsetId@v$version"
}

// ---------------------------------------------------------------------------
// Calibration results
// ---------------------------------------------------------------------------

enum class CalibrationStatus { OK, OUTSIDE_CURVE, UNKNOWN, ERROR }

data class Peak(
    val rank: Int,
    val modeCalBP: Int,
    val modeDensity: Double
)

data class HpdInterval(
    val levelPct: Double,
    val ordinal: Int,
    val startCalBP: Int,
    val endCalBP: Int,
    val probabilityMass: Double,
    /** ordinal ranks of other intervals with the same mass within numeric tolerance. */
    val tiedWith: List<Int> = emptyList()
)

/** One grid cell of the integral behind an interval probability (audit evidence). */
data class IntegralCell(
    val calBP: Int,
    val curveMu: Double,
    val curveSigma: Double,
    val offsetMean: Double,
    val likelihood: Double,
    val weight: Double
)

data class CalibrationResult(
    val fingerprint: String,
    val algorithmVersion: String,
    val measurementRef: String,
    val curveRef: String,
    val offsetRef: String?,
    val eraSystemId: String,
    val gridStep: Int,
    val levelsPct: List<Double>,
    val status: CalibrationStatus,
    val diagnostics: List<Diagnostic>,
    /** calBP grid, older first (descending calBP). */
    val gridCalBP: List<Int>,
    val weights: List<Double>,
    val totalLikelihood: Double,
    val normalized: Boolean,
    val peaks: List<Peak>,
    val intervals: List<HpdInterval>,
    val requestHash: String,
    val directSources: Map<String, String>
) {
    fun weightAt(calBP: Int): Double? {
        val idx = gridCalBP.indexOf(calBP)
        return if (idx >= 0) weights[idx] else null
    }

    /** Probability mass of [startCalBP, endCalBP] inclusive, with the integral cells. */
    fun intervalEvidence(startCalBP: Int, endCalBP: Int, curves: (String) -> CalibrationCurve?, offsetMeanAt: (Int) -> Double):
        Pair<Double, List<IntegralCell>> {
        var mass = 0.0
        val cells = ArrayList<IntegralCell>()
        for ((i, t) in gridCalBP.withIndex()) {
            if (t in endCalBP..startCalBP) {
                mass += weights[i]
                val curve = curves(curveRef.substringBefore('@'))
                val mu = curve?.muAt(t) ?: Double.NaN
                val sg = curve?.sigmaAt(t) ?: Double.NaN
                cells += IntegralCell(t, mu, sg, offsetMeanAt(t), Double.NaN, weights[i])
            }
        }
        return mass to cells
    }
}

// ---------------------------------------------------------------------------
// Sequence constraints and assessments
// ---------------------------------------------------------------------------

data class SequenceConstraint(
    val constraintId: String,
    val label: String,
    val olderMeasurementId: String,
    val youngerMeasurementId: String,
    val sourceRef: String
)

enum class AdjustmentKind { REVISE_OFFSET, EXCLUDE_SAMPLE, RELAX_CONSTRAINT, REVISE_MEASUREMENT_NOTE, UPGRADE_CURVE }

data class CandidateAdjustment(
    val kind: AdjustmentKind,
    val targetRef: String,
    val proposal: String,
    val evidence: String
)

data class SequenceAssessment(
    val constraintRef: String,
    val olderResultFingerprint: String,
    val youngerResultFingerprint: String,
    val orderProbability: Double,
    val conflicts: Boolean,
    val candidates: List<CandidateAdjustment>,
    val diagnostics: List<Diagnostic>
) {
    companion object { const val CONFLICT_THRESHOLD = 0.95 }
}

// ---------------------------------------------------------------------------
// Human decisions (exclusions and adjudication) are events; a projection of
// accepted exclusions is kept here for convenience.
// ---------------------------------------------------------------------------

enum class DecisionOutcome { ACCEPTED, REJECTED, REVOKED }

data class ExclusionDecisionView(
    val decisionId: String,
    val measurementId: String,
    val reason: String,
    val sourceRef: String,
    val outcome: DecisionOutcome,
    val actor: String,
    val eventSeq: Long,
    val basisEventIds: List<String>
)
