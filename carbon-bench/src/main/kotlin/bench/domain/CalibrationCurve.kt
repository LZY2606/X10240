package bench.domain

import kotlinx.serialization.Serializable

/**
 * One annual row of a radiocarbon calibration curve.
 * calBP increases toward the past; rows are sorted ascending by calBP.
 * All values carry explicit units (radiocarbon years BP).
 */
@Serializable
data class CurvePoint(
    val calBP: Int,
    val c14AgeBP: Double,
    val sigma: Double,
)

@Serializable
data class CalibrationCurve(
    val id: String,
    val version: String,
    val description: String,
    val sourceRef: String,
    val points: List<CurvePoint>,
) {
    val minBP: Int get() = points.first().calBP
    val maxBP: Int get() = points.last().calBP

    fun at(bp: Int): CurvePoint? =
        points.binarySearchBy(bp) { it.calBP }.let { idx -> if (idx >= 0) points[idx] else null }

    /**
     * Piecewise-linear interpolation between annual knots. Outside the
     * published range interpolation is refused (returns null) so callers can
     * record an explicit boundary/extrapolation diagnostic instead of silently
     * drawing a value.
     */
    fun interp(bp: Double): InterpResult? {
        if (bp < minBP - 0.5 || bp > maxBP + 0.5) return null
        val lo = kotlin.math.floor(bp).toInt().coerceIn(minBP, maxBP)
        val hi = (lo + 1).coerceAtMost(maxBP)
        val a = at(lo) ?: return null
        val b = at(hi) ?: return null
        val t = if (hi == lo) 0.0 else (bp - lo) / (hi - lo)
        return InterpResult(
            calBP = bp,
            c14AgeBP = a.c14AgeBP + t * (b.c14AgeBP - a.c14AgeBP),
            sigma = a.sigma + t * (b.sigma - a.sigma),
            knotOlderBP = lo,
            knotYoungerBP = hi,
            interpolated = hi != lo && t != 0.0,
        )
    }

    data class InterpResult(
        val calBP: Double,
        val c14AgeBP: Double,
        val sigma: Double,
        val knotOlderBP: Int,
        val knotYoungerBP: Int,
        val interpolated: Boolean,
    )
}
