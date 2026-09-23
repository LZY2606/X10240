package c14.domain

import kotlinx.serialization.Serializable

/**
 * A radiocarbon calibration curve: mapping from calendar years BP (t, integer years,
 * BP = before 1950 CE) to the atmospheric/marine radiocarbon age in "C-14 years BP"
 * with the curve's own one-sigma uncertainty.
 *
 * Quantities always carry a unit/benchmark; interpolation is linear between control
 * points and clamps at the declared boundary (boundary interpolation is reported as
 * a diagnostic instead of silently extrapolating).
 */
@Serializable
data class CurvePoint(
    val calBP: Int,
    val c14AgeBP: Double,
    val sigmaBP: Double,
)

@Serializable
data class CalCurve(
    val id: String,
    val name: String,
    val version: Int,
    val sourceNote: String,
    val points: List<CurvePoint>,
) {
    val tMin: Int get() = points.first().calBP
    val tMax: Int get() = points.last().calBP

    /** Linear interpolation; values outside the declared range clamp to the edge. */
    fun meanAt(t: Double): Double {
        if (t <= tMin) return points.first().c14AgeBP
        if (t >= tMax) return points.last().c14AgeBP
        val ti = t.toInt()
        val p0 = points[ti - tMin]
        val p1 = points[ti - tMin + 1]
        val f = t - ti
        return p0.c14AgeBP + (p1.c14AgeBP - p0.c14AgeBP) * f
    }

    fun sigmaAt(t: Double): Double {
        if (t <= tMin) return points.first().sigmaBP
        if (t >= tMax) return points.last().sigmaBP
        val ti = t.toInt()
        val p0 = points[ti - tMin]
        val p1 = points[ti - tMin + 1]
        val f = t - ti
        return p0.sigmaBP + (p1.sigmaBP - p0.sigmaBP) * f
    }

    fun clamped(t: Double): Boolean = t <= tMin || t >= tMax
}

/**
 * Offline, deterministic synthetic calibration curves shipped with the workbench.
 * They deliberately contain plateaus and wiggles so a single lab age maps to several
 * separated calendar intervals (multi-peak evidence). Version 2 is a curve upgrade:
 * the same wiggle field, shifted and re-phased, which moves one plateau.
 */
object SyntheticCurves {
    const val CURVE_ID = "SIM-CAL-20"

    private fun wave(t: Int, phase: Double, drift: Double): Double {
        val trend = 0.92 * t
        val wiggle = 150.0 * Math.sin(t / 130.0 + phase) +
            55.0 * Math.sin(t / 47.0 + phase * 1.7)
        return trend + wiggle + drift
    }

    private fun build(version: Int): CalCurve {
        val tMin = 0
        val tMax = 5200
        val phase = if (version == 1) 0.4 else 0.4 + 0.55
        val drift = if (version == 1) 30.0 else 64.0
        val pts = (tMin..tMax step 25).map { t ->
            val jitter = 18.0 * Math.sin(t / 311.0 + version)
            CurvePoint(
                calBP = t,
                c14AgeBP = wave(t, phase, drift),
                sigmaBP = 22.0 + 0.004 * t + jitter * 0.0,
            )
        }
        return CalCurve(
            id = CURVE_ID,
            name = "Simulated IntCal-style wiggly curve",
            version = version,
            sourceNote = if (version == 1)
                "in-repo offline curve, synthetic v1 (case pack baseline)"
            else
                "in-repo offline curve, synthetic v2 (curve upgrade, plateau re-phased)",
            points = pts,
        )
    }

    val v1: CalCurve by lazy { build(1) }
    val v2: CalCurve by lazy { build(2) }

    fun byVersion(v: Int): CalCurve = when (v) {
        1 -> v1
        2 -> v2
        else -> error("unknown curve version $v for $CURVE_ID")
    }
}
