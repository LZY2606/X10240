package workbench.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import kotlin.math.*

const val CALIBRATION_ALGORITHM_VERSION = "calib-1.2.0"
const val CURVE_GENERATOR_VERSION = "curvegen-1.0.0"

/** One row of a calibration curve: cal BP -> conventional C14 age BP with 1-sigma. */
@Serializable
data class CurvePoint(val calBp: Int, val c14Bp: Double, val sigma: Double)

@Serializable
data class CurveTable(val name: String, val version: String, val points: List<CurvePoint>) {
    val minCalBp: Int get() = points.first().calBp
    val maxCalBp: Int get() = points.last().calBp

    /** Piecewise-linear interpolation. Outside the table the value is clamped and
     *  the caller is expected to record a boundary diagnostic. */
    fun interpolate(calBp: Double): Pair<Double, Double> {
        if (calBp <= minCalBp) return points.first().let { it.c14Bp to it.sigma }
        if (calBp >= maxCalBp) return points.last().let { it.c14Bp to it.sigma }
        val lo = calBp.floorToInt()
        val idx = points.indexOfFirst { it.calBp > lo } - 1
        val a = points[idx.coerceAtLeast(0)]
        val b = points[(idx + 1).coerceAtMost(points.lastIndex)]
        if (b.calBp == a.calBp) return a.c14Bp to a.sigma
        val f = (calBp - a.calBp) / (b.calBp - a.calBp)
        return (a.c14Bp + f * (b.c14Bp - a.c14Bp)) to (a.sigma + f * (b.sigma - a.sigma))
    }
}

/**
 * Deterministic synthetic curve generator (offline stand-in for IntCal).
 * Versioned so "fixed version => reproducible interpolation & normalization".
 */
object CurveFactory {
    fun generate(name: String, version: String, variant: Int): CurveTable {
        val pts = ArrayList<CurvePoint>()
        var cal = 0
        while (cal <= 12000) {
            val base = cal * 0.92 + 120.0
            val w1 = 95.0 * sin(cal / 340.0 + variant * 0.7)
            val w2 = 55.0 * sin(cal / 137.0 + 1.3 + variant * 0.31)
            val w3 = 30.0 * sin(cal / 61.0 + 0.4)
            val shift = if (variant >= 2 && cal in 2500..3600) 60.0 * sin((cal - 2500) / 1100.0 * PI) else 0.0
            val c14 = base + w1 + w2 + w3 + shift
            val sigma = 18.0 + 7.0 * (0.5 + 0.5 * sin(cal / 500.0))
            pts.add(CurvePoint(cal, round2(c14), round2(sigma)))
            cal += 5
        }
        return CurveTable(name, version, pts)
    }
    private fun round2(x: Double) = round(x * 100.0) / 100.0
}

/** Calendar coordinate label with explicit era basis (no year zero). */
@Serializable
data class CalYear(val year: Int, val era: String) { // era: "BCE" | "CE"
    override fun toString() = "$year $era"
    fun toSortKey(): Int = if (era == "BCE") -(year) else year
}

object Chronology {
    /** cal BP (before 1950 CE) -> BCE/CE label. No year zero. */
    fun calBpToCalYear(calBp: Double): CalYear {
        val y = 1950 - calBp.roundToInt()
        return if (y >= 1) CalYear(y, "CE") else CalYear(1 - y, "BCE")
    }
    fun calYearToCalBp(year: Int, era: String): Int =
        if (era == "CE") 1950 - year else 1950 + year - 1
}

@Serializable
data class HpdInterval(
    val fromCalBp: Int,
    val toCalBp: Int,
    val fromLabel: String,
    val toLabel: String,
    val mass: Double,
    val level: Double,
    val index: Int,
)

@Serializable
data class Mode(val calBp: Int, val label: String, val density: Double)

