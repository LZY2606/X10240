package org.chrono.model

import kotlinx.serialization.Serializable

/** One sampled point of a radiocarbon calibration curve: calendar BP -> radiocarbon BP +/- sigma. */
@Serializable
data class CurvePoint(
    val calBp: Double,
    val c14Bp: Double,
    val sigma: Double,
) {
    init {
        require(sigma >= 0.0) { "curve point sigma must be non-negative at calBp=$calBp" }
    }
}

/**
 * An immutable, versioned calibration curve snapshot. Every derived result references the
 * exact (id, version, contentSha) it integrated against, so curve upgrades never rewrite
 * history.
 */
@Serializable
data class CalibrationCurveSpec(
    val curveId: String,
    val version: String,
    val points: List<CurvePoint>,
    val sourceRef: String? = null,
    val publishedNote: String? = null,
) {
    val calBpMin: Double get() = points.first().calBp
    val calBpMax: Double get() = points.last().calBp
}

/** Laboratory radiocarbon determination. Immutable once committed. */
@Serializable
data class MeasurementSpec(
    val sampleCode: String,
    val age: Qty,
    val sigma: Qty,
    val rawAgeText: String? = null,
    val rawSigmaText: String? = null,
    val materialNote: String? = null,
    val laboratoryRef: String? = null,
    val sourceRef: String? = null,
)

/** Reservoir offset distribution applied to a measurement (delta-R, in 14C years). */
@Serializable
sealed class OffsetDist {
    @Serializable
    data class Gaussian(val mean: Double, val sigma: Double) : OffsetDist()

    @Serializable
    data class Component(val weight: Double, val mean: Double, val sigma: Double)

    @Serializable
    data class Mixture(val components: List<Component>) : OffsetDist() {
        init {
            require(components.size >= 2) { "a mixture offset needs at least two components" }
            require(components.all { it.weight > 0.0 && it.sigma >= 0.0 }) {
                "mixture components need positive weights and non-negative sigmas"
            }
        }
    }
}

/** A sourced reservoir / offset hypothesis (e.g. regional marine reservoir table entry). */
@Serializable
data class ReservoirSpec(
    val reservoirCode: String,
    val name: String,
    val offset: OffsetDist,
    val unit: String = Units.C14_YR_BP,
    val rawText: String? = null,
    val sourceRef: String? = null,
)

/** Links a sample to the reservoir offset hypothesis assumed for it. Revisable independently. */
@Serializable
data class OffsetBindingSpec(
    val sampleCode: String,
    val reservoirCode: String,
    val note: String? = null,
    val sourceRef: String? = null,
)

/** Project-wide coordinate conventions (14C datum, calendar datum, BCE/CE zero-year policy). */
@Serializable
data class EpochSystemSpec(
    val code: String,
    val c14Datum: String = Units.DATUM_BP1950,
    val calendarDatum: String = Units.DATUM_ASTRO,
    val zeroYearPolicy: String = "astronomical: year 0 is 1 BCE",
    val labelConvention: String = Units.DATUM_BCE_CE,
    val sourceRef: String? = null,
)

@Serializable
data class SequenceEdge(
    val olderSample: String,
    val youngerSample: String,
    val note: String? = null,
    val sourceRef: String? = null,
)

/** Ordered stratigraphic chain constraint. A strict single chain is required by the engine. */
@Serializable
data class SequenceConstraintSpec(
    val chainCode: String,
    val edges: List<SequenceEdge>,
    val sourceRef: String? = null,
)

/** A sourced decision to exclude a sample from some downstream scope (never deletes the data). */
@Serializable
data class ExclusionSpec(
    val sampleCode: String,
    val reason: String,
    val scope: String = "calibration",
    val sourceRef: String? = null,
)
