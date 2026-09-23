package workbench

import kotlinx.serialization.Serializable
import java.security.MessageDigest
import kotlin.math.*

const val ALGORITHM_VERSION = "calib-1.0.0"
const val GRID_STEP_YEARS = 1

fun sha256(text: String): String =
    MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
        .joinToString("") { "%02x".format(it) }

fun fingerprintOf(vararg parts: String): String =
    sha256(parts.sorted().joinToString("|")).take(16)

fun normalPdf(x: Double, sigma: Double): Double =
    exp(-0.5 * (x / sigma).pow(2)) / (sigma * sqrt(2 * PI))

/** Convert cal BP to BCE/CE label. Astronomical year 0 = 1 BCE. */
fun bpToEra(calBp: Int): String {
    val astro = 1950 - calBp
    return if (astro > 0) "$astro CE" else "${1 - astro} BCE"
}

@Serializable
data class CurvePoint(val calBp: Int, val c14Age: Double, val sigma: Double)

@Serializable
data class CalibrationCurve(
    val stream: String,
    val version: Int,
    val name: String,
    val points: List<CurvePoint>,
) {
    val minBp: Int get() = points.minOf { it.calBp }
    val maxBp: Int get() = points.maxOf { it.calBp }

    /** Linear interpolation; clamps at boundaries and reports whether clamping happened. */
    fun sample(calBp: Int): Triple<Double, Double, Boolean> {
        val sorted = points.sortedBy { it.calBp }
        if (calBp <= sorted.first().calBp) return Triple(sorted.first().c14Age, sorted.first().sigma, true)
        if (calBp >= sorted.last().calBp) return Triple(sorted.last().c14Age, sorted.last().sigma, true)
        val upper = sorted.indexOfFirst { it.calBp >= calBp }
        val hi = sorted[upper]
        val lo = sorted[upper - 1]
        val t = (calBp - lo.calBp).toDouble() / (hi.calBp - lo.calBp)
        return Triple(lo.c14Age + t * (hi.c14Age - lo.c14Age), lo.sigma + t * (hi.sigma - lo.sigma), false)
    }
}

@Serializable
data class OffsetDistribution(
    val stream: String,
    val version: Int,
    val name: String,
    val mean: Double,
    val sigma: Double,
    val unit: String = "14C yr BP",
)

@Serializable
data class DensityCell(val calBp: Int, val density: Double)

@Serializable
data class CalInterval(val fromBp: Int, val toBp: Int, val probability: Double, val level: Double) {
    val fromEra: String get() = bpToEra(fromBp)
    val toEra: String get() = bpToEra(toBp)
    val crossesEra: Boolean get() = fromBp > 1950 && toBp < 1950
}

@Serializable
data class EvidenceSegment(val fromBp: Int, val toBp: Int, val cells: Int, val mass: Double)

@Serializable
data class IntervalEvidence(val interval: CalInterval, val segments: List<EvidenceSegment>, val gridStep: Int, val normalizedMass: Double)

@Serializable
data class CalibrationOutput(
    val cells: List<DensityCell>,
    val intervals682: List<CalInterval>,
    val intervals954: List<CalInterval>,
    val edgeInterpolated: Boolean,
    val normalizedMass: Double,
    val effectiveC14: Double,
    val effectiveSigma: Double,
    val algorithmVersion: String,
) {
    /** Integration evidence for one interval: the grid segments whose mass sums to its probability. */
    fun evidenceFor(interval: CalInterval): IntervalEvidence {
        val inside = cells.filter { it.calBp in interval.fromBp..interval.toBp && it.density > 0 }
        val segments = mutableListOf<EvidenceSegment>()
        var runStart: Int? = null
        var runCells = 0
        var runMass = 0.0
        var prev: Int? = null
        for (cell in inside) {
            if (runStart == null || prev == null || cell.calBp != prev!! + GRID_STEP_YEARS) {
                if (runStart != null) segments.add(EvidenceSegment(runStart!!, prev!!, runCells, runMass))
                runStart = cell.calBp; runCells = 0; runMass = 0.0
            }
            runCells++; runMass += cell.density; prev = cell.calBp
        }
        if (runStart != null) segments.add(EvidenceSegment(runStart!!, prev!!, runCells, runMass))
        return IntervalEvidence(interval, segments, GRID_STEP_YEARS, normalizedMass)
    }
}

