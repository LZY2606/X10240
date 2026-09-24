package org.chronology.workbench

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

const val ALGORITHM_CALIBRATION = "radiocarbon-grid-calibration-v1"
const val ALGORITHM_SEQUENCE = "sequence-forward-backward-v1"

@Serializable
data class InterpolatedCurve(val points: List<CurvePoint>) {
    fun at(calendarBP: Double): CurvePoint? {
        if (points.isEmpty() || calendarBP < points.first().calendarBP || calendarBP > points.last().calendarBP) return null
        val index = points.binarySearchBy(calendarBP) { it.calendarBP }
        if (index >= 0) return points[index]
        val insertion = -index - 1
        val before = points[insertion - 1]
        val after = points[insertion]
        val span = after.calendarBP - before.calendarBP
        if (span <= 0.0) return before
        val t = (calendarBP - before.calendarBP) / span
        return CurvePoint(
            calendarBP = calendarBP,
            radiocarbonBP = before.radiocarbonBP + t * (after.radiocarbonBP - before.radiocarbonBP),
            sigmaRadiocarbonBP = before.sigmaRadiocarbonBP + t * (after.sigmaRadiocarbonBP - before.sigmaRadiocarbonBP),
            sourceId = "interpolated:${before.sourceId};${after.sourceId}",
        )
    }

    fun support(): Pair<Double, Double>? = if (points.isEmpty()) null else points.first().calendarBP to points.last().calendarBP
}

@Serializable
data class ReservoirScenario(val offsetBP: Double, val sigmaBP: Double, val weight: Double, val hypothesisId: String)

@Serializable
data class DensityPoint(val calendarBP: Double, val astronomicalYear: Int, val eraLabel: String, val density: Double, val curve14CBP: Double)

@Serializable
data class CalendarInterval(
    val intervalId: String,
    val startBP: Double,
    val endBP: Double,
    val startLabel: String,
    val endLabel: String,
    val probability: Double,
    val crossesBCE_CE: Boolean,
    val integralEvidence: IntegralEvidence,
    val rank: Int,
)

@Serializable
data class IntegralEvidence(
    val method: String,
    val weightSum: Double,
    val totalWeightSum: Double,
    val gridStepYears: Double,
    val firstPointBP: Double,
    val lastPointBP: Double,
    val pointCount: Int,
    val boundaryStates: List<BoundaryState>,
)

@Serializable
data class BoundaryState(val boundaryBP: Double, val inside: Boolean, val nearestCurvePointBP: Double, val interpolationFraction: Double)

@Serializable
data class DistributionMode(
    val modeId: String,
    val centerBP: Double,
    val centerLabel: String,
    val height: Double,
    val startBP: Double,
    val endBP: Double,
)

@Serializable
data class CalibrationComputation(
    val subjectId: String,
    val requestEventId: String,
    val resultEventId: String,
    val versionId: String,
    val density: List<DensityPoint>,
    val modes: List<DistributionMode>,
    val credibleIntervals: List<CalendarInterval>,
    val expectedBP: Double? = null,
    val status: String,
    val diagnostics: List<String>,
    val directSources: List<String>,
    val sourceRanges: List<SourceRange>,
    val parameterFingerprint: String,
)

object Era {
    fun astronomicalYear(calendarBP: Double): Int {
        val ceYear = 1950 - calendarBP
        val roundedDown = if (ceYear >= 0) kotlin.math.floor(ceYear).toInt() else kotlin.math.ceil(ceYear).toInt()
        return if (roundedDown <= 0) roundedDown - 1 else roundedDown
    }

    fun label(calendarBP: Double): String {
        val year = astronomicalYear(calendarBP)
        val display = abs(year)
        return if (year < 0) "$display BCE" else if (year == 0) "1 BCE" else "$display CE"
    }

    fun crossesBCE_CE(startBP: Double, endBP: Double): Boolean = startBP > 1950.0 && endBP < 1950.0
}

