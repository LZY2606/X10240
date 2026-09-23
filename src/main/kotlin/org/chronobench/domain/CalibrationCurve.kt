package org.chronobench.domain

import kotlinx.serialization.Serializable
import kotlin.math.max

@Serializable
data class CurveKnot(val calBP: Double, val c14BP: Double, val sigma: Double) {
    init {
        require(sigma >= 0.0) { "curve sigma must be non-negative at calBP=$calBP" }
    }
}

/**
 * An offline, fully-versioned radiocarbon calibration curve.
 *
 * Knots are ordered ascending by cal BP; C14 means and sigmas are linearly interpolated
 * between knots. Outside the covered span there is no extrapolation: queries return
 * [Double.NaN] and carry an OUT_OF_CURVE_RANGE diagnostic, so values are never invented
 * for plotting.
 */
@Serializable
data class CalibrationCurve(
    val id: String,
    val version: String,
    val displayName: String,
    val knots: List<CurveKnot>,
    val source: String,
    val fingerprint: String,
    val supersedes: String? = null
) {
    val minCalBP: Double get() = knots.first().calBP
    val maxCalBP: Double get() = knots.last().calBP

    fun contains(calBP: Double): Boolean = calBP in minCalBP..maxCalBP

    fun c14At(calBP: Double): Double {
        if (calBP < minCalBP || calBP > maxCalBP) return Double.NaN
        if (calBP <= knots.first().calBP) return knots.first().c14BP
        for (i in 1 until knots.size) {
            val hi = knots[i]
            if (calBP <= hi.calBP) {
                val lo = knots[i - 1]
                val f = (calBP - lo.calBP) / (hi.calBP - lo.calBP)
                return lo.c14BP + f * (hi.c14BP - lo.c14BP)
            }
        }
        return Double.NaN
    }

    fun sigmaAt(calBP: Double): Double {
        if (calBP < minCalBP || calBP > maxCalBP) return Double.NaN
        if (calBP <= knots.first().calBP) return knots.first().sigma
        for (i in 1 until knots.size) {
            val hi = knots[i]
            if (calBP <= hi.calBP) {
                val lo = knots[i - 1]
                val f = (calBP - lo.calBP) / (hi.calBP - lo.calBP)
                return lo.sigma + f * (hi.sigma - lo.sigma)
            }
        }
        return Double.NaN
    }

    /**
     * Total 14C standard deviation at this calendar age: curve sigma combined in quadrature
     * with the measurement sigma and an optional reservoir offset sigma.
     */
    fun totalSigmaAt(calBP: Double, measurementSigma: Double, reservoirSigma: Double): Double {
        val cs = sigmaAt(calBP)
        return sqrt(cs * cs + measurementSigma * measurementSigma + reservoirSigma * reservoirSigma)
    }

    companion object {
        private fun sqrt(x: Double) = kotlin.math.sqrt(x)

        /** SHA-256 based content fingerprint; stable across JVM runs. */
        fun fingerprintOf(id: String, version: String, knots: List<CurveKnot>): String {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            digest.update(id.toByteArray())
            digest.update('|'.code.toByte())
            digest.update(version.toByteArray())
            for (k in knots) {
                digest.update(canon(k.calBP))
                digest.update(','.code.toByte())
                digest.update(canon(k.c14BP))
                digest.update(','.code.toByte())
                digest.update(canon(k.sigma))
                digest.update(';'.code.toByte())
            }
            return digest.digest().joinToString("") { "%02x".format(it) }.take(16)
        }

        /** Deterministic, locale-free canonical rendering of a double. */
        fun canon(v: Double): ByteArray =
            java.math.BigDecimal.valueOf(v).stripTrailingZeros().toPlainString().toByteArray()
    }
}

/**
 * Synthetic but domain-shaped offline curves. They emulate the IntCal-style wiggle that
 * produces multiple calendar solutions for one 14C age. Two versions are shipped so the
 * acceptance suite can exercise a curve upgrade without touching the network.
 */
object SyntheticCurves {
    const val SOURCE_V1 = "casepack://carbon-seat/curves/synthetic-intcal-v1.csv"
    const val SOURCE_V2 = "casepack://carbon-seat/curves/synthetic-intcal-v2.csv"

    fun v1(): CalibrationCurve = build(
        version = "v1",
        source = SOURCE_V1,
        supersedes = null,
        lobes = listOf(Triple(2050.0, 75.0, 25.0), Triple(2100.0, 70.0, 25.0), Triple(2150.0, 75.0, 25.0)),
        displaySuffix = "v1"
    )

    /** v2 shifts the wiggle ~8 years older and reduces the central amplitude; v1 stays replayable. */
    fun v2(): CalibrationCurve = build(
        version = "v2",
        source = SOURCE_V2,
        supersedes = "synthetic-intcal@v1",
        lobes = listOf(Triple(2058.0, 68.0, 26.0), Triple(2108.0, 55.0, 26.0), Triple(2158.0, 68.0, 26.0)),
        displaySuffix = "v2 revised wiggle"
    )

    private const val MIN_BP = 1700
    private const val MAX_BP = 2300
    private const val SHELF_C14 = 1950.0
    private const val SHELF_END = 2175.0
    private const val CLIFF_END = 2180.0
    private const val CLIFF_TOP = 2100.0
    private const val OLD_SLOPE = 6.0

    private fun build(
        version: String,
        source: String,
        supersedes: String?,
        lobes: List<Triple<Double, Double, Double>>,
        displaySuffix: String
    ): CalibrationCurve {
        val knots = ArrayList<CurveKnot>(2 * (MAX_BP - MIN_BP) + 1)
        for (i in 0..2 * (MAX_BP - MIN_BP)) {
            val bp = MIN_BP + i * 0.5
            val base = when {
                bp <= 1980.0 -> bp - 30.0
                bp <= SHELF_END -> SHELF_C14
                bp <= CLIFF_END -> SHELF_C14 + (CLIFF_TOP - SHELF_C14) * ((bp - SHELF_END) / (CLIFF_END - SHELF_END))
                else -> CLIFF_TOP + OLD_SLOPE * (bp - CLIFF_END)
            }
            var wiggle = 0.0
            for ((center, amp, halfWidth) in lobes) {
                val d = bp - center
                if (d <= halfWidth) {
                    val tri = 1.0 - (d / halfWidth).let { it * it }
                    if (tri > 0) wiggle += amp * tri
                }
            }
            val sigma = 12.0 + 0.004 * (bp - MIN_BP)
            knots.add(CurveKnot(bp, base - wiggle, sigma))
        }
        return CalibrationCurve(
            id = "synthetic-intcal",
            version = version,
            displayName = "Synthetic IntCal-like curve (offline case pack, $displaySuffix)",
            knots = knots,
            source = source,
            fingerprint = CalibrationCurve.fingerprintOf("synthetic-intcal", version, knots),
            supersedes = supersedes
        )
    }
}
