package org.chronobench.domain

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

const val ENGINE_ALGORITHM_VERSION = "calibration-grid-hpd-1.0.0"
const val GRID_START = 1700.0
const val GRID_END = 2300.0
const val GRID_STEP = 0.5
const val ENGINE_SEED = 42L

@Serializable
data class MeasurementRef(val measurementId: String, val c14AgeBP: Double, val sigmaBP: Double)

@Serializable
data class ReservoirHypothesis(
    val id: String,
    val label: String,
    val deltaRBP: Double,
    val deltaRSigmaBP: Double
) {
    init { require(deltaRSigmaBP >= 0.0) { "reservoir sigma must be non-negative" } }
}

@Serializable
data class CalibrationRequest(
    val measurement: MeasurementRef,
    val curveId: String,
    val curveVersion: String,
    val curveFingerprint: String,
    val reservoir: ReservoirHypothesis,
    val gridStart: Double = GRID_START,
    val gridEnd: Double = GRID_END,
    val gridStep: Double = GRID_STEP
)

@Serializable
data class GridPoint(
    val index: Int,
    val calBP: Double,
    val density: Double,
    val curveC14BP: Double,
    val totalSigma: Double,
    val withinCurve: Boolean
)

@Serializable
data class IntegrationEvidence(
    val startCalBP: Double,
    val endCalBP: Double,
    val probability: Double,
    val rule: String,
    val trapezoidTerms: Int,
    val normalizedUnder: Double
)

@Serializable
data class CalendarInterval(
    val stableId: String,
    val startCalBP: Double,
    val endCalBP: Double,
    val probability: Double,
    val credibleLevel: Double,
    val labelBP: String,
    val labelBceCe: String,
    val crossesBceCe: Boolean,
    val evidence: IntegrationEvidence,
    val rank: Int,
    val tieGroupId: String?
)

@Serializable
data class ModePeak(
    val stableId: String,
    val centerCalBP: Double,
    val peakDensity: Double,
    val modeMass: Double,
    val rank: Int
)

@Serializable
data class Diagnostic(
    val code: String,
    val message: String,
    val startCalBP: Double? = null,
    val endCalBP: Double? = null
)

@Serializable
data class ParameterFingerprint(
    val algorithmVersion: String,
    val gridStart: Double,
    val gridEnd: Double,
    val gridStep: Double,
    val curveFingerprint: String,
    val measurementFingerprint: String,
    val reservoirFingerprint: String,
    val engineSeed: Long,
    val fingerprint: String
)

@Serializable
data class CalibrationResult(
    val request: CalibrationRequest,
    val points: List<GridPoint>,
    val normalizationConstant: Double,
    val intervals68: List<CalendarInterval>,
    val intervals95: List<CalendarInterval>,
    val modes: List<ModePeak>,
    val diagnostics: List<Diagnostic>,
    val parameterFingerprint: ParameterFingerprint
) {
    fun intervalById(id: String): CalendarInterval? =
        (intervals68 + intervals95).firstOrNull { it.stableId == id }
}