class CalibrationEngine(private val gridStep: Double = 1.0) {
    fun calibrate(
        subjectId: String,
        requestEventId: String,
        measurementAgeBP: Double,
        measurementSigmaBP: Double,
        curve: InterpolatedCurve,
        curveVersionId: String,
        reservoir: List<ReservoirScenario>,
        measurementVersionId: String,
        reservoirVersionId: String,
        exclusions: List<Pair<Double, Double>> = emptyList(),
    ): CalibrationComputation {
        val support = curve.support()
        val diagnostics = mutableListOf<String>()
        if (support == null) {
            return computationShell(subjectId, requestEventId, measurementVersionId, reservoirVersionId, curveVersionId, emptyList(), "UNKNOWN", listOf("校准曲线没有可用点"))
        }
        val (minBP, maxBP) = support
        if (maxBP < minBP) diagnostics.add("曲线坐标必须按日历 BP 升序")
        val years = generateSequence(minBP) { (it + gridStep).takeIf { next -> next <= maxBP + 1e-9 } }.toList()
        val raw = years.map { year ->
            val point = curve.at(year) ?: error("interpolation support mismatch")
            val mixtureLikelihood = reservoir.sumOf { scenario ->
                val totalSigma = hypot(measurementSigmaBP, hypot(point.sigmaRadiocarbonBP, scenario.sigmaBP))
                val delta = measurementAgeBP - scenario.offsetBP - point.radiocarbonBP
                scenario.weight * exp(-0.5 * (delta / totalSigma) * (delta / totalSigma)) / (totalSigma * sqrt(2.0 * Math.PI))
            }
            val excluded = exclusions.any { (start, end) -> year >= min(start, end) - 1e-9 && year <= max(start, end) + 1e-9 }
            DensityPoint(year, Era.astronomicalYear(year), Era.label(year), if (excluded) 0.0 else mixtureLikelihood, point.radiocarbonBP)
        }
        val trapezoidWeights = raw.indices.map { index ->
            when {
                raw.size == 1 -> gridStep
                index == 0 || index == raw.lastIndex -> gridStep / 2.0
                else -> gridStep
            }
        }
        val totalWeight = raw.zip(trapezoidWeights).sumOf { it.first.density * it.second }
        if (totalWeight <= 0.0 || totalWeight.isNaN()) {
            return computationShell(subjectId, requestEventId, measurementVersionId, reservoirVersionId, curveVersionId, raw.map { it.copy(density = 0.0) }, "REJECTED_EMPTY_SUPPORT", listOf("在曲线支撑和排除区间内没有正概率"))
        }
        val normalized = raw.map { it.copy(density = it.density / totalWeight) }
        val modes = detectModes(subjectId, normalized)
        val intervals = credibleIntervals(subjectId, normalized, trapezoidWeights.map { it / totalWeight }, totalWeight, listOf(minBP, maxBP), 0.95)
        val sourceRanges = listOf(
            SourceRange(measurementVersionId, measurementVersionId, 0.0, 0.0),
            SourceRange(curveVersionId, curveVersionId, minBP, maxBP),
            SourceRange(reservoirVersionId, reservoirVersionId, 0.0, 0.0),
        )
        val directSources = listOf(requestEventId, measurementVersionId, curveVersionId, reservoirVersionId)
        val fingerprint = StableIds.fingerprint(
            ALGORITHM_CALIBRATION,
            mapOf(
                "measurement" to measurementVersionId,
                "curve" to curveVersionId,
                "reservoir" to reservoirVersionId,
                "gridStep" to gridStep.toString(),
                "exclusions" to exclusions.joinToString(";") { "${it.first}-${it.second}" },
                "curvePoints" to curve.points.joinToString("|") { "${it.calendarBP}:${it.radiocarbonBP}:${it.sigmaRadiocarbonBP}" },
            ),
        )
        val resultId = StableIds.stable("cal", listOf(subjectId, requestEventId, fingerprint))
        return CalibrationComputation(subjectId, requestEventId, resultId, resultId, normalized, modes, intervals, null, if (modes.size > 1) "MULTIMODAL" else "CURRENT", diagnostics, directSources, sourceRanges, fingerprint)
    }

