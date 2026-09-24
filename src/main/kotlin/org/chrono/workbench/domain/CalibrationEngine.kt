package org.chrono.workbench.domain

import org.chrono.workbench.model.Curve
import org.chrono.workbench.model.ReservoirKind
import org.chrono.workbench.model.ReservoirOffset
import kotlin.math.exp
import kotlin.math.sqrt
import kotlinx.serialization.Serializable

const val ALGORITHM_VERSION = "calib-1.0.0"
const val GRID_STEP_YEARS = 1
const val TIE_TOLERANCE = 1e-9
const val SUPPORT_Z_LIMIT = 6.0

@Serializable
data class IntegralEvidence(
    val method: String,
    val level: Double,
    val gridStepYears: Int,
    val fromCellCalBP: Int,
    val toCellCalBP: Int,
    val includedCells: Int,
    val densityThreshold: Double,
    val summedMass: Double,
    val normalizer: Double,
    val formula: String
)

@Serializable
data class CalInterval(
    val intervalId: String,
    val rank: Int,
    val oldCalBP: Double,
    val youngCalBP: Double,
    val oldEdgeLabel: String,
    val youngEdgeLabel: String,
    val probabilityMass: Double,
    val massWithin68: Double,
    val peakCalBP: Int,
    val peakDensity: Double,
    val spansBCECE: Boolean,
    val tiedWith: List<String>,
    val evidence: IntegralEvidence
)

@Serializable
data class ModePeak(val calBP: Int, val density: Double, val label: String)

@Serializable
data class ScenarioDensity(val scenarioId: String, val label: String, val weight: Double, val density: List<Double>)

@Serializable
data class CalibrationResult(
    val derivedId: String,
    val status: String,
    val diagnostics: List<String>,
    val multimodal: Boolean,
    val modeCount: Int,
    val gridCalBP: List<Int>,
    val density: List<Double>,
    val rawNormalizer: Double,
    val intervals95: List<CalInterval>,
    val modes: List<ModePeak>,
    val scenarios: List<ScenarioDensity>,
    val algorithmVersion: String,
    val parameterFingerprint: String,
    val directSources: List<String>,
    val truncatedAtOldBoundary: Boolean,
    val truncatedAtYoungBoundary: Boolean,
    val normalized: Boolean,
    val legalCandidates: List<String>
)

data class CalibrationInput(
    val measurementId: String,
    val labCode: String,
    val ageBP: Double,
    val sigmaBP: Double,
    val curve: Curve,
    val reservoir: ReservoirOffset?
)

object CalibrationEngine {

    private const val SQRT_2PI: Double = 2.5066282746310002

    private fun normal(x: Double, mu: Double, sigma: Double): Double {
        val z = (x - mu) / sigma
        return exp(-0.5 * z * z) / (sigma * SQRT_2PI)
    }

    fun interpolate(curve: Curve, calBP: Int): Pair<Double, Double> =
        curve.points.first { it.calBP == calBP }.let { it.c14age to it.sigma.toDouble() }

    data class Scenario(val id: String, val label: String, val weight: Double, val mean: Double, val sd: Double)

    private fun scenarios(reservoir: ReservoirOffset?): List<Scenario> {
        if (reservoir == null) return listOf(Scenario("none", "no reservoir correction", 1.0, 0.0, 0.0))
        return when (reservoir.kind) {
            ReservoirKind.NORMAL ->
                listOf(Scenario(reservoir.id, reservoir.label, 1.0, reservoir.meanYears, reservoir.sdYears))
            ReservoirKind.MIXTURE ->
                reservoir.components.mapIndexed { i, c ->
                    Scenario("${reservoir.id}#$i", "${reservoir.label} component ${i + 1}", c.weight, c.meanYears, c.sdYears)
                }
        }
    }

    private data class Grid(val calBP: List<Int>, val mu: List<Double>, val sigmaCurve: List<Double>)

    private fun grid(curve: Curve): Grid {
        val pts = curve.points
        return Grid(pts.map { it.calBP }, pts.map { it.c14age }, pts.map { it.sigma.toDouble() })
    }

    fun parameterFingerprint(input: CalibrationInput): String {
        val r = input.reservoir
        val reservoirCanonical = if (r == null) "none" else
            "${r.id}:${r.kind}:${r.meanYears}:${r.sdYears}:" +
                r.components.joinToString(",") { "${it.weight}/${it.meanYears}/${it.sdYears}" }
        return listOf(
            ALGORITHM_VERSION,
            "curve=${input.curve.id}@${input.curve.version}",
            "meas=${input.measurementId}:${input.ageBP}+-${input.sigmaBP}",
            "resv=$reservoirCanonical",
            "grid=$GRID_STEP_YEARS",
            "hpd=68,95"
        ).joinToString("|")
    }