object CalibrationEngine {
    /**
     * Calibrate one measurement under one reservoir hypothesis:
     * p(calBP) proportional to Normal(c14Age - dR; curve(calBP),
     * sigmaCurve^2 + sigmaLab^2 + sigmaDR^2), normalized by the trapezoid rule.
     *
     * The full multi-modal posterior is retained (never collapsed to a mean year).
     * Grid cells outside the curve version keep density 0 with an explicit diagnostic;
     * no extrapolation is performed for plotting.
     */
    fun calibrate(req: CalibrationRequest, curve: CalibrationCurve): CalibrationResult {
        require(curve.id == req.curveId && curve.version == req.curveVersion) {
            "curve version mismatch: requested ${req.curveId}@${req.curveVersion}"
        }
        require(curve.fingerprint == req.curveFingerprint) {
            "curve fingerprint mismatch for ${curve.id}@${curve.version}: replay must pin content"
        }

        val n = Math.round((req.gridEnd - req.gridStart) / req.gridStep).toInt() + 1
        val raw = DoubleArray(n)
        val points = ArrayList<GridPoint>(n)
        var outOfRangeCells = 0
        var firstOut: Double? = null
        var lastOut: Double? = null
        val effectiveAge = req.measurement.c14AgeBP - req.reservoir.deltaRBP

        for (i in 0 until n) {
            val calBP = req.gridStart + i * req.gridStep
            if (curve.contains(calBP)) {
                val mu = curve.c14At(calBP)
                val sig = curve.totalSigmaAt(calBP, req.measurement.sigmaBP, req.reservoir.deltaRSigmaBP)
                raw[i] = normalDensity(effectiveAge, mu, sig)
                points.add(GridPoint(i, calBP, 0.0, mu, sig, true))
            } else {
                raw[i] = 0.0
                points.add(GridPoint(i, calBP, 0.0, Double.NaN, Double.NaN, false))
                outOfRangeCells++
                if (firstOut == null) firstOut = calBP
                lastOut = calBP
            }
        }

        val z = trapezoid(raw, req.gridStep)
        require(z > 0.0) { "calibration posterior has zero support: ${req.measurement.measurementId}" }
        for (i in 0 until n) points[i] = points[i].copy(density = raw[i] / z)

        val diagnostics = mutableListOf<Diagnostic>()
        if (outOfRangeCells > 0) {
            diagnostics.add(
                Diagnostic(
                    "OUT_OF_CURVE_RANGE",
                    "$outOfRangeCells grid cells lie outside ${curve.id}@${curve.version} " +
                        "(${curve.minCalBP}-${curve.maxCalBP} cal BP); density fixed to 0, no extrapolation.",
                    firstOut, lastOut
                )
            )
        }

        val i68 = tieGroups(hpdIntervals(points, req.gridStep, 0.68, z))
        val i95 = tieGroups(hpdIntervals(points, req.gridStep, 0.95, z))
        val modes = findModes(points, req.gridStep)

        if (modes.size > 1) {
            diagnostics.add(Diagnostic("MULTIMODAL_POSTERIOR",
                "${modes.size} separate calendar modes retained; the posterior is not reduced to a mean year."))
        }
        val peakD = points.maxOf { it.density }
        val edgeRatio = maxOf(points.first().density, points.last().density) / peakD
        val covered68 = i68.sumOf { it.probability }
        if (edgeRatio > 0.05 || covered68 < 0.675) {
            val atOld = points.last().calBP
            val atYoung = points.first().calBP
            val oldSide = points.last().density >= points.first().density
            diagnostics.add(Diagnostic(
                "BOUNDARY_TRUNCATION",
                "Posterior is cut by the ${if (oldSide) "older ($atOld cal BP)" else "younger ($atYoung cal BP)"} edge of " +
                    "${curve.id}@${curve.version}; edge density is ${"%.1f".format(edgeRatio * 100)}% of the peak and the 68% " +
                    "set only covers ${"%.3f".format(covered68)}. The missing calendar mass is reported as unknown, not extrapolated."
            ))
        }
        if (i68.any { it.crossesBceCe } || i95.any { it.crossesBceCe }) {
            diagnostics.add(Diagnostic("BCE_CE_CROSSING",
                "At least one credible interval straddles the BCE/CE boundary (year 0 = 1 BCE)."))
        }
        if (i68.size > 1 || i95.size > 1) {
            diagnostics.add(Diagnostic("MULTIPLE_DISJOINT_CALENDAR_INTERVALS",
                "Credible set splits into disjoint calendar intervals; all are reported as first-class candidates."))
        }

        return CalibrationResult(req, points, z, i68, i95, modes, diagnostics, fingerprint(req, z))
    }

    /** Integrate the normalized density over an arbitrary window (the expandable interval evidence). */
    fun integrate(result: CalibrationResult, fromCalBP: Double, toCalBP: Double): IntegrationEvidence =
        IntegrationEvidence(
            fromCalBP, toCalBP,
            integrate(result.points, fromCalBP, toCalBP),
            "user-expanded trapezoid integral of normalized posterior density",
            result.points.size,
            result.normalizationConstant
        )

    private fun trapezoid(v: DoubleArray, h: Double): Double {
        var s = 0.0
        for (i in 1 until v.size) s += v[i - 1] + v[i]
        return 0.5 * h * s
    }

