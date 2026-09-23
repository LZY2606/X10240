package org.chronocarbon

import kotlinx.serialization.Serializable
import kotlin.math.exp
import kotlin.math.round
import kotlin.math.sqrt
import kotlinx.serialization.encodeToString

const val ALGORITHM_VERSION = "cal-gaussian-grid/v1"
const val GRID_STEP_BP = 2

/** Normalized parameter fingerprint: same inputs + fixed curve version => identical result. */
@Serializable
data class CalibrationRequest(
    val measurementId: String,
    val curveVersion: String,
    val offset: OffsetDist,
    val eraSystem: EraSystem = EraSystem.BCE_CE,
    val gridStepBp: Int = GRID_STEP_BP,
    val level68: Double = 0.6827,
    val level95: Double = 0.9545
) {
    fun fingerprint(): String {
        val offsetJson = JsonCodec.json.encodeToString(OffsetDist.serializer(), offset)
        val basis = listOf(
            ALGORITHM_VERSION, measurementId, curveVersion,
            JsonCodec.canonicalJson(JsonCodec.json.parseToJsonElement(offsetJson)),
            eraSystem.name, gridStepBp.toString(), level68.toString(), level95.toString()
        ).joinToString("|")
        return Hashing.sha256Hex(basis)
    }
}

/** One calendar-age grid cell with its normalized probability mass and boundary flags. */
@Serializable
data class DensityCell(
    val index: Int,
    val calBp: Int,
    val eraLabel: String,
    val density: Double,
    val mass: Double,
    val atLowerCurveBoundary: Boolean,
    val atUpperCurveBoundary: Boolean
)

/** Integrable evidence row backing an interval: sum of these masses equals interval probability. */
@Serializable
data class EvidenceRow(val cellIndex: Int, val calBp: Int, val eraLabel: String, val mass: Double)

/**
 * A credible calendar interval. Parallel intervals (co-equal multi-modal lobes) are retained as
 * separate first-class rows instead of being averaged into one year.
 */
@Serializable
data class CalendarInterval(
    val id: String,
    val level: String,
    val order: Int,
    val fromBp: Int,
    val toBp: Int,
    val fromEra: String,
    val toEra: String,
    val probability: Double,
    val crossesBceCe: Boolean,
    val touchesCurveBoundary: Boolean,
    val evidence: List<EvidenceRow>
)

@Serializable
data class ModeSummary(
    val order: Int,
    val peakBp: Int,
    val peakEra: String,
    val fromBp: Int,
    val toBp: Int,
    val probability: Double
)

@Serializable
data class CalibrationResult(
    val requestFingerprint: String,
    val algorithmVersion: String,
    val measurementId: String,
    val curveVersion: String,
    val curveMinBp: Int,
    val curveMaxBp: Int,
    val gridStepBp: Int,
    val totalMass: Double,
    val lowerBoundaryMass: Double,
    val upperBoundaryMass: Double,
    val boundaryTruncated: Boolean,
    val cells: List<DensityCell>,
    val modes: List<ModeSummary>,
    val intervals68: List<CalendarInterval>,
    val intervals95: List<CalendarInterval>,
    /** Diagnostics only; never the reported answer. Multi-modal evidence is not collapsed to it. */
    val weightedMeanBpDiagnostic: Double
) {
    fun allIntervals(): List<CalendarInterval> = intervals68 + intervals95
    fun intervalById(id: String): CalendarInterval? = allIntervals().firstOrNull { it.id == id }
}

object Calibrator {

    fun calibrate(
        measurement: Measurement,
        curve: CalibrationCurve,
        request: CalibrationRequest
    ): CalibrationResult {
        require(measurement.valid) { "measurement ${measurement.id} is not valid for calibration" }
        val age = measurement.ageBp!!
        val sigma = measurement.sigma!!
        val step = request.gridStepBp
        val bpValues = (curve.minBp..curve.maxBp step step).toList()

        val sigmaEff = request.offset.effectiveSigma(sigma)
        val target = age - request.offset.shift()
        val rawWeights = DoubleArray(bpValues.size) { i ->
            val bp = bpValues[i].toDouble()
            val mu = curve.muAt(bp)
            val denom = 2.0 * sigmaEff * sigmaEff
            exp(-((target - mu) * (target - mu)) / denom)
        }
        val total = rawWeights.sum()
        val masses = DoubleArray(bpValues.size) { rawWeights[it] / total }

        val cells = bpValues.mapIndexed { i, bp ->
            val atLow = bp <= curve.minBp
            val atHigh = bp >= curve.maxBp
            DensityCell(
                index = i,
                calBp = bp,
                eraLabel = EraFormat.label(bp, request.eraSystem),
                density = masses[i] / step,
                mass = masses[i],
                atLowerCurveBoundary = atLow,
                atUpperCurveBoundary = atHigh
            )
        }

        val edgeWidth = step * 2
        val lowerMass = masses.filterIndexed { i, _ -> bpValues[i] <= curve.minBp + edgeWidth }.sum()
        val upperMass = masses.filterIndexed { i, _ -> bpValues[i] >= curve.maxBp - edgeWidth }.sum()
        // Boundary interpolation case: >5% of mass piles onto the clipped table edge.
        val truncated = lowerMass > 0.05 || upperMass > 0.05

        val modes = findModes(bpValues, masses, request)
        val intervals95 = hpdIntervals(cells, masses, bpValues, request.level95, "95_4", request)
        val intervals68 = hpdIntervals(cells, masses, bpValues, request.level68, "68_3", request)
        val mean = bpValues.indices.sumOf { bpValues[it] * masses[it] }

        return CalibrationResult(
            requestFingerprint = request.fingerprint(),
            algorithmVersion = ALGORITHM_VERSION,
            measurementId = measurement.id,
            curveVersion = curve.version,
            curveMinBp = curve.minBp,
            curveMaxBp = curve.maxBp,
            gridStepBp = step,
            totalMass = total.let { masses.sum() },
            lowerBoundaryMass = lowerMass,
            upperBoundaryMass = upperMass,
            boundaryTruncated = truncated,
            cells = cells,
            modes = modes,
            intervals68 = intervals68,
            intervals95 = intervals95,
            weightedMeanBpDiagnostic = mean
        )
    }