    fun calibrate(input: CalibrationInput, derivedId: String): CalibrationResult {
        val g = grid(input.curve)
        val n = g.calBP.size
        val scs = scenarios(input.reservoir)
        val raw = DoubleArray(n)
        val componentDensity = HashMap<String, DoubleArray>()
        var minAbsZ = Double.MAX_VALUE

        for (s in scs) {
            val arr = DoubleArray(n)
            for (i in 0 until n) {
                val sigmaTotal = sqrt(input.sigmaBP * input.sigmaBP + s.sd * s.sd + g.sigmaCurve[i] * g.sigmaCurve[i])
                val z = (input.ageBP - s.mean - g.mu[i]) / sigmaTotal
                val v = s.weight * normal(input.ageBP - s.mean, g.mu[i], sigmaTotal)
                arr[i] = v
                raw[i] += v
                if (kotlin.math.abs(z) < minAbsZ) minAbsZ = kotlin.math.abs(z)
            }
            componentDensity[s.id] = arr
        }

        val diagnostics = mutableListOf<String>()
        if (minAbsZ > SUPPORT_Z_LIMIT) {
            diagnostics.add("No curve point within ${SUPPORT_Z_LIMIT.toInt()} combined standard deviations; date lies outside the curve domain.")
            return CalibrationResult(
                derivedId = derivedId,
                status = "OUTSIDE_CURVE_UNKNOWN",
                diagnostics = diagnostics,
                multimodal = false,
                modeCount = 0,
                gridCalBP = g.calBP,
                density = List(n) { 0.0 },
                rawNormalizer = raw.sum(),
                intervals95 = emptyList(),
                modes = emptyList(),
                scenarios = scs.map { ScenarioDensity(it.id, it.label, it.weight, List(n) { 0.0 }) },
                algorithmVersion = ALGORITHM_VERSION,
                parameterFingerprint = parameterFingerprint(input),
                directSources = directSourceIds(input),
                truncatedAtOldBoundary = false,
                truncatedAtYoungBoundary = false,
                normalized = false,
                legalCandidates = emptyList()
            )
        }

        val normalizer = raw.sum()
        val density = DoubleArray(n) { raw[it] / normalizer }

        val truncOld = density.first() > 1e-5
        val truncYoung = density.last() > 1e-5
        if (truncOld) diagnostics.add("Posterior is truncated at the old boundary calBP ${g.calBP.first()}; no extrapolation is evaluated.")
        if (truncYoung) diagnostics.add("Posterior is truncated at the young boundary calBP ${g.calBP.last()}; no extrapolation is evaluated.")

        val selected95 = hpdSelection(density, 0.95)
        val selected68 = hpdSelection(density, 0.68)
        val thr95 = selected95.threshold
        val thr68 = selected68.threshold

        val segments = contiguous(selected95.mask)
        val peaks = findPeaks(g.calBP, density)
        val intervals = segments.mapIndexed { idx, seg ->
            val (a, b) = seg
            var mass = 0.0
            var mass68 = 0.0
            var peakIdx = a
            for (i in a..b) {
                mass += density[i]
                if (selected68.mask[i]) mass68 += density[i]
                if (density[i] > density[peakIdx]) peakIdx = i
            }
            val oldEdge = edgeFraction(g.calBP, density, a, b, thr95, oldSide = true)
            val youngEdge = edgeFraction(g.calBP, density, a, b, thr95, oldSide = false)
            val oldLbl = CalendarScales.label(kotlin.math.ceil(oldEdge).toInt()).text
            val youngLbl = CalendarScales.label(kotlin.math.floor(youngEdge).toInt()).text
            CalInterval(
                intervalId = "${derivedId}:iv${idx + 1}",
                rank = idx + 1,
                oldCalBP = oldEdge,
                youngCalBP = youngEdge,
                oldEdgeLabel = oldLbl,
                youngEdgeLabel = youngLbl,
                probabilityMass = mass,
                massWithin68 = mass68,
                peakCalBP = g.calBP[peakIdx],
                peakDensity = density[peakIdx],
                spansBCECE = CalendarScales.spansBCECE(kotlin.math.ceil(oldEdge).toInt(), kotlin.math.floor(youngEdge).toInt()),
                tiedWith = emptyList(),
                evidence = IntegralEvidence(
                    method = "Riemann sum over fixed 1-year calBP grid after linear curve interpolation, normalized by the same grid",
                    level = 0.95,
                    gridStepYears = GRID_STEP_YEARS,
                    fromCellCalBP = g.calBP[a],
                    toCellCalBP = g.calBP[b],
                    includedCells = b - a + 1,
                    densityThreshold = thr95,
                    summedMass = mass,
                    normalizer = normalizer,
                    formula = "sum_{x in cells} raw(x)/Z, Z=$normalizer; threshold=$thr95; 68% threshold=$thr68"
                )
            )
        }

        val tieGroups = intervals.groupBy { roundTie(it.probabilityMass) }.filter { it.value.size > 1 }
        val tieMap = HashMap<String, MutableList<String>>()
        for (group in tieGroups.values) {
            val ids = group.map { it.intervalId }
            for (iv in group) tieMap[iv.intervalId] = ids.filter { it != iv.intervalId }.toMutableList()
        }
        val finalized = intervals.map { it.copy(tiedWith = tieMap[it.intervalId] ?: emptyList()) }

        val status = when {
            truncOld || truncYoung -> "EDGE_TRUNCATED"
            else -> "OK"
        }
        val scenarioDensities = scs.map { s ->
            val arr = componentDensity.getValue(s.id)
            val compNorm = arr.sum()
            ScenarioDensity(s.id, s.label, s.weight, List(n) { if (compNorm > 0) arr[it] / compNorm else 0.0 })
        }

        return CalibrationResult(
            derivedId = derivedId,
            status = status,
            diagnostics = diagnostics,
            multimodal = peaks.size >= 2,
            modeCount = peaks.size,
            gridCalBP = g.calBP,
            density = density.toList(),
            rawNormalizer = normalizer,
            intervals95 = finalized,
            modes = peaks.map { ModePeak(g.calBP[it], density[it], CalendarScales.label(g.calBP[it]).text) },
            scenarios = scenarioDensities,
            algorithmVersion = ALGORITHM_VERSION,
            parameterFingerprint = parameterFingerprint(input),
            directSources = directSourceIds(input),
            truncatedAtOldBoundary = truncOld,
            truncatedAtYoungBoundary = truncYoung,
            normalized = true,
            legalCandidates = finalized.filter { it.tiedWith.isNotEmpty() || finalized.size > 1 }.map { it.intervalId }
        )
    }