    private fun hpdIntervals(points: List<GridPoint>, step: Double, level: Double, z: Double): List<CalendarInterval> {
        // Cell mass via trapezoid halves; HPD keeps the smallest high-density set whose mass >= level.
        val cellMass = DoubleArray(points.size)
        for (i in points.indices) {
            val left = if (i == 0) points[0].density else 0.5 * (points[i - 1].density + points[i].density)
            val right = if (i == points.size - 1) points[i].density else 0.5 * (points[i].density + points[i + 1].density)
            cellMass[i] = 0.5 * step * (left + right)
        }
        val order = points.indices.sortedWith(compareByDescending<Int> { points[it].density }.thenBy { it })
        val chosen = BooleanArray(points.size)
        var acc = 0.0
        for (idx in order) {
            if (points[idx].density <= 0.0) break
            chosen[idx] = true
            acc += cellMass[idx]
            if (acc >= level - 1e-12) break
        }

        val intervals = mutableListOf<CalendarInterval>()
        var i = 0
        while (i < points.size) {
            if (!chosen[i]) { i++; continue }
            val startIdx = i
            while (i < points.size && chosen[i]) i++
            val endIdx = i - 1
            val startBP = points[startIdx].calBP - step / 2.0
            val endBP = points[endIdx].calBP + step / 2.0
            val terms = (endIdx - startIdx) + 2
            intervals.add(buildInterval(startBP, endBP, integrate(points, startBP, endBP), level, terms, z))
        }
        return intervals
            .sortedWith(compareByDescending<CalendarInterval> { it.probability }.thenBy { it.startCalBP })
            .mapIndexed { rank, iv -> iv.copy(rank = rank + 1) }
    }

    private fun buildInterval(startBP: Double, endBP: Double, prob: Double, level: Double, terms: Int, z: Double) =
        CalendarInterval(
            stableId = "iv-" + shortHash("interval|$startBP|$endBP|$level"),
            startCalBP = startBP,
            endCalBP = endBP,
            probability = prob,
            credibleLevel = level,
            labelBP = CalendarAxis.formatInterval(startBP, endBP, EraSystem.BP_1950),
            labelBceCe = CalendarAxis.formatInterval(startBP, endBP, EraSystem.ANNO_ASTRONOMICAL),
            crossesBceCe = CalendarAxis.crossesBceCe(startBP, endBP),
            evidence = IntegrationEvidence(
                startCalBP = startBP,
                endCalBP = endBP,
                probability = prob,
                rule = "trapezoid integral of normalized posterior density; HPD credible level ${pct(level)}",
                trapezoidTerms = terms,
                normalizedUnder = z
            ),
            rank = 0,
            tieGroupId = null
        )

    private fun integrate(points: List<GridPoint>, from: Double, to: Double): Double {
        val step = points[1].calBP - points[0].calBP
        fun dAt(x: Double): Double {
            if (x <= points.first().calBP) return points.first().density
            if (x >= points.last().calBP) return points.last().density
            val pos = (x - points.first().calBP) / step
            val i = pos.toInt()
            val f = pos - i
            if (i + 1 >= points.size) return points[i].density
            return points[i].density + f * (points[i + 1].density - points[i].density)
        }
        val lo = max(from, points.first().calBP)
        val hi = min(to, points.last().calBP)
        if (hi <= lo) return 0.0
        val nSteps = Math.round((hi - lo) / (step / 2.0)).toInt().coerceAtLeast(1)
        val h = (hi - lo) / nSteps
        var s = 0.5 * (dAt(lo) + dAt(hi))
        for (k in 1 until nSteps) s += dAt(lo + k * h)
        return s * h
    }