@Serializable
data class CalibrationPayload(
    val sampleId: String,
    val measuredBp: Double,
    val measuredSigma: Double,
    val offsetMean: Double,
    val offsetSigma: Double,
    val curveName: String,
    val curveVersion: String,
    val gridFromCalBp: Int,
    val gridToCalBp: Int,
    val gridStep: Int,
    val normalization: Double,
    val truncatedLow: Boolean,
    val truncatedHigh: Boolean,
    val modes: List<Mode>,
    val hpd68: List<HpdInterval>,
    val hpd95: List<HpdInterval>,
    val densities: List<Double>, // normalized density per grid point, reproducible
) {
    /** Integration evidence for one interval: sum of normalized densities over the grid span. */
    fun intervalEvidence(interval: HpdInterval): JsonObject {
        val fromIdx = (interval.fromCalBp - gridFromCalBp) / gridStep
        val toIdx = (interval.toCalBp - gridFromCalBp) / gridStep
        val slice = densities.subList(fromIdx.coerceAtLeast(0), (toIdx + 1).coerceAtMost(densities.size))
        val integral = slice.sum() * gridStep
        return buildJsonObject {
            put("intervalIndex", interval.index)
            put("level", interval.level)
            put("fromCalBp", interval.fromCalBp)
            put("toCalBp", interval.toCalBp)
            put("fromLabel", interval.fromLabel)
            put("toLabel", interval.toLabel)
            put("gridStep", gridStep)
            put("gridPoints", slice.size)
            put("integral", round6(integral))
            put("normalization", normalization)
            put("algorithmVersion", CALIBRATION_ALGORITHM_VERSION)
            put("curveVersion", curveVersion)
            put("offsetMean", offsetMean)
            put("offsetSigma", offsetSigma)
        }
    }
}

private fun round6(x: Double) = round(x * 1e6) / 1e6

object Calibrator {
    /**
     * Grid calibration with reservoir offset folded in. Multi-modal output is
     * preserved as modes + disjoint HPD intervals; never collapsed to a mean.
     */
    fun calibrate(
        sampleId: String,
        measuredBp: Double,
        measuredSigma: Double,
        offsetMean: Double,
        offsetSigma: Double,
        curve: CurveTable,
        marginSigma: Double = 5.0,
    ): CalibrationPayload {
        val adjusted = measuredBp - offsetMean
        val totalVar = measuredSigma * measuredSigma + offsetSigma * offsetSigma
        // grid range: where curve mean is within marginSigma of adjusted age
        val totalSigma = sqrt(totalVar + 30.0 * 30.0)
        val span = marginSigma * totalSigma
        // find calBp range whose curve c14 is near adjusted age
        var lo = curve.minCalBp.toDouble()
        var hi = curve.maxCalBp.toDouble()
        var foundLo = false
        var foundHi = false
        var t = curve.minCalBp.toDouble()
        while (t <= curve.maxCalBp) {
            val (mu, _) = curve.interpolate(t)
            if (abs(mu - adjusted) <= span) {
                if (!foundLo) { lo = t; foundLo = true }
                hi = t; foundHi = true
            }
            t += 1.0
        }
        if (!foundHi) { lo = curve.minCalBp.toDouble(); hi = curve.maxCalBp.toDouble() }
        val from = lo.toInt().coerceAtLeast(curve.minCalBp)
        val to = hi.toInt().coerceAtMost(curve.maxCalBp)
        val step = 1
        val n = (to - from) / step + 1
        val raw = DoubleArray(n)
        var truncatedLow = false
        var truncatedHigh = false
        for (i in 0 until n) {
            val calBp = from + i * step
            val (mu, sigmaC) = curve.interpolate(calBp.toDouble())
            val v = totalVar + sigmaC * sigmaC
            val z = (mu - adjusted)
            raw[i] = exp(-z * z / (2.0 * v)) / sqrt(v)
        }
        // boundary truncation diagnostics: density at curve edges still significant
        val peak = raw.max()
        if (from == curve.minCalBp && raw[0] > 0.05 * peak) truncatedLow = true
        if (to == curve.maxCalBp && raw[n - 1] > 0.05 * peak) truncatedHigh = true
        val norm = raw.sum() * step
        val densities = raw.map { round6(it / norm) }
        val modes = findModes(densities, from, step)
        val hpd68 = hpd(densities, from, step, 0.6827)
        val hpd95 = hpd(densities, from, step, 0.9545)
        return CalibrationPayload(
            sampleId = sampleId,
            measuredBp = measuredBp,
            measuredSigma = measuredSigma,
            offsetMean = offsetMean,
            offsetSigma = offsetSigma,
            curveName = curve.name,
            curveVersion = curve.version,
            gridFromCalBp = from,
            gridToCalBp = to,
            gridStep = step,
            normalization = round6(norm),
            truncatedLow = truncatedLow,
            truncatedHigh = truncatedHigh,
            modes = modes,
            hpd68 = hpd68,
            hpd95 = hpd95,
            densities = densities,
        )
    }

