package workbench

import kotlinx.serialization.Serializable
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sqrt

const val ALGO_CALIBRATION = "kairo-calib/1.3.0"
const val ALGO_SEQUENCE = "kairo-seq/1.1.0"

/** Reservoir offset assumption. Values in c14 years. */
@Serializable
sealed interface OffsetSpec {
    @Serializable
    data object None : OffsetSpec

    @Serializable
    data class Normal(val mean: Double, val sigma: Double) : OffsetSpec

    /** Discrete distribution: (offset value, weight). Weights need not be normalized. */
    @Serializable
    data class Discrete(val samples: List<List<Double>>) : OffsetSpec
}

fun normalPdf(x: Double, mu: Double, sigma: Double): Double {
    val z = (x - mu) / sigma
    return exp(-0.5 * z * z) / (sigma * sqrt(2.0 * PI))
}

/** Calendar era axis: calBP relative to 1950, displayed as BCE/CE with NO year zero. */
object Era {
    /** calBP -> signed CE year number; 1 BCE = 0, 2 BCE = -1 (astronomical numbering). */
    fun ceYear(calBP: Double): Double = 1950.0 - calBP

    fun format(calBP: Double): String {
        val y = ceYear(calBP)
        val r = kotlin.math.round(y).toInt()
        return if (r >= 1) "$r CE" else "${1 - r} BCE"
    }
}

@Serializable
data class IntervalEvidence(
    val level: Double,
    val loCalBP: Double,
    val hiCalBP: Double,
    val loEra: String,
    val hiEra: String,
    val mass: Double,
    val gridPoints: Int,
    val gridStep: Double,
    val normalization: Double,
    val curveId: String,
    val curveRevision: Int,
    val loSegment: String,
    val hiSegment: String,
    val offset: String,
)

@Serializable
data class CalInterval(
    val id: String,
    val level: Double,
    val loCalBP: Double,
    val hiCalBP: Double,
    val loEra: String,
    val hiEra: String,
    val mass: Double,
    val evidence: IntervalEvidence,
)

@Serializable
data class CalibrationPayload(
    val gridStart: Double,
    val gridStep: Double,
    val density: List<Double>,
    val peaksCalBP: List<Double>,
    val intervals: List<CalInterval>,
    val normalization: Double,
    val curveId: String,
    val curveRevision: Int,
    val measurementC14: Double,
    val measurementSigma: Double,
    val offset: String,
    val boundaryDiagnostics: List<String>,
)

object Calibrator {
    const val GRID_STEP = 1.0
    val LEVELS = listOf(0.6827, 0.9545)

    data class Output(val payload: CalibrationPayload, val boundaryDiagnostics: List<String>)

    fun offsetLabel(o: OffsetSpec): String = when (o) {
        is OffsetSpec.None -> "none"
        is OffsetSpec.Normal -> "normal(${o.mean},${o.sigma})"
        is OffsetSpec.Discrete -> "discrete(" + o.samples.joinToString(",") { "${it[0]}:${it[1]}" } + ")"
    }