object Calibrator {
    /**
     * Calibrate a (possibly offset-adjusted) conventional radiocarbon age against a curve.
     * Multi-modal structure is preserved as a normalized 1-year grid; no mean year is produced.
     */
    fun calibrate(c14Age: Double, sigma: Double, curve: CalibrationCurve): CalibrationOutput {
        val cells = mutableListOf<DensityCell>()
        var edge = false
        for (calBp in curve.minBp..curve.maxBp step GRID_STEP_YEARS) {
            val (curveC14, curveSigma, clamped) = curve.sample(calBp)
            val totalSigma = sqrt(sigma * sigma + curveSigma * curveSigma)
            val w = normalPdf(c14Age - curveC14, totalSigma)
            cells.add(DensityCell(calBp, w))
            if (clamped) edge = true
        }
        val sum = cells.sumOf { it.density }
        require(sum > 0) { "calibration grid has zero mass" }
        val normalized = cells.map { DensityCell(it.calBp, it.density / sum) }
        // Boundary truncation: edge cell still carries >0.5% of peak density.
        val peak = normalized.maxOf { it.density }
        val truncated = normalized.first().density > 0.005 * peak || normalized.last().density > 0.005 * peak
        return CalibrationOutput(
            cells = normalized,
            intervals682 = hdrIntervals(normalized, 0.682),
            intervals954 = hdrIntervals(normalized, 0.954),
            edgeInterpolated = edge || truncated,
            normalizedMass = normalized.sumOf { it.density },
            effectiveC14 = c14Age,
            effectiveSigma = sigma,
            algorithmVersion = ALGORITHM_VERSION,
        )
    }

    /** Highest-density regions: contiguous runs above the mass threshold. */
    fun hdrIntervals(cells: List<DensityCell>, level: Double): List<CalInterval> {
        val sorted = cells.sortedByDescending { it.density }
        var acc = 0.0
        var threshold = 0.0
        for (c in sorted) {
            acc += c.density
            threshold = c.density
            if (acc >= level) break
        }
        val intervals = mutableListOf<CalInterval>()
        var start: Int? = null
        var mass = 0.0
        var prev: Int? = null
        for (cell in cells.sortedBy { it.calBp }) {
            val inside = cell.density >= threshold && threshold > 0
            if (inside) {
                if (start == null || prev == null || cell.calBp != prev!! + GRID_STEP_YEARS) {
                    if (start != null) intervals.add(CalInterval(start!!, prev!!, mass, level))
                    start = cell.calBp; mass = 0.0
                }
                mass += cell.density
                prev = cell.calBp
            }
        }
        if (start != null) intervals.add(CalInterval(start!!, prev!!, mass, level))
        return intervals
    }

    /** Apply a reservoir offset distribution to a measurement. */
    fun applyOffset(c14Age: Double, sigma: Double, offset: OffsetDistribution): Pair<Double, Double> =
        c14Age - offset.mean to sqrt(sigma * sigma + offset.sigma * offset.sigma)

    /** P(below is older than above) from two normalized posteriors. below/above use cal BP (larger = older). */
    fun probabilityOlder(below: List<DensityCell>, above: List<DensityCell>): Double {
        val aboveCdf = mutableMapOf<Int, Double>()
        var cum = 0.0
        for (c in above.sortedByDescending { it.calBp }) { cum += c.density; aboveCdf[c.calBp] = cum }
        var p = 0.0
        for (b in below) {
            // P(above calBp < b.calBp) = mass of above younger than b
            val younger = above.filter { it.calBp < b.calBp }.sumOf { it.density }
            p += b.density * younger
        }
        return p
    }
}

@Serializable
data class ConflictCandidate(val type: String, val targetStream: String, val detail: String)

@Serializable
data class SequenceAssessment(
    val constraintStream: String,
    val belowStream: String,
    val aboveStream: String,
    val probabilityBelowOlder: Double,
    val conflict: Boolean,
    val candidates: List<ConflictCandidate>,
)