    private fun findModes(densities: List<Double>, from: Int, step: Int): List<Mode> {
        val out = ArrayList<Mode>()
        val max = densities.max()
        var i = 1
        while (i < densities.size - 1) {
            if (densities[i] >= densities[i - 1] && densities[i] >= densities[i + 1] && densities[i] > 0.15 * max) {
                val calBp = from + i * step
                if (out.none { abs(it.calBp - calBp) < 15 }) {
                    out.add(Mode(calBp, Chronology.calBpToCalYear(calBp.toDouble()).toString(), densities[i]))
                }
            }
            i++
        }
        return out
    }

    /** Highest-posterior-density disjoint intervals at [level] mass. */
    fun hpd(densities: List<Double>, from: Int, step: Int, level: Double): List<HpdInterval> {
        val order = densities.indices.sortedByDescending { densities[it] }
        val target = level / step.coerceAtLeast(1)
        var acc = 0.0
        val chosen = sortedSetOf<Int>()
        for (idx in order) {
            if (acc >= level) break
            chosen.add(idx)
            acc += densities[idx] * step
        }
        val intervals = ArrayList<HpdInterval>()
        var i = 0
        var k = 0
        while (i < densities.size) {
            if (i in chosen) {
                var j = i
                var mass = 0.0
                while (j < densities.size && j in chosen) { mass += densities[j] * step; j++ }
                val a = from + i * step
                val b = from + (j - 1) * step
                intervals.add(
                    HpdInterval(
                        fromCalBp = b, toCalBp = a, // older first: larger calBp is older
                        fromLabel = Chronology.calBpToCalYear(b.toDouble()).toString(),
                        toLabel = Chronology.calBpToCalYear(a.toDouble()).toString(),
                        mass = round6(mass),
                        level = level,
                        index = k++,
                    )
                )
                i = j
            } else i++
        }
        return intervals.sortedByDescending { it.mass }
    }
}

/** Parse a CurveTable from its raw source JSON (kept in original representation). */
fun curveFromRaw(raw: JsonObject): CurveTable {
    val pts = raw["points"]!!.jsonArray.map {
        val a = it.jsonArray
        CurvePoint(a[0].jsonPrimitive.int, a[1].jsonPrimitive.double, a[2].jsonPrimitive.double)
    }
    return CurveTable(
        raw["name"]!!.jsonPrimitive.content,
        raw["version"]!!.jsonPrimitive.content,
        pts,
    )
}

fun curveToRaw(curve: CurveTable): JsonObject = buildJsonObject {
    put("name", curve.name)
    put("version", curve.version)
    put("generator", CURVE_GENERATOR_VERSION)
    putJsonArray("points") {
        curve.points.forEach { p -> add(JsonArray(listOf(JsonPrimitive(p.calBp), JsonPrimitive(p.c14Bp), JsonPrimitive(p.sigma)))) }
    }
}