    /**
     * Mode detection by local-maxima *prominence* (the vertical rise of a peak above the
     * highest saddle connecting it to a higher peak). A prominence floor of 12% of the
     * global peak discards wiggles on flat shelf tails while keeping genuinely separate
     * wiggle-lobe solutions, so the calibrated multi-modal posterior is never collapsed.
     */
    private fun findModes(points: List<GridPoint>, step: Double): List<ModePeak> {
        if (points.isEmpty()) return emptyList()
        val d = points.map { it.density }
        val peak = d.max()
        if (peak <= 0.0) return emptyList()
        val isMax = IntArray(d.size)
        for (i in 1 until d.size - 1) {
            if (d[i] >= d[i - 1] && d[i] > d[i + 1]) isMax[i] = 1
        }
        val peaks = isMax.indices.filter { isMax[it] == 1 }
        if (peaks.isEmpty()) {
            val j = d.indices.maxByOrNull { d[it] }!!
            return listOf(ModePeak("md-" + shortHash("mode|${points.first().calBP}|${points.last().calBP}"),
                points[j].calBP, d[j], integrate(points, points.first().calBP - step / 2, points.last().calBP + step / 2), 1))
        }
        val sorted = peaks.sortedByDescending { d[it] }
        data class Basin(var lo: Int, var hi: Int)
        val basinOf = HashMap<Int, Basin>()
        val accepted = mutableListOf<Int>()
        val prominenceFloor = peak * 0.12
        for (idx in sorted) {
            // nearest accepted higher peak on each side
            val left = accepted.filter { it < idx }.maxOrNull()
            val right = accepted.filter { it > idx }.minOrNull()
            val leftMin = if (left == null) 0.0 else (left until idx).minOf { d[it] }
            val rightMin = if (right == null) 0.0 else (idx + 1..right).minOf { d[it] }
            val saddle = min(leftMin, rightMin)
            val prominence = d[idx] - saddle
            if (prominence >= prominenceFloor) {
                accepted.add(idx)
                val lo = if (left == null) 0 else {
                    val s1 = (left until idx).minByOrNull { d[it] }!!
                    val ls = basinOf.getValue(left)
                    (s1 + ls.hi) / 2
                }
                val hi = if (right == null) d.size - 1 else {
                    val s2 = (idx + 1..right).minByOrNull { d[it] }!!
                    val rs = basinOf.getValue(right)
                    (s2 + rs.lo) / 2
                }
                basinOf[idx] = Basin(lo, hi)
            }
        }
        accepted.sort()
        return accepted.mapIndexed { k, idx ->
            val b = basinOf.getValue(idx)
            val startBP = if (k == 0) points.first().calBP - step / 2 else points[b.lo].calBP
            val endBP = if (k == accepted.size - 1) points.last().calBP + step / 2 else points[b.hi].calBP
            ModePeak(
                stableId = "md-" + shortHash("mode|$startBP|$endBP"),
                centerCalBP = points[idx].calBP,
                peakDensity = d[idx],
                modeMass = integrate(points, startBP, endBP),
                rank = 0
            )
        }
            .sortedWith(compareByDescending<ModePeak> { it.modeMass }.thenBy { it.centerCalBP })
            .mapIndexed { rank, m -> m.copy(rank = rank + 1) }
    }

    private fun tieGroups(intervals: List<CalendarInterval>): List<CalendarInterval> {
        val tol = 1e-7
        return intervals.map { iv ->
            val peers = intervals.filter { it.stableId != iv.stableId && abs(it.probability - iv.probability) <= tol }
            if (peers.isEmpty()) iv
            else iv.copy(tieGroupId = "tg-" + shortHash((peers + iv).map { it.stableId }.sorted().joinToString(",")))
        }
    }

    private fun pct(v: Double) = "${Math.round(v * 100)}%"

    private fun fingerprint(req: CalibrationRequest, z: Double): ParameterFingerprint {
        val mf = shortHash("meas|${req.measurement.measurementId}|${req.measurement.c14AgeBP}|${req.measurement.sigmaBP}")
        val rf = shortHash("res|${req.reservoir.id}|${req.reservoir.deltaRBP}|${req.reservoir.deltaRSigmaBP}")
        val basis = listOf(
            ENGINE_ALGORITHM_VERSION, req.gridStart, req.gridEnd, req.gridStep,
            req.curveFingerprint, mf, rf, "seed=$ENGINE_SEED", "z=$z"
        ).joinToString("|")
        return ParameterFingerprint(
            ENGINE_ALGORITHM_VERSION, req.gridStart, req.gridEnd, req.gridStep,
            req.curveFingerprint, mf, rf, ENGINE_SEED, shortHash(basis)
        )
    }

    private fun shortHash(input: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }.take(12)
    }
}
