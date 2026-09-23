package bench

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.exp
import kotlin.math.sqrt

/** One control point of a calibration curve. All values carry their coordinate basis:
 *  calBp = calendar years before 1950 (cal BP), bp = radiocarbon years BP, sigma in 14C years. */
data class CurvePoint(val calBp: Double, val bp: Double, val sigma: Double)

data class Curve(val inputKey: String, val name: String, val points: List<CurvePoint>) {
    init {
        require(points.size >= 2) { "curve needs at least 2 control points" }
        require(points.zipWithNext().all { (a, b) -> a.calBp < b.calBp }) { "curve control points must be strictly increasing in calBP" }
    }

    /** Linear interpolation between control points. Outside the table the nearest
     *  control point is held constant and the call is flagged as a boundary clamp. */
    fun interp(calBp: Double): Pair<Pair<Double, Double>, Boolean> {
        val pts = points
        if (calBp <= pts.first().calBp) return (pts.first().bp to pts.first().sigma) to (calBp < pts.first().calBp)
        if (calBp >= pts.last().calBp) return (pts.last().bp to pts.last().sigma) to (calBp > pts.last().calBp)
        var lo = 0
        var hi = pts.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) / 2
            if (pts[mid].calBp <= calBp) lo = mid else hi = mid
        }
        val a = pts[lo]
        val b = pts[hi]
        val f = (calBp - a.calBp) / (b.calBp - a.calBp)
        return ((a.bp + f * (b.bp - a.bp)) to (a.sigma + f * (b.sigma - a.sigma))) to false
    }
}

/** Fixed grid parameters. The fingerprint pins them so reproduction is version-locked. */
data class CalibParams(
    val gridStart: Double = 0.0,
    val gridEnd: Double = 6000.0,
    val gridStep: Double = 5.0
) {
    fun fingerprint(): String = sha256("$ALGORITHM_VERSION|$gridStart|$gridEnd|$gridStep")
    fun grid(): List<Double> {
        val out = ArrayList<Double>()
        var t = gridStart
        while (t <= gridEnd + 1e-9) {
            out.add(t)
            t += gridStep
        }
        return out
    }
}

/** Reservoir offset as a distribution N(mean, sigma) in 14C years — never a bare point. */
data class OffsetDistribution(val mean: Double, val sigma: Double)

data class CalibInterval(
    val index: Int,
    val fromCalBp: Double,
    val toCalBp: Double,
    val probability: Double,
    val fromEra: String,
    val toEra: String,
    val pointCount: Int,
    val trapezoidSum: Double,
    val normalization: Double
)

data class CalibrationOutcome(
    val grid: List<Double>,
    val density: List<Double>,          // normalized so trapezoid(density) == 1
    val likelihood: List<Double>,       // raw likelihood before normalization
    val normalization: Double,
    val peaks: List<Double>,
    val intervals68: List<CalibInterval>,
    val intervals95: List<CalibInterval>,
    val clamped: Boolean
)

/** Astronomical year numbering: 1 = 1 CE, 0 = 1 BCE, -n = (n+1) BCE. */
fun calBpToEra(calBp: Double): String {
    val astro = 1950.0 - calBp
    val rounded = astro.toInt()
    return if (rounded >= 1) "$rounded CE" else "${1 - rounded} BCE"
}

object CalibrationEngine {
    fun calibrate(
        radiocarbonBp: Double,
        radiocarbonSigma: Double,
        curve: Curve,
        offset: OffsetDistribution,
        params: CalibParams = CalibParams()
    ): CalibrationOutcome {
        val grid = params.grid()
        val likelihood = DoubleArray(grid.size)
        var clamped = false
        for (i in grid.indices) {
            val t = grid[i]
            val (curveEstimate, wasClamped) = curve.interp(t)
            if (wasClamped) clamped = true
            val (mu, sigmaC) = curveEstimate
            val variance = radiocarbonSigma * radiocarbonSigma + sigmaC * sigmaC + offset.sigma * offset.sigma
            val centered = radiocarbonBp - offset.mean - mu
            likelihood[i] = exp(-0.5 * centered * centered / variance) / sqrt(2.0 * Math.PI * variance)
        }
        val norm = trapezoid(grid, likelihood)
        require(norm > 0.0) { "calibration likelihood integrates to zero on the fixed grid" }
        val density = DoubleArray(grid.size) { likelihood[it] / norm }
        val peaks = findPeaks(grid, density)
        val intervals68 = hpdIntervals(grid, density, 0.683, norm)
        val intervals95 = hpdIntervals(grid, density, 0.954, norm)
        return CalibrationOutcome(
            grid = grid,
            density = density.toList(),
            likelihood = likelihood.toList(),
            normalization = norm,
            peaks = peaks,
            intervals68 = intervals68,
            intervals95 = intervals95,
            clamped = clamped
        )
    }

