package org.gsb.radiocarbon.casepack

import org.gsb.radiocarbon.model.CalibrationCurve
import org.gsb.radiocarbon.model.CurvePoint
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/**
 * Produces the offline case-pack curve DEMOCAL20. It is deliberately engineered
 * (not a copy of any real IntCal table) so the shipped example exhibits:
 *  - a multi-modal wiggle near 2550 calBP (one age -> several calendar ranges)
 *  - two exactly mirrored lobes near 5900/6260 calBP (equal posterior mass ->
 *    tied calendar intervals); the second lobe is base-line compensated so the
 *    equality is algebraic, not approximate
 *  - the 1 BCE / 1 CE boundary near 1950 calBP
 *  - a smooth deep section past 4500 calBP
 * The "b" revision perturbs only 3300..3900 calBP, exercising a localized curve
 * upgrade: samples elsewhere keep identical result fingerprints.
 */
object DemoCurveGenerator {
    private const val TWO_PI = 2.0 * PI
    private const val C1 = 5900
    private const val C2 = 6260
    private const val PERIOD = 120
    private const val LOBE = 180
    private const val LOBE_AMP = 130.0

    private fun base(t: Int): Double =
        t + 160.0 * (1.0 - exp(-t / 2600.0)) + 8.0 * sin(TWO_PI * t / 2000.0)

    /** wiggle A: multimodal region near 2550. */
    private fun wiggleA(t: Int): Double {
        val g = exp(-((t - 2550.0) / 360.0) * ((t - 2550.0) / 360.0))
        return 210.0 * g * sin((t - 2550.0) / 52.0)
    }

    /** Localized change applied only in 3300..3900 for curve version b. */
    private fun upgradeWiggle(t: Int, active: Boolean): Double {
        if (!active || t !in 3300..3900) return 0.0
        return 120.0 * exp(-((t - 3600.0) / 250.0) * ((t - 3600.0) / 250.0)) *
            sin((t - 3600.0) / 55.0)
    }

    private fun window(d: Int): Double {
        val ad = if (d < 0) -d else d
        if (ad > LOBE) return 0.0
        return 1.0 - ad.toDouble() / LOBE // triangular Hann, compact support, no overlap
    }

    private fun oscillate(d: Int): Double = LOBE_AMP * cos(TWO_PI * d / PERIOD)

    /**
     * wiggle B: two equal-mass lobes. The second lobe subtracts the base-line
     * difference base(C2+d)-base(C1+d), so mu(C2+d) == mu(C1+d) algebraically.
     */
    private fun wiggleB(t: Int): Double {
        var v = 0.0
        val d1 = t - C1
        if (d1 in -LOBE..LOBE) v += window(d1) * oscillate(d1)
        val d2 = t - C2
        if (d2 in -LOBE..LOBE) {
            val compensation = base(C2 + d2) - base(C1 + d2)
            v += window(d2) * oscillate(d2) - compensation
        }
        return v
    }

    private fun sigma(t: Int): Double =
        if (t >= 1000) 25.0 else 25.0 * (t / 1000.0).coerceAtLeast(0)

    fun generate(version: String, span: IntRange = 0..10000): CalibrationCurve {
        val active = when (version) {
            "v2026a" -> false
            "v2026b" -> true
            else -> throw IllegalArgumentException("unknown demo curve version: $version")
        }
        val pts = span.map { t ->
            val mu = base(t) + wiggleA(t) + wiggleB(t) + upgradeWiggle(t, active)
            CurvePoint(t, mu, sigma(t))
        }
        return CalibrationCurve(
            curveId = "DEMOCAL20",
            version = version,
            publishedLabel = "Offline DEMOCAL20 ($version) - synthetic teaching curve, NOT IntCal",
            points = pts,
            sourceRef = "casepack/curves/DEMOCAL20_$version.rccurve (shipped offline)"
        )
    }
}
