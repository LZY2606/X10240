package chrono.domain

import kotlinx.serialization.Serializable
import java.time.Instant

/**
 * Every piece of evidence carries a citation: who supplied it and where it came from.
 */
@Serializable
data class Source(
    val citation: String,
    val origin: String,
    val submittedAtEpochMilli: Long? = null
) {
    val submittedAt: Instant? get() = submittedAtEpochMilli?.let(Instant::ofEpochMilli)
}

/** Units and coordinate datums are explicit; normalization happens only in derived layers. */
object Units {
    const val C14_YEARS_BP = "14C yr BP"
    const val CAL_YEARS_BP = "cal BP"
    const val CAL_YEARS_ASTRO = "cal yr (astronomical)"
    const val CAL_YEARS_BCE_CE = "BCE/CE"
}

/** A reservoir offset is a *distribution*, never a single forced number. */
@Serializable
sealed class OffsetSpec {
    abstract val mean: Double
    abstract val sd: Double
    abstract val representation: String

    @Serializable
    @SerialName("normal")
    data class Normal(
        val meanBP: Double,
        val sigmaBP: Double,
        val unit: String = Units.C14_YEARS_BP
    ) : OffsetSpec() {
        override val mean get() = meanBP
        override val sd get() = sigmaBP
        override val representation: String
            get() = "Normal(${fmt(meanBP)} \u00b1 ${fmt(sigmaBP)} $unit)"
    }

    /** Mixture of reservoir components, e.g. 70% marine + 30% freshwater. */
    @Serializable
    @SerialName("mixture")
    data class Mixture(
        val components: List<Component>,
        val unit: String = Units.C14_YEARS_BP
    ) : OffsetSpec() {
        @Serializable
        data class Component(val weight: Double, val meanBP: Double, val sigmaBP: Double) {
            init {
                require(weight > 0.0) { "mixture weight must be positive" }
            }
        }

        override val mean: Double
            get() = components.sumOf { it.weight * it.meanBP } / components.sumOf { it.weight }

        override val sd: Double
            get() {
                val w = components.sumOf { it.weight }
                val m = mean
                return kotlin.math.sqrt(
                    components.sumOf { c ->
                        val ww = c.weight / w
                        ww * (c.sigmaBP * c.sigmaBP + (c.meanBP - m) * (c.meanBP - m))
                    }
                )
            }

        override val representation: String
            get() = "Mixture[" + components.joinToString(", ") {
                "${fmt(it.weight / components.sumOf { x -> x.weight } * 100.0)}% " +
                    "N(${fmt(it.meanBP)},${fmt(it.sigmaBP)})"
            } + " $unit]"
    }

    /** Samples kept as supplied; mean/sd are derived summaries. */
    @Serializable
    @SerialName("samples")
    data class Samples(
        val valuesBP: List<Double>,
        val unit: String = Units.C14_YEARS_BP
    ) : OffsetSpec() {
        override val mean: Double get() = valuesBP.average()
        override val sd: Double
            get() {
                val m = mean
                return kotlin.math.sqrt(valuesBP.sumOf { (it - m) * (it - m) } / valuesBP.size)
            }
        override val representation: String = "Samples(n=${valuesBP.size}, unit=$unit)"
    }

    companion object {
        fun fmt(v: Double): String {
            val r = kotlin.math.round(v * 10.0) / 10.0
            return if (r == kotlin.math.floor(r) && kotlin.math.abs(r) < 1e9)
                r.toLong().toString()
            else String.format("%.1f", r)
        }
    }
}

/**
 * Calibration curve. Calendar axis is cal BP (years before 1950 CE, always non-negative).
 * Points are supplied (not resampled on import); [interpolateAt] does linear interpolation,
 * i.e. the deterministic boundary rule of curve version + 1.0.0.
 */
@Serializable
data class CalibrationCurve(
    val id: String,
    val label: String,
    val version: String,
    val supersedes: String? = null,
    val points: List<CurvePoint>,
    val source: Source,
    val datum: String = Units.CAL_YEARS_BP
) {
    private val xMin get() = points.first().calBP
    private val xMax get() = points.last().calBP

    init {
        require(points.size >= 2) { "curve needs at least 2 points" }
        require(points.zipWithNext().all { (a, b) -> a.calBP < b.calBP }) {
            "curve points must be strictly increasing cal BP"
        }
    }

    @Serializable
    data class CurvePoint(val calBP: Double, val c14BP: Double, val sigmaC14: Double)

    data class Interp(val c14BP: Double, val sigmaC14: Double, val inside: Boolean)

    fun interpolateAt(calBP: Double): Interp? {
        if (calBP < xMin || calBP > xMax) return null
        if (calBP == xMin) return Interp(points.first().c14BP, points.first().sigmaC14, true)
        if (calBP == xMax) return Interp(points.last().c14BP, points.last().sigmaC14, true)
        var lo = 0
        var hi = points.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (points[mid].calBP <= calBP) lo = mid else hi = mid
        }
        val a = points[lo]
        val b = points[hi]
        val t = (calBP - a.calBP) / (b.calBP - a.calBP)
        return Interp(
            a.c14BP + t * (b.c14BP - a.c14BP),
            a.sigmaC14 + t * (b.sigmaC14 - a.sigmaC14),
            true
        )
    }

    val span: ClosedFloatingPointRange<Double> get() = xMin..xMax
}

