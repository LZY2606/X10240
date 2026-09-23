package workbench

import kotlinx.serialization.Serializable

/**
 * A versioned radiocarbon calibration curve: irregularly spaced nodes
 * (calBP, c14AgeBP, sigmaBP), linearly interpolated between nodes.
 * The curve resource is immutable per version; upgrading the curve means
 * referencing a new [curveId]/[version], never editing nodes in place.
 */
@Serializable
data class CurveNode(val calBP: Double, val c14BP: Double, val sigmaBP: Double)

@Serializable
data class CalibrationCurve(
    val curveId: String,
    val version: Int,
    val nodes: List<CurveNode>,
) {
    val minCalBP: Double get() = nodes.first().calBP
    val maxCalBP: Double get() = nodes.last().calBP

    init {
        require(nodes.size >= 2) { "curve needs at least two nodes" }
        require(nodes.zipWithNext().all { (a, b) -> a.calBP < b.calBP }) { "curve nodes must be strictly increasing in calBP" }
    }

    /** Linear interpolation of the conventional C14 age at [calBP]. */
    fun c14At(calBP: Double): Double = interpolate(calBP) { it.c14BP }

    /** Linear interpolation of the curve 1-sigma at [calBP]. */
    fun sigmaAt(calBP: Double): Double = interpolate(calBP) { it.sigmaBP }

    /**
     * Boundary interpolation: inside the table we interpolate linearly between
     * the bracketing nodes; outside the table we clamp to the boundary node and
     * the caller records a diagnostic (the density outside the table is zero).
     */
    private fun interpolate(calBP: Double, pick: (CurveNode) -> Double): Double {
        if (calBP <= nodes.first().calBP) return pick(nodes.first())
        if (calBP >= nodes.last().calBP) return pick(nodes.last())
        var lo = 0
        var hi = nodes.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) / 2
            if (nodes[mid].calBP <= calBP) lo = mid else hi = mid
        }
        val a = nodes[lo]
        val b = nodes[hi]
        val f = (calBP - a.calBP) / (b.calBP - a.calBP)
        return pick(a) + f * (pick(b) - pick(a))
    }

    companion object {
        fun parseCsv(curveId: String, version: Int, text: String): CalibrationCurve {
            val nodes = text.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .map { line ->
                    val parts = line.split(",")
                    require(parts.size == 3) { "bad curve line: $line" }
                    CurveNode(parts[0].toDouble(), parts[1].toDouble(), parts[2].toDouble())
                }.toList()
            return CalibrationCurve(curveId, version, nodes)
        }

        fun loadResource(curveId: String, version: Int): CalibrationCurve {
            val name = "/curves/${curveId}_v$version.csv"
            val text = CalibrationCurve::class.java.getResource(name)?.readText()
                ?: error("curve resource not found: $name")
            return parseCsv(curveId, version, text)
        }
    }
}
