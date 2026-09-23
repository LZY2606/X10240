package org.chrono.calc

import kotlinx.serialization.Serializable
import org.chrono.model.CalibrationCurveSpec
import org.chrono.model.CurvePoint
import org.chrono.model.canonicalJson
import org.chrono.model.OffsetDist
import org.chrono.model.Units
import org.chrono.model.Years
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Algorithm versions of the derived layer; changing math must bump a version. */
object Algorithms {
    const val CALIBRATION = "cal-linear-grid/v1.0"
    const val ORDERING = "order-chain-belief/v1.0"
}

/** Fixed seed used by any deterministic tie-breaking; the engine itself is seed-independent. */
const val FIXED_SEED: Long = 20260924L

class CalibrationCurve(val spec: CalibrationCurveSpec) {
    private val points = spec.points.sortedByDescending { it.calBp }

    init {
        require(points.size >= 2) { "curve ${spec.curveId}@${spec.version} needs at least 2 points" }
        require(points.zipWithNext().all { it.first.calBp > it.second.calBp }) {
            "curve ${spec.curveId}@${spec.version} must be strictly decreasing in cal BP"
        }
        require(points.all { it.sigma >= 0.0 }) { "curve sigmas must be non-negative" }
    }

    val calBpMax: Double = points.first().calBp
    val calBpMin: Double = points.last().calBp

    data class Interp(val c14Bp: Double, val sigma: Double, val inside: Boolean, val segmentIndex: Int)

    /**
     * Piecewise-linear interpolation. Outside the curve's span the value is held at the
     * boundary and [Interp.inside] is false so callers can flag truncated evidence rather
     * than silently extending the curve.
     */
    fun interp(calBp: Double): Interp {
        if (calBp >= calBpMax) return Interp(points.first().c14Bp, points.first().sigma, calBp == calBpMax, 0)
        if (calBp <= calBpMin) {
            val last = points.lastIndex
            return Interp(points.last().c14Bp, points.last().sigma, calBp == calBpMin, last - 1)
        }
        var lo = 0
        var hi = points.lastIndex
        while (hi - lo > 1) {
            val mid = (lo + hi) / 2
            if (points[mid].calBp >= calBp) lo = mid else hi = mid
        }
        val a = points[lo]
        val b = points[hi]
        val f = (calBp - a.calBp) / (b.calBp - a.calBp)
        return Interp(a.c14Bp + f * (b.c14Bp - a.c14Bp), a.sigma + f * (b.sigma - b.sigma), true, lo)
    }

    fun pointsList(): List<CurvePoint> = points
}

@Serializable
data class CalibrationConfig(
    val stepYear: Double = 5.0,
    val hpdLevels: List<Double> = listOf(0.68, 0.95),
    val modeSeparationYears: Double = 30.0,
    val modeProminence: Double = 0.10,
) {
    init {
        require(stepYear > 0.0)
        require(hpdLevels.all { it in 0.0001..0.9999 })
    }

    fun fingerprint(): String = org.chrono.model.Hashing.sha256Hex(canonicalJson(this)).take(16)
}

/** One density cell on the calendar grid (midpoint in both cal BP and astronomical years). */
@Serializable
data class DensityCell(
    val index: Int,
    val calBpMid: Double,
    val astroMid: Double,
    val densityPerYear: Double,
    val curveInside: Boolean,
)

@Serializable
data class HpdInterval(
    val intervalId: String,
    val level: Double,
    val astroStart: Double,
    val astroEnd: Double,
    val calBpStart: Double,
    val calBpEnd: Double,
    val labelStart: String,
    val labelEnd: String,
    val probability: Double,
    val crossesBceCe: Boolean,
    val truncatedAtCurveBoundary: Boolean,
)

@Serializable
data class IntervalEvidence(
    val evidenceId: String,
    val intervalId: String,
    val level: Double,
    val integratedCells: Int,
    val stepYear: Double,
    val unnormalizedMass: Double,
    val normalizedProbability: Double,
    val trapezoidRule: String,
    val astroStart: Double,
    val astroEnd: Double,
    val algorithmVersion: String,
    val parameterFingerprint: String,
    val curveId: String,
    val curveVersion: String,
    val curveContentSha: String,
    val offsetSummary: String,
)

@Serializable
data class ModeSummary(
    val modeId: String,
    val astroPeak: Double,
    val labelPeak: String,
    val localMass: Double,
)