    private fun computationShell(
        subjectId: String,
        requestEventId: String,
        measurementVersionId: String,
        reservoirVersionId: String,
        curveVersionId: String,
        density: List<DensityPoint>,
        status: String,
        diagnostics: List<String>,
    ): CalibrationComputation {
        val fingerprint = StableIds.fingerprint(ALGORITHM_CALIBRATION, mapOf("request" to requestEventId, "status" to status))
        val id = StableIds.stable("cal", listOf(subjectId, requestEventId, fingerprint))
        return CalibrationComputation(
            subjectId, requestEventId, id, id, density, emptyList(), emptyList(), null, status, diagnostics,
            listOf(requestEventId, measurementVersionId, curveVersionId, reservoirVersionId),
            listOf(SourceRange(curveVersionId, curveVersionId, 0.0, 0.0)),
            fingerprint,
        )
    }

    private fun detectModes(subjectId: String, density: List<DensityPoint>): List<DistributionMode> {
        val modes = mutableListOf<DistributionMode>()
        var start: Int? = null
        var peakIndex = 0
        for (index in density.indices) {
            val positive = density[index].density > 0.0
            if (positive && start == null) {
                start = index
                peakIndex = index
            }
            if (positive && density[index].density > density[peakIndex].density) peakIndex = index
            val endNow = positive && (index == density.lastIndex || density[index + 1].density == 0.0)
            if (start != null && endNow) {
                val end = index
                val id = StableIds.stable("mode", listOf(subjectId, density[start].calendarBP.toString(), density[end].calendarBP.toString(), density[peakIndex].calendarBP.toString()))
                modes.add(DistributionMode(id, density[peakIndex].calendarBP, Era.label(density[peakIndex].calendarBP), density[peakIndex].density, density[start].calendarBP, density[end].calendarBP))
                start = null
            }
        }
        return modes.sortedByDescending { it.height }
    }

    private fun credibleIntervals(
        subjectId: String,
        density: List<DensityPoint>,
        weights: List<Double>,
        totalWeight: Double,
        boundaries: List<Double>,
        mass: Double,
    ): List<CalendarInterval> {
        val threshold = findThreshold(density, weights, mass)
        val intervals = mutableListOf<CalendarInterval>()
        var start: Int? = null
        for (index in density.indices) {
            val included = density[index].density >= threshold - 1e-14
            if (included && start == null) start = index
            if (start != null && (!included || index == density.lastIndex)) {
                val end = if (included) index else index - 1
                val weightSum = (start..end).sumOf { weights[it] * density[it].density }
                val startBP = density[start].calendarBP
                val endBP = density[end].calendarBP
                val boundaryStates = boundaries.map { boundary ->
                    val nearest = density.minBy { abs(it.calendarBP - boundary) }
                    BoundaryState(boundary, boundary in startBP..endBP, nearest.calendarBP, if (boundary in startBP..endBP) (boundary - startBP) / max(gridStep, endBP - startBP) else 0.0)
                }
                val id = StableIds.stable("interval", listOf(subjectId, startBP.toString(), endBP.toString(), mass.toString(), totalWeight.toString()))
                intervals.add(CalendarInterval(id, startBP, endBP, Era.label(startBP), Era.label(endBP), weightSum, Era.crossesBCE_CE(startBP, endBP), IntegralEvidence("trapezoidal-grid-normalized-v1", weightSum, 1.0, gridStep, startBP, endBP, end - start + 1, boundaryStates), intervals.size + 1))
                start = null
            }
        }
        return intervals.sortedByDescending { it.probability }
    }

    private fun findThreshold(density: List<DensityPoint>, weights: List<Double>, mass: Double): Double {
        var low = 0.0
        var high = density.maxOf { it.density }
        repeat(60) {
            val mid = (low + high) / 2.0
            val includedMass = density.zip(weights).sumOf { (point, weight) -> if (point.density >= mid) point.density * weight else 0.0 }
            if (includedMass >= mass) low = mid else high = mid
        }
        return low
    }
}
