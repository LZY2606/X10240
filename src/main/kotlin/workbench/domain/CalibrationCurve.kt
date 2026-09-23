package workbench.domain

import workbench.StableIds
import kotlin.math.max

/** A single tabulated calibration-curve record. calBp is ascending in time but stored ascending here. */
@kotlinx.serialization.Serializable
data class CurvePoint(
    val calBp: Double,
    val c14Bp: Double,
    val sigma: Double
)

/**
 * Fixed-version calibration curve. The points are an immutable snapshot;
 * upgrading to a new curve version means registering a new CalibrationCurve,
 * never mutating this one.
 */
class CalibrationCurve private constructor(
    val curveId: String,
    val version: String,
    val displayName: String,
    val sourceRef: String,
    val points: List<CurvePoint>,
    val fingerprint: String
) {
    val minCalBp: Double = points.first().calBp
    val maxCalBp: Double = points.last().calBp

    private inner class Lookup(val index: Int) {
        val p0: CurvePoint get() = points[index]
        val p1: CurvePoint get() = points[index + 1]
    }

    private fun bracket(calBp: Double): Lookup? {
        if (calBp < minCalBp || calBp > maxCalBp) return null
        // simple binary search; tables are ~600-6000 rows, this is ample
        var lo = 0
        var hi = points.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (points[mid].calBp <= calBp) lo = mid else hi = mid
        }
        return if (lo < points.size - 1) Lookup(lo) else null
    }

    data class Interpolation(
        val calBp: Double,
        val c14Bp: Double,
        val sigma: Double,
        val leftCalBp: Double,
        val rightCalBp: Double,
        val weight: Double,
        val inside: Boolean
    )

    /**
     * Piecewise linear interpolation as used by the standard radiocarbon
     * calibration machinery (IntCal convention). Out-of-range queries are not
     * extrapolated: they come back inside=false so callers can mark the boundary
     * rather than inventing curve values.
     */
    fun interpolate(calBp: Double): Interpolation {
        val found = bracket(calBp)
        if (found == null) {
            val edge = if (calBp <= minCalBp) points.first() else points.last()
            return Interpolation(calBp, edge.c14Bp, edge.sigma, edge.calBp, edge.calBp, 0.0, false)
        }
        val span = found.p1.calBp - found.p0.calBp
        val weight = if (span == 0.0) 0.0 else (calBp - found.p0.calBp) / span
        return Interpolation(
            calBp = calBp,
            c14Bp = found.p0.c14Bp + weight * (found.p1.c14Bp - found.p0.c14Bp),
            sigma = found.p0.sigma + weight * (found.p1.sigma - found.p0.sigma),
            leftCalBp = found.p0.calBp,
            rightCalBp = found.p1.calBp,
            weight = weight,
            inside = true
        )
    }

    companion object {
        fun parse(
            version: String,
            displayName: String,
            sourceRef: String,
            text: String,
            originLineOffset: Int = 0
        ): ParseResult {
            val points = ArrayList<CurvePoint>()
            val diagnostics = ArrayList<CurveDiagnostic>()
            val header = LinkedHashMap<String, String>()
            text.lineSequence().forEachIndexed { zeroIndexed, rawLine ->
                val lineNo = zeroIndexed + 1 + originLineOffset
                val line = rawLine.trim()
                if (line.isEmpty()) return@forEachIndexed
                if (line.startsWith("#")) {
                    val body = line.removePrefix("#").trim()
                    val separator = body.indexOf(':')
                    if (separator > 0) header[body.substring(0, separator).trim()] =
                        body.substring(separator + 1).trim()
                    return@forEachIndexed
                }
                val fields = line.split(Regex("[,\\s]+")).filter { it.isNotEmpty() }
                if (fields.size < 3) {
                    diagnostics += CurveDiagnostic(
                        lineNo, line, "Expected 3 columns: calBP, c14BP, sigma"
                    )
                    return@forEachIndexed
                }
                val values = fields.subList(0, 3).map { token ->
                    token.toDoubleOrNull() ?: run {
                        diagnostics += CurveDiagnostic(lineNo, line, "Non-numeric value '$token'")
                        return@forEachIndexed
                    }
                }
                if (values[2] < 0) {
                    diagnostics += CurveDiagnostic(lineNo, line, "sigma must be non-negative")
                    return@forEachIndexed
                }
                points += CurvePoint(values[0], values[1], values[2])
            }
            if (points.size < 2) {
                diagnostics += CurveDiagnostic(0, "", "Curve needs at least two tabulated points")
            }
            val ascending = points.zipWithNext().all { (a, b) -> b.calBp > a.calBp }
            if (!ascending) {
                diagnostics += CurveDiagnostic(0, "", "calBP column must be strictly ascending")
            }
            val fingerprint = StableIds.fingerprint(
                listOf(
                    "kind" to "calibration-curve",
                    "version" to version,
                    "points" to points.flatMap { listOf(it.calBp, it.c14Bp, it.sigma) }
                        .joinToString(",") { StableIds.formatProbability(it) }
                )
            )
            val curveId = StableIds.contentId(
                "cur",
                listOf("version" to version, "fingerprint" to fingerprint)
            )
            val curve = if (diagnostics.none { it.lineNo == 0 })
                CalibrationCurve(curveId, version, displayName, sourceRef, points, fingerprint) else null
            return ParseResult(curve, header, diagnostics, fingerprint, curveId)
        }
    }

    @kotlinx.serialization.Serializable
    data class CurveDiagnostic(val lineNo: Int, val rawLine: String, val message: String)

    data class ParseResult(
        val curve: CalibrationCurve?,
        val header: Map<String, String>,
        val diagnostics: List<CurveDiagnostic>,
        val fingerprint: String,
        val curveId: String
    )
}