    internal fun trapezoid(grid: List<Double>, values: DoubleArray): Double {
        var sum = 0.0
        for (i in 0 until grid.size - 1) {
            sum += (values[i] + values[i + 1]) * 0.5 * (grid[i + 1] - grid[i])
        }
        return sum
    }

    private fun findPeaks(grid: List<Double>, density: DoubleArray): List<Double> {
        val peaks = ArrayList<Double>()
        var max = 0.0
        for (v in density) if (v > max) max = v
        val threshold = max * 0.05
        for (i in 1 until grid.size - 1) {
            if (density[i] >= threshold && density[i] >= density[i - 1] && density[i] >= density[i + 1]
                && (density[i] > density[i - 1] || density[i] > density[i + 1])
            ) {
                peaks.add(grid[i])
            }
        }
        if (peaks.isEmpty() && max > 0) {
            var best = 0
            for (i in density.indices) if (density[i] > density[best]) best = i
            peaks.add(grid[best])
        }
        return peaks
    }

    /** Highest-density regions: contiguous grid runs whose density clears the level
     *  that captures [mass] of the total probability. Multimodal output is preserved
     *  as并列 intervals — never collapsed into a mean year. */
    internal fun hpdIntervals(grid: List<Double>, density: DoubleArray, mass: Double, norm: Double): List<CalibInterval> {
        val stepMasses = DoubleArray(grid.size)
        for (i in grid.indices) {
            val half = if (i == 0 || i == grid.size - 1) 0.5 else 1.0
            stepMasses[i] = density[i] * gridStepAt(grid, i) * half
        }
        val order = density.indices.sortedByDescending { density[it] }
        var acc = 0.0
        var level = 0.0
        for (idx in order) {
            acc += stepMasses[idx]
            level = density[idx]
            if (acc >= mass) break
        }
        val intervals = ArrayList<CalibInterval>()
        var i = 0
        var index = 0
        while (i < grid.size) {
            if (density[i] >= level && density[i] > 0.0) {
                var j = i
                while (j + 1 < grid.size && density[j + 1] >= level) j++
                val slice = DoubleArray(j - i + 1) { density[i + it] }
                val sub = grid.subList(i, j + 1)
                val trap = trapezoid(sub, slice)
                intervals.add(
                    CalibInterval(
                        index = index++,
                        fromCalBp = grid[i],
                        toCalBp = grid[j],
                        probability = trap,
                        fromEra = calBpToEra(grid[i]),
                        toEra = calBpToEra(grid[j]),
                        pointCount = slice.size,
                        trapezoidSum = trap * norm,
                        normalization = norm
                    )
                )
                i = j + 1
            } else {
                i++
            }
        }
        return intervals
    }

    private fun gridStepAt(grid: List<Double>, i: Int): Double = when {
        grid.size < 2 -> 1.0
        i == 0 -> grid[1] - grid[0]
        i == grid.size - 1 -> grid[grid.size - 1] - grid[grid.size - 2]
        else -> (grid[i + 1] - grid[i - 1]) / 2.0
    }
}

fun CalibrationOutcome.toJson(): JsonObject = buildJsonObject {
    put("normalization", JsonPrimitive(normalization))
    put("clamped", JsonPrimitive(clamped))
    put("peaks", JsonArray(peaks.map { JsonPrimitive(it) }))
    put("grid", JsonArray(grid.map { JsonPrimitive(it) }))
    put("density", JsonArray(density.map { JsonPrimitive(it) }))
    put("intervals68", intervalsToJson(intervals68))
    put("intervals95", intervalsToJson(intervals95))
}

fun intervalsToJson(intervals: List<CalibInterval>): JsonArray = buildJsonArray {
    for (iv in intervals) {
        add(buildJsonObject {
            put("index", JsonPrimitive(iv.index))
            put("fromCalBp", JsonPrimitive(iv.fromCalBp))
            put("toCalBp", JsonPrimitive(iv.toCalBp))
            put("probability", JsonPrimitive(iv.probability))
            put("fromEra", JsonPrimitive(iv.fromEra))
            put("toEra", JsonPrimitive(iv.toEra))
            put("pointCount", JsonPrimitive(iv.pointCount))
            put("trapezoidSum", JsonPrimitive(iv.trapezoidSum))
            put("normalization", JsonPrimitive(iv.normalization))
        })
    }
}

fun curveFromContent(inputKey: String, content: JsonObject): Curve {
    val name = content["name"]!!.jsonPrimitive.content
    val pts = content["points"]!!.let { it as JsonArray }.map { el ->
        val o = el.jsonObject
        CurvePoint(
            o["calBp"]!!.jsonPrimitive.double,
            o["bp"]!!.jsonPrimitive.double,
            o["sigma"]!!.jsonPrimitive.double
        )
    }
    return Curve(inputKey, name, pts)
}