@Serializable
data class CalibrationResult(
    val resultId: String,
    val sampleCode: String,
    val curveId: String,
    val curveVersion: String,
    val curveContentSha: String,
    val algorithmVersion: String,
    val parameterFingerprint: String,
    val gridCalBpMin: Double,
    val gridCalBpMax: Double,
    val stepYear: Double,
    val offsetSummary: String,
    val totalMassInside: Double,
    val boundaryTruncated: Boolean,
    val cells: List<DensityCell>,
    val intervals: List<HpdInterval>,
    val evidence: List<IntervalEvidence>,
    val modes: List<ModeSummary>,
    val multimodal: Boolean,
    val notCollapsedToMean: Boolean = true,
) {
    fun cellSeriesCsvPreview(): String =
        cells.take(4).joinToString(",") { "%.2f".format(it.astroMid) }
}

class CalibrationEngine(private val config: CalibrationConfig = CalibrationConfig()) {

    fun calibrate(
        sampleCode: String,
        measuredAge: Double,
        measuredSigma: Double,
        curve: CalibrationCurve,
        offset: OffsetDist,
    ): CalibrationResult {
        require(measuredSigma > 0.0) { "measurement sigma for $sampleCode must be positive" }
        val step = config.stepYear
        val gridLo = curve.calBpMin
        val gridHi = curve.calBpMax
        val count = ((gridHi - gridLo) / step).roundToInt() + 1

        val raws = ArrayList<RawCell>(count)
        var rawMass = 0.0
        var insideMass = 0.0
        var boundary = false
        for (i in 0 until count) {
            val calBp = gridLo + i * step
            val at = curve.interp(calBp)
            if (!at.inside && i != 0 && i != count - 1) boundary = true
            val likelihood = offsetWeightedLikelihood(measuredAge, measuredSigma, at.c14Bp, at.sigma, offset)
            raws += RawCell(i, calBp, likelihood, at.inside)
            rawMass += likelihood * step
            if (at.inside) insideMass += likelihood * step
        }
        if (rawMass <= 0.0) {
            error("calibration of $sampleCode carries zero probability under the given curve/offset")
        }
        val truncated = raws.first().weight > relativeThreshold(raws) || raws.last().weight > relativeThreshold(raws)

        val cells = raws.map { raw ->
            DensityCell(
                index = raw.index,
                calBpMid = raw.calBp,
                astroMid = Years.calBpToAstro(raw.calBp),
                densityPerYear = raw.weight / rawMass,
                curveInside = raw.inside,
            )
        }
        val modes = findModes(sampleCode, cells)
        val intervals = mutableListOf<HpdInterval>()
        val evidence = mutableListOf<IntervalEvidence>()
        val sortedDesc = cells.sortedByDescending { it.densityPerYear }
        config.hpdLevels.forEachIndexed { levelIndex, level ->
            val selected = buildHpd(cells, sortedDesc, level)
            selected.forEachIndexed { segmentIndex, segment ->
                val iid = "${org.chrono.model.IdKinds.INTERVAL}_${sampleCode}_${(level * 100).roundToInt()}_$segmentIndex"
                val interval = HpdInterval(
                    intervalId = iid,
                    level = level,
                    astroStart = segment.first().astroMid - step / 2,
                    astroEnd = segment.last().astroMid + step / 2,
                    calBpStart = segment.last().calBpMid + step / 2,
                    calBpEnd = segment.first().calBpMid - step / 2,
                    labelStart = Years.bceCeLabel(segment.first().astroMid - step / 2),
                    labelEnd = Years.bceCeLabel(segment.last().astroMid + step / 2),
                    probability = segment.sumOf { it.densityPerYear } * step,
                    crossesBceCe = Years.crossesBceCe(
                        segment.first().astroMid - step / 2,
                        segment.last().astroMid + step / 2,
                    ),
                    truncatedAtCurveBoundary = segment.any { !it.curveInside },
                )
                intervals += interval
                evidence += IntervalEvidence(
                    evidenceId = "${org.chrono.model.IdKinds.EVIDENCE}_${sampleCode}_${levelIndex}_$segmentIndex",
                    intervalId = iid,
                    level = level,
                    integratedCells = segment.size,
                    stepYear = step,
                    unnormalizedMass = segment.sumOf { cell ->
                        raws[cell.index].weight
                    } * step,
                    normalizedProbability = interval.probability,
                    trapezoidRule = "density(midpoint)*step; grid constant step=$step ${Units.YR}",
                    astroStart = interval.astroStart,
                    astroEnd = interval.astroEnd,
                    algorithmVersion = Algorithms.CALIBRATION,
                    parameterFingerprint = config.fingerprint(),
                    curveId = curve.spec.curveId,
                    curveVersion = curve.spec.version,
                    curveContentSha = org.chrono.model.Hashing.sha256Hex(curve.spec.points.toString()).take(16),
                    offsetSummary = offsetSummary(offset),
                )
            }
        }

        return CalibrationResult(
            resultId = "${org.chrono.model.IdKinds.CALIBRATION}_$sampleCode",
            sampleCode = sampleCode,
            curveId = curve.spec.curveId,
            curveVersion = curve.spec.version,
            curveContentSha = org.chrono.model.Hashing.sha256Hex(curve.spec.points.toString()).take(16),
            algorithmVersion = Algorithms.CALIBRATION,
            parameterFingerprint = config.fingerprint(),
            gridCalBpMin = gridLo,
            gridCalBpMax = gridHi,
            stepYear = step,
            offsetSummary = offsetSummary(offset),
            totalMassInside = insideMass / rawMass,
            boundaryTruncated = truncated || boundary,
            cells = cells,
            intervals = intervals,
            evidence = evidence,
            modes = modes,
            multimodal = modes.size >= 2,
        )
    }