    private fun findModes(bp: List<Int>, masses: DoubleArray, request: CalibrationRequest): List<ModeSummary> {
        val peak = masses.max()
        val threshold = peak * 0.05
        val groups = mutableListOf<IntRange>()
        var start = -1
        for (i in masses.indices) {
            val above = masses[i] >= threshold
            if (above && start < 0) start = i
            if ((!above || i == masses.lastIndex) && start >= 0) {
                val end = if (above) i else i - 1
                groups += start..end
                start = -1
            }
        }
        return groups.mapIndexed { order, range ->
            var peakIdx = range.first
            for (i in range) if (masses[i] > masses[peakIdx]) peakIdx = i
            ModeSummary(
                order = order,
                peakBp = bp[peakIdx],
                peakEra = EraFormat.label(bp[peakIdx], request.eraSystem),
                fromBp = bp[range.first],
                toBp = bp[range.last],
                probability = round4(range.sumOf { masses[it] })
            )
        }
    }

    /** Highest-density intervals: keep the most probable cells until [level] mass is reached. */
    private fun hpdIntervals(
        cells: List<DensityCell>,
        masses: DoubleArray,
        bp: List<Int>,
        level: Double,
        levelTag: String,
        request: CalibrationRequest
    ): List<CalendarInterval> {
        val orderByDensity = masses.indices.sortedWith(compareByDescending<Int> { masses[it] })
        val chosen = BooleanArray(masses.size)
        var accumulated = 0.0
        for (i in orderByDensity) {
            chosen[i] = true
            accumulated += masses[i]
            if (accumulated >= level) break
        }
        val groups = mutableListOf<IntRange>()
        var start = -1
        for (i in chosen.indices) {
            if (chosen[i] && start < 0) start = i
            if ((!chosen[i] || i == chosen.lastIndex) && start >= 0) {
                groups += start..(if (chosen[i]) i else i - 1)
                start = -1
            }
        }
        return groups.mapIndexed { order, range ->
            val from = bp[range.first]
            val to = bp[range.last]
            val prob = round4(range.sumOf { masses[it] })
            CalendarInterval(
                id = stableIntervalId(request, levelTag, order, from, to),
                level = levelTag,
                order = order,
                fromBp = from,
                toBp = to,
                fromEra = EraFormat.label(from, request.eraSystem),
                toEra = EraFormat.label(to, request.eraSystem),
                probability = prob,
                crossesBceCe = from >= 1950 && to <= 1950 || (from > 1950 && to < 1950) ||
                    (from >= 1950) != (to >= 1950) || straddles1950(from, to),
                touchesCurveBoundary = cells[range.first].atLowerCurveBoundary ||
                    cells[range.last].atUpperCurveBoundary,
                evidence = range.map { i ->
                    EvidenceRow(i, bp[i], EraFormat.label(bp[i], request.eraSystem), round8(masses[i]))
                }
            )
        }
    }

    private fun straddles1950(fromBp: Int, toBp: Int): Boolean = fromBp >= 1950 && toBp <= 1950

    private fun stableIntervalId(request: CalibrationRequest, level: String, order: Int, from: Int, to: Int): String {
        val basis = request.fingerprint() + "|$level|$order|$from|$to"
        return "iv-" + Hashing.sha256Hex(basis).take(10)
    }

    private fun round4(v: Double) = round(v * 10000.0) / 10000.0
    private fun round8(v: Double) = round(v * 1e8) / 1e8
}

/** Calendar era formatting. cal BP 1950 == 1 BCE/CE boundary year; older BP is BCE, younger is CE. */
object EraFormat {
    fun label(calBp: Int, system: EraSystem): String = when (system) {
        EraSystem.CAL_BP -> "$calBp cal BP"
        EraSystem.BCE_CE -> {
            val astronomical = 1950 - calBp
            when {
                astronomical <= 0 -> "${1 - astronomical} BCE"
                else -> "$astronomical CE"
            }
        }
    }

    fun crossesBceCe(fromBp: Int, toBp: Int): Boolean = (fromBp >= 1950) != (toBp >= 1950)
}
