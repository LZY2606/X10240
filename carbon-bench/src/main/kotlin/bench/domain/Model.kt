package bench.domain

import kotlinx.serialization.Serializable

/**
 * A laboratory radiocarbon determination. Stored exactly as submitted; the
 * raw representation is never overwritten (revisions become new versions with
 * a parent pointer).
 */
@Serializable
data class Measurement(
    val id: String,
    val version: Int,
    val parentVersion: Int? = null,
    val labCode: String,
    val c14AgeBP: Double,
    val sigmaBP: Double,
    val ageUnit: String = "14C yr BP",
    val sigmaUnit: String = "14CC yr BP",
    val rawText: String,
    val sourceRef: String,
    val supersededBy: String? = null,
) {
    val current: Boolean get() = supersededBy == null
}

/**
 * Reservoir / dietary offset hypothesis. Both Gaussian and discrete-mixture
 * forms are first-class so non-Gaussian reservoir distributions are not
 * flattened into a single mean.
 */
@Serializable
sealed class OffsetDistribution {
    abstract val meanBP: Double
    abstract val totalVar: Double

    @Serializable
    data class Gaussian(
        val deltaRBP: Double,
        val deltaRSigmaBP: Double,
        val unit: String = "14C yr",
    ) : OffsetDistribution() {
        override val meanBP get() = deltaRBP
        override val totalVar get() = deltaRSigmaBP * deltaRSigmaBP
    }

    @Serializable
    data class Mixture(
        val components: List<Component>,
        val unit: String = "14C yr",
    ) : OffsetDistribution() {
        @Serializable
        data class Component(val shiftBP: Double, val sigmaBP: Double, val weight: Double)

        override val meanBP: Double
            get() = components.sumOf { it.shiftBP * it.weight } / components.sumOf { it.weight }
        override val totalVar: Double
            get() {
                val wsum = components.sumOf { it.weight }
                val m = meanBP
                return components.sumOf { c ->
                    c.weight * (c.sigmaBP * c.sigmaBP + (c.shiftBP - m) * (c.shiftBP - m))
                } / wsum
            }
    }
}

@Serializable
data class OffsetHypothesis(
    val id: String,
    val version: Int,
    val parentVersion: Int? = null,
    val name: String,
    val appliesToSampleIds: List<String>,
    val distribution: OffsetDistribution,
    val sourceRef: String,
    val supersededBy: String? = null,
) {
    val current: Boolean get() = supersededBy == null
}

/** One annual density weight after normalisation over the searched grid. */
@Serializable
data class DensityYear(val calBP: Int, val weight: Double, val eraLabel: String)

/**
 * A contiguous probability interval. Ties at the HDR threshold are kept as
 * separate co-equal intervals (never merged) and every interval carries the
 * evidence required to re-integrate it.
 */
@Serializable
data class ProbInterval(
    val id: String,
    val calBPOlder: Int,
    val calBPYounger: Int,
    val probability: Double,
    val rank: Int,
    val tied: Boolean,
    val crossesEraBoundary: Boolean,
    val olderLabel: String,
    val youngerLabel: String,
    val integralSteps: List<IntegralStep>,
)

@Serializable
data class IntegralStep(
    val calBP: Int,
    val numerator: Double,
    val denominator: Double,
    val curveC14BP: Double,
    val curveSigma: Double,
    val combinedSigma: Double,
    val rawLikelihood: Double,
    val interpolatedKnot: Boolean,
)

@Serializable
data class BoundaryDiagnostic(
    val calBP: Int,
    val kind: String, // INTERPOLATED_KNOT | OUTSIDE_RANGE | ERA_CROSSING
    val detail: String,
)

@Serializable
data class CalibrationResult(
    val id: String,
    val measurementId: String,
    val measurementVersion: Int,
    val curveId: String,
    val curveVersion: String,
    val offsetId: String?,
    val offsetVersion: Int?,
    val eraSystemId: String,
    val algorithmVersion: String,
    val paramFingerprint: String,
    val directSources: List<String>,
    val effectiveAgeBP: Double,
    val effectiveSigmaBP: Double,
    val gridMinBP: Int,
    val gridMaxBP: Int,
    val normalizingConstant: Double,
    val multimodal: Boolean,
    val intervals: List<ProbInterval>,
    val density: List<DensityYear>,
    val diagnostics: List<BoundaryDiagnostic>,
    val stale: Boolean = false,
    val staleReason: String? = null,
)