    private fun roundTie(v: Double): Long = Math.round(v / TIE_TOLERANCE)

    private fun directSourceIds(input: CalibrationInput): List<String> =
        listOfNotNull(input.measurementId, input.curve.id, input.reservoir?.id)

    private data class HPD(val mask: BooleanArray, val threshold: Double)

    private fun hpdSelection(density: DoubleArray, level: Double): HPD {
        val order = density.indices.sortedWith(compareByDescending<Int> { density[it] })
        val mask = BooleanArray(density.size)
        var acc = 0.0
        var threshold = 0.0
        for (i in order) {
            mask[i] = true
            acc += density[i]
            threshold = density[i]
            if (acc >= level) break
        }
        return HPD(mask, threshold)
    }

    private fun contiguous(mask: BooleanArray): List<Pair<Int, Int>> {
        val out = mutableListOf<Pair<Int, Int>>()
        var i = 0
        while (i < mask.size) {
            if (mask[i]) {
                var j = i
                while (j + 1 < mask.size && mask[j + 1]) j++
                out.add(i to j)
                i = j + 1
            } else i++
        }
        return out
    }

    private fun findPeaks(xs: List<Int>, density: DoubleArray): List<Int> {
        val floor = 0.02 * (density.maxOrNull() ?: 0.0)
        val peaks = mutableListOf<Int>()
        for (i in density.indices) {
            val left = if (i > 0) density[i - 1] else -1.0
            val right = if (i + 1 < density.size) density[i + 1] else -1.0
            if (density[i] >= left && density[i] > right && density[i] >= floor) peaks.add(i)
        }
        return peaks
    }

    private fun edgeFraction(xs: List<Int>, density: DoubleArray, a: Int, b: Int, threshold: Double, oldSide: Boolean): Double {
        val idx = if (oldSide) a else b
        val neighbor = if (oldSide) idx - 1 else idx + 1
        if (neighbor < 0 || neighbor >= density.size) return xs[idx].toDouble()
        val dIn = density[idx]
        val dOut = density[neighbor]
        if (dIn == dOut) return xs[idx].toDouble()
        val frac = (dIn - threshold) / (dIn - dOut)
        val clamped = frac.coerceIn(0.0, 1.0)
        return if (oldSide) xs[idx] + clamped * 1.0 else xs[idx] - clamped
    }

    fun integrate(result: CalibrationResult, fromCalBP: Int, toCalBP: Int): IntegralEvidence {
        var sum = 0.0
        var count = 0
        var first = 0
        var last = 0
        for ((i, x) in result.gridCalBP.withIndex()) {
            if (x <= fromCalBP.coerceAtLeast(result.gridCalBP.first()) &&
                x >= toCalBP.coerceAtMost(result.gridCalBP.last())) {
                sum += result.density[i]
                if (count == 0) first = i
                last = i
                count++
            }
        }
        return IntegralEvidence(
            method = "Riemann sum over the normalized fixed 1-year grid",
            level = -1.0,
            gridStepYears = GRID_STEP_YEARS,
            fromCellCalBP = if (count > 0) result.gridCalBP[first] else -1,
            toCellCalBP = if (count > 0) result.gridCalBP[last] else -1,
            includedCells = count,
            densityThreshold = -1.0,
            summedMass = sum,
            normalizer = result.rawNormalizer,
            formula = "sum_{x in requested cells} density(x)"
        )
    }
}
