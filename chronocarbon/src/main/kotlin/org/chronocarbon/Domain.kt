package org.chronocarbon

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.exp

/** Supported calendar systems. All stored coordinates are cal BP; labels are presentation only. */
@Serializable
enum class EraSystem(val id: String, val label: String) {
    CAL_BP("cal_bp", "cal BP (before 1950 CE)"),
    BCE_CE("bce_ce", "BCE/CE astronomical years");
}

@Serializable
data class CurvePoint(val calBp: Int, val c14Bp: Double, val sigma: Double)

/**
 * Calibration curve at a fixed version. Points are sorted ascending by cal BP.
 * [version] is part of every fingerprint: recomputation under a new curve version is a new derivation.
 */
@Serializable
data class CalibrationCurve(
    val curveId: String,
    val version: String,
    val successorOf: String? = null,
    val timeCoordinate: String = "cal BP (years before 1950 CE)",
    val halfLifeYears: Int = 5568,
    val pointSpacingYears: Int = 5,
    val provenance: Provenance,
    val points: List<CurvePoint>
) {
    val minBp: Int get() = points.first().calBp
    val maxBp: Int get() = points.last().calBp

    init {
        require(points.size >= 2) { "curve $version needs at least two points" }
        require(points.zipWithNext().all { it.first.calBp < it.second.calBp }) {
            "curve $version points must be strictly ascending cal BP"
        }
        require(points.all { it.sigma > 0.0 }) { "curve $version sigma must be positive" }
    }

    /** Piecewise-linear interpolation; constant extrapolation outside the table (boundary clipping). */
    fun muAt(bp: Double): Double = interp(bp) { it.calBp.toDouble() to it.c14Bp }
    fun sigmaAt(bp: Double): Double = interp(bp) { it.calBp.toDouble() to it.sigma }

    private inline fun interp(bp: Double, pair: (CurvePoint) -> Pair<Double, Double>): Double {
        if (bp <= points.first().calBp) return pair(points.first()).second
        if (bp >= points.last().calBp) return pair(points.last()).second
        var lo = 0
        var hi = points.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (points[mid].calBp.toDouble() <= bp) lo = mid else hi = mid
        }
        val (x0, y0) = pair(points[lo])
        val (x1, y1) = pair(points[hi])
        val f = (bp - x0) / (x1 - x0)
        return y0 + f * (y1 - y0)
    }
}

@Serializable
data class Provenance(
    val source: String,
    val document: String? = null,
    val detail: String? = null,
    val importedAt: String? = null
)

/**
 * Reservoir offset distribution in radiocarbon years.
 * Gaussian for classical Delta R; weighted mixture for competing reservoir hypotheses.
 */
@Serializable
sealed class OffsetDist {
    abstract fun effectiveSigma(measurementSigma: Double): Double
    abstract fun shift(): Double

    @Serializable
    data class Gaussian(val mean: Double, val sigma: Double, val unit: String = "14C yr BP") : OffsetDist() {
        init { require(sigma >= 0.0) { "offset sigma cannot be negative" } }
        override fun effectiveSigma(measurementSigma: Double) =
            kotlin.math.sqrt(measurementSigma * measurementSigma + sigma * sigma)
        override fun shift() = mean
    }

    @Serializable
    data class Component(val mean: Double, val sigma: Double, val weight: Double)

    @Serializable
    data class Mixture(val components: List<Component>, val unit: String = "14C yr BP") : OffsetDist() {
        init {
            require(components.size >= 2) { "mixture needs >= 2 components" }
            require(components.all { it.sigma >= 0.0 && it.weight > 0.0 }) { "bad mixture component" }
        }
        override fun effectiveSigma(measurementSigma: Double): Double {
            val wSum = components.sumOf { it.weight }
            val mean = components.sumOf { it.weight * it.mean } / wSum
            val variance = components.sumOf {
                it.weight * (it.sigma * it.sigma + (it.mean - mean) * (it.mean - mean))
            } / wSum
            return kotlin.math.sqrt(measurementSigma * measurementSigma + variance)
        }
        override fun shift(): Double {
            val wSum = components.sumOf { it.weight }
            return components.sumOf { it.weight * it.mean } / wSum
        }
    }

    @Serializable
    data object Zero : OffsetDist() {
        override fun effectiveSigma(measurementSigma: Double) = measurementSigma
        override fun shift() = 0.0
    }
}

/** Diagnostic left in place when an imported value cannot be converted or normalized. */
@Serializable
data class InputDiagnostic(val code: String, val message: String, val rawField: String? = null)

/**
 * Immutable laboratory measurement. Raw textual representations are preserved; the normalized
 * numeric value lives alongside. Unconvertible values remain with [diagnostics] and are never
 * dropped to make a plot.
 */
@Serializable
data class Measurement(
    val id: String,
    val code: String,
    val rawAgeText: String,
    val rawSigmaText: String,
    val ageBp: Double?,
    val sigma: Double?,
    val unit: String,
    val eraSystem: EraSystem,
    val provenance: Provenance,
    val parentId: String? = null,
    val superseded: Boolean = false,
    val diagnostics: List<InputDiagnostic> = emptyList()
) {
    val valid: Boolean get() = ageBp != null && sigma != null && sigma > 0.0 && diagnostics.none { it.code.startsWith("error") }
}