    fun calibrate(
        measurementC14: Double,
        measurementSigma: Double,
        curve: CalibrationCurve,
        offset: OffsetSpec,
        gridMin: Int = curve.minCalBP,
        gridMax: Int = curve.maxCalBP,
    ): Output {
        val n = ((gridMax - gridMin) / GRID_STEP).toInt() + 1
        val raw = DoubleArray(n)
        val discrete: List<Pair<Double, Double>>? = (offset as? OffsetSpec.Discrete)?.let { spec ->
            val wsum = spec.samples.sumOf { it[1] }
            spec.samples.map { it[0] to it[1] / wsum }
        }
        val normalOffset = offset as? OffsetSpec.Normal
        for (i in 0 until n) {
            val t = gridMin + i * GRID_STEP
            val seg = curve.segmentAt(t)
            raw[i] = when {
                discrete != null -> discrete.sumOf { (v, w) ->
                    val s = sqrt(measurementSigma * measurementSigma + seg.sigma * seg.sigma)
                    w * normalPdf(measurementC14, seg.mu + v, s)
                }
                normalOffset != null -> {
                    val s = sqrt(
                        measurementSigma * measurementSigma + seg.sigma * seg.sigma +
                            normalOffset.sigma * normalOffset.sigma
                    )
                    normalPdf(measurementC14, seg.mu + normalOffset.mean, s)
                }
                else -> {
                    val s = sqrt(measurementSigma * measurementSigma + seg.sigma * seg.sigma)
                    normalPdf(measurementC14, seg.mu, s)
                }
            }
        }
        val z = raw.sum() * GRID_STEP
        val density = raw.map { it / z }

        // Boundary diagnostics: non-negligible density at a grid edge implies truncation.
        val maxD = density.max()
        val diags = ArrayList<String>()
        val edgeRatioLo = density.first() / maxD
        val edgeRatioHi = density.last() / maxD
        if (edgeRatioLo > 0.001) {
            diags.add("lower-boundary: density at ${gridMin} calBP is ${"%.4f".format(edgeRatioLo)} of peak; distribution truncated at curve edge, interpolation clamped to first segment")
        }
        if (edgeRatioHi > 0.001) {
            diags.add("upper-boundary: density at ${gridMax} calBP is ${"%.4f".format(edgeRatioHi)} of peak; distribution truncated at curve edge, interpolation clamped to last segment")
        }

        // Peaks: local maxima above 1% of global max.
        val peaks = ArrayList<Double>()
        for (i in 1 until n - 1) {
            if (density[i] >= density[i - 1] && density[i] > density[i + 1] && density[i] > 0.01 * maxD) {
                if (peaks.isEmpty() || gridMin + i * GRID_STEP - peaks.last() > 5) {
                    peaks.add(gridMin + i * GRID_STEP)
                }
            }
        }

        // HPD intervals per level; parallel intervals are kept side by side, never averaged.
        val intervals = ArrayList<CalInterval>()
        for (level in LEVELS) {
            val order = density.indices.sortedByDescending { density[it] }
            var acc = 0.0
            var k = 0
            while (k < order.size && acc < level) { acc += density[order[k]] * GRID_STEP; k++ }
            val threshold = if (k == 0) 0.0 else density[order[k - 1]]
            var i = 0
            var runIdx = 0
            while (i < n) {
                if (density[i] >= threshold) {
                    var j = i
                    var mass = 0.0
                    while (j < n && density[j] >= threshold) { mass += density[j] * GRID_STEP; j++ }
                    val lo = gridMin + i * GRID_STEP
                    val hi = gridMin + (j - 1) * GRID_STEP
                    val segLo = curve.segmentAt(lo)
                    val segHi = curve.segmentAt(hi)
                    val id = Ids.of("ivl", curve.curveId, curve.revision.toString(),
                        measurementC14.toString(), measurementSigma.toString(),
                        offsetLabel(offset), level.toString(), lo.toString(), hi.toString())
                    val ev = IntervalEvidence(
                        level = level, loCalBP = lo, hiCalBP = hi,
                        loEra = Era.format(hi), hiEra = Era.format(lo),
                        mass = mass, gridPoints = j - i, gridStep = GRID_STEP,
                        normalization = z, curveId = curve.curveId, curveRevision = curve.revision,
                        loSegment = "[${segLo.lo.calBP} calBP ${"%.2f".format(segLo.lo.c14)}] -> [${segLo.hi.calBP} calBP ${"%.2f".format(segLo.hi.c14)}]",
                        hiSegment = "[${segHi.lo.calBP} calBP ${"%.2f".format(segHi.lo.c14)}] -> [${segHi.hi.calBP} calBP ${"%.2f".format(segHi.hi.c14)}]",
                        offset = offsetLabel(offset),
                    )
                    intervals.add(CalInterval(id, level, lo, hi, Era.format(hi), Era.format(lo), mass, ev))
                    runIdx++
                    i = j
                } else i++
            }
        }

        val payload = CalibrationPayload(
            gridStart = gridMin.toDouble(), gridStep = GRID_STEP, density = density,
            peaksCalBP = peaks, intervals = intervals, normalization = z,
            curveId = curve.curveId, curveRevision = curve.revision,
            measurementC14 = measurementC14, measurementSigma = measurementSigma,
            offset = offsetLabel(offset), boundaryDiagnostics = diags,
        )
        return Output(payload, diags)
    }
}