/**
 * A raw radiocarbon measurement exactly as the laboratory reported it.
 * Original representation is retained; [ageBP]/[sigmaBP] are its normalized parse.
 */
@Serializable
data class Measurement(
    val id: String,
    val sampleCode: String,
    val labCode: String,
    val rawAge: String,
    val rawSigma: String,
    val ageBP: Double,
    val sigmaBP: Double,
    val material: String,
    val unit: String,
    val source: Source,
    val parentId: String? = null,
    val superseded: Boolean = false,
    val unconvertible: Boolean = false,
    val diagnostic: String? = null
)

@Serializable
data class Reservoir(
    val id: String,
    val label: String,
    val offset: OffsetSpec,
    val source: Source,
    val parentId: String? = null,
    val superseded: Boolean = false
)

/** A pairing of one measurement with one reservoir hypothesis. Immutable once created. */
@Serializable
data class Hypothesis(
    val id: String,
    val measurementId: String,
    val reservoirId: String? = null
)

/** Relationship between two samples. [order] = older/younger or coeval. */
@Serializable
data class SequenceConstraint(
    val id: String,
    val olderHypothesisId: String? = null,
    val youngerHypothesisId: String? = null,
    val coevalA: String? = null,
    val coevalB: String? = null,
    val note: String,
    val source: Source
) {
    val isCoeval get() = coevalA != null && coevalB != null
}

/** A reviewer decision to exclude material (with reason); itself sourced and revocable. */
@Serializable
data class Exclusion(
    val id: String,
    val targetType: String,
    val targetId: String,
    val reason: String,
    val source: Source,
    val revoked: Boolean = false
)

/** A reviewer adjudication on a calibrated interval. */
@Serializable
data class Decision(
    val id: String,
    val intervalId: String,
    val runId: String,
    val verdict: String,
    val rationale: String,
    val reviewer: String,
    val supersededBy: String? = null,
    val revoked: Boolean = false
)

/** One candidate calendar interval of a multimodal posterior. Never averaged away. */
@Serializable
data class CalendarInterval(
    val intervalId: String,
    val level: String,
    val index: Int,
    val startCalBP: Double,
    val endCalBP: Double,
    val probabilityMass: Double,
    val relativePeakDensity: Double,
    val crossesBceCe: Boolean,
    val boundaryTruncated: Boolean
)

/** Density grid sample, kept compact for persistence and SVG rendering. */
@Serializable
data class GridPoint(val calBP: Int, val density: Double)

@Serializable
data class CalibrationRun(
    val runId: String,
    val hypothesisId: String,
    val measurementId: String,
    val reservoirId: String? = null,
    val curveId: String,
    val curveVersion: String,
    val algorithm: String,
    val parameterFingerprint: String,
    val gridStepBP: Int,
    val grid: List<GridPoint>,
    val intervals: List<CalendarInterval>,
    val totalMass: Double,
    val boundaryLowTruncated: Boolean,
    val boundaryHighTruncated: Boolean,
    val modeCount: Int,
    val excluded: Boolean = false,
    val diagnostic: String? = null,
    val sources: List<String>,
    val createdAtEvent: Long
)

@Serializable
data class AdjustmentProposal(
    val proposalId: String,
    val hypothesisId: String,
    val kind: String,
    val suggestedDeltaBP: Double? = null,
    val suggestedReservoirLabel: String? = null,
    val expectedImprovement: Double,
    val accepted: Boolean = false,
    val note: String
)

@Serializable
data class SequenceEvaluation(
    val evaluationId: String,
    val constraintId: String,
    val kind: String,
    val runAId: String,
    val runBId: String,
    val pOlder: Double,
    val pYounger: Double,
    val pCoeval: Double? = null,
    val violated: Boolean,
    val proposals: List<AdjustmentProposal> = emptyList()
)

@Serializable
data class Project(
    val id: String,
    val name: String,
    val eraSystem: String,
    val eraSource: Source,
    @Serializable(with = InstantSerializer::class)
    val createdAt: Instant
)