    private fun relativeThreshold(raws: List<RawCell>): Double {
        val maxW = raws.maxOf { it.weight }
        return 1e-9 * maxW
    }

    private data class RawCell(val index: Int, val calBp: Double, val weight: Double, val inside: Boolean)

    private fun offsetWeightedLikelihood(
        measured: Double,
        measuredSigma: Double,
        curveC14: Double,
        curveSigma: Double,
        offset: OffsetDist,
    ): Double = when (offset) {
        is OffsetDist.Gaussian -> normalLikelihood(measured, measuredSigma, curveC14, curveSigma, offset)
        is OffsetDist.Mixture -> {
            val totalWeight = offset.components.sumOf { it.weight }
            offset.components.sumOf { component ->
                component.weight / totalWeight *
                    normalLikelihood(measured, measuredSigma, curveC14, curveSigma,
                        OffsetDist.Gaussian(component.mean, component.sigma))
            }
        }
    }

    private fun normalLikelihood(
        measured: Double,
        measuredSigma: Double,
        curveC14: Double,
        curveSigma: Double,
        offset: OffsetDist.Gaussian,
    ): Double {
        // 14C age observed = mu_curve(t) + delta_R; variances combine.
        val mean = curveC14 + offset.mean
        val sigma = hypot(hypot(measuredSigma, curveSigma), offset.sigma)
        val z = (measured - mean) / sigma
        return exp(-0.5 * z * z) / (sigma * sqrt(2.0 * PI))
    }

    private fun buildHpd(
        cells: List<DensityCell>,
        sortedDesc: List<DensityCell>,
        level: Double,
    ): List<List<DensityCell>> {
        val needed = level
        var accumulated = 0.0
        val chosen = HashSet<Int>()
        for (cell in sortedDesc) {
            chosen += cell.index
            accumulated += cell.densityPerYear * config.stepYear
            if (accumulated >= needed - 1e-12) break
        }
        val segments = mutableListOf<List<DensityCell>>()
        var current = mutableListOf<DensityCell>()
        for (cell in cells) {
            if (cell.index in chosen) {
                current += cell
            } else if (current.isNotEmpty()) {
                segments += current
                current = mutableListOf()
            }
        }
        if (current.isNotEmpty()) segments += current
        return segments
    }


    private fun findModes(sampleCode: String, cells: List<DensityCell>): List<ModeSummary> {
        val maxDensity = cells.maxOfOrNull { it.densityPerYear } ?: return emptyList()
        val modes = mutableListOf<ModeSummary>()
        var modeIndex = 0
        for (i in 1 until cells.lastIndex) {
            val prev = cells[i - 1]
            val here = cells[i]
            val next = cells[i + 1]
            if (here.densityPerYear >= prev.densityPerYear &&
                here.densityPerYear > next.densityPerYear &&
                here.densityPerYear >= config.modeProminence * maxDensity
            ) {
                if (modes.isEmpty() ||
                    abs(here.astroMid - modes.last().astroPeak) >= config.modeSeparationYears
                ) {
                    modes += ModeSummary(
                        modeId = "${org.chrono.model.IdKinds.CANDIDATE}_${sampleCode}_m$modeIndex",
                        astroPeak = here.astroMid,
                        labelPeak = Years.bceCeLabel(here.astroMid),
                        localMass = here.densityPerYear * config.stepYear,
                    )
                    modeIndex++
                }
            }
        }
        return modes
    }

    companion object {
        fun offsetSummary(offset: OffsetDist): String = when (offset) {
            is OffsetDist.Gaussian ->
                "deltaR=N(%.1f,%.1f) ${Units.C14_YR_BP}".format(offset.mean, offset.sigma)
            is OffsetDist.Mixture ->
                "deltaR=mix(" + offset.components.joinToString(",") {
                    "w%.2f:N(%.1f,%.1f)".format(it.weight, it.mean, it.sigma)
                } + ") ${Units.C14_YR_BP}"
        }
    }
}
