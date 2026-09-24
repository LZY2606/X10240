package workbench

import kotlin.math.exp
import kotlin.math.pow

/** One node of a calibration curve. Units: calBP calendar axis, c14 BP measured axis. */
data class CurveNode(val calBP: Int, val c14: Double, val sigma: Double)

/**
 * Versioned calibration curve. Interpolation is piecewise linear and fully deterministic,
 * so results are reproducible under a fixed (curveId, revision).
 */
class CalibrationCurve(
    val curveId: String,
    val revision: Int,
    val nodes: List<CurveNode>,
) {
    val minCalBP: Int = nodes.first().calBP
    val maxCalBP: Int = nodes.last().calBP

    /** Interpolated value plus the two bracketing nodes actually used (interpolation evidence). */
    data class Segment(val mu: Double, val sigma: Double, val lo: CurveNode, val hi: CurveNode)

    fun segmentAt(calBP: Double): Segment {
        val t = calBP.coerceIn(minCalBP.toDouble(), maxCalBP.toDouble())
        var lo = nodes.first()
        var hi = nodes.last()
        for (i in 0 until nodes.size - 1) {
            val a = nodes[i]
            val b = nodes[i + 1]
            if (t >= a.calBP && t <= b.calBP) { lo = a; hi = b; break }
        }
        val span = (hi.calBP - lo.calBP).toDouble()
        val f = if (span == 0.0) 0.0 else (t - lo.calBP) / span
        return Segment(
            mu = lo.c14 + f * (hi.c14 - lo.c14),
            sigma = lo.sigma + f * (hi.sigma - lo.sigma),
            lo = lo, hi = hi,
        )
    }

    fun toRawJson(): String = buildString {
        append("{\"curveId\":\"").append(curveId).append("\",\"revision\":").append(revision)
        append(",\"basis\":\"calBP:1950\",\"nodes\":[")
        nodes.forEachIndexed { i, n ->
            if (i > 0) append(',')
            append('[').append(n.calBP).append(',').append(n.c14).append(',').append(n.sigma).append(']')
        }
        append("]}")
    }

    companion object {
        const val STEP = 10
        const val MIN = 0
        const val MAX = 6000

        private fun gauss(t: Double, c: Double, w: Double, a: Double): Double =
            a * exp(-((t - c) / w).pow(2))

        /**
         * Built-in offline curve family "KAIRO-1". Deterministic analytic definition:
         * a long-term trend plus localized wiggles. The wiggle near 2400-2700 calBP is a
         * plateau (Hallstatt-like) that yields multi-modal calibrated distributions.
         * Revision 2 revises only the 3000-3400 calBP region (curve upgrade scenario).
         */
        fun kairo(revision: Int): CalibrationCurve {
            require(revision == 1 || revision == 2) { "unknown KAIRO-1 revision $revision" }
            val nodes = ArrayList<CurveNode>()
            var t = MIN
            while (t <= MAX) {
                val td = t.toDouble()
                var c14 = 0.98 * td + 60.0
                // Plateau-forming wiggles around 2400-2700 calBP (non-monotone).
                c14 += gauss(td, 2450.0, 90.0, 70.0)
                c14 -= gauss(td, 2620.0, 80.0, 55.0)
                // Smaller wiggles elsewhere.
                c14 += gauss(td, 1100.0, 120.0, 45.0)
                c14 -= gauss(td, 1900.0, 100.0, 30.0)
                c14 += gauss(td, 4300.0, 150.0, 60.0)
                if (revision == 1) {
                    c14 += gauss(td, 3200.0, 130.0, 40.0)
                } else {
                    // rev2: re-measured region 3000-3400 calBP.
                    c14 += gauss(td, 3200.0, 130.0, 15.0)
                    c14 -= gauss(td, 3300.0, 90.0, 25.0)
                }
                val sigma = 12.0 + 6.0 * kotlin.math.abs(kotlin.math.sin(td / 350.0))
                nodes.add(CurveNode(t, c14, sigma))
                t += STEP
            }
            return CalibrationCurve("KAIRO-1", revision, nodes)
        }
    }
}
