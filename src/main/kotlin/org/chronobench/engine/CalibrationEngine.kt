package org.chronobench.engine

import org.chronobench.domain.*
import kotlin.math.*

/**
 * Deterministic radiocarbon calibration.
 *
 * Fixed algorithm version + fixed one-year grid + linear interpolation between published
 * curve nodes make every result byte-reproducible for a fixed curve version. The posterior
 * is kept as a full discrete distribution: peaks and HPD intervals are first-class data,
 * never a single averaged calendar year.
 */
object CalibrationEngine {
    const val ALGORITHM_VERSION = "cal-v1.0.0"
    private const val GRID_STEP = 1
    private const val HPD_MASS = 0.95
    private const val EDGE_EPS = 1e-6

    data class CurveSample(val calBP: Int, val c14BP: Double, val c14Sigma: Double, val lowerNode: CurvePoint, val upperNode: CurvePoint)

    fun interpolate(curve: CalCurve, t: Int): CurveSample? {
        val pts = curve.points
        if (t < pts.first().calBP || t > pts.last().calBP) return null
        if (pts.first().calBP == t) return CurveSample(t, pts.first().c14BP, pts.first().c14Sigma, pts.first(), pts.first())
        if (pts.last().calBP == t) return CurveSample(t, pts.last().c14BP, pts.last().c14Sigma, pts.last(), pts.last())
        var i = 0
        while (i < pts.size - 1 && pts[i + 1].calBP < t) i++
        val a = pts[i]; val b = pts[i + 1]
        val f = (t - a.calBP).toDouble() / (b.calBP - a.calBP)
        return CurveSample(
            t,
            a.c14BP + f * (b.c14BP - a.c14BP),
            a.c14Sigma + f * (b.c14Sigma - a.c14Sigma),
            a, b
        )
    }

    private fun gauss(x: Double, mu: Double, sigma: Double): Double {
        val z = (x - mu) / sigma
        return exp(-0.5 * z * z) / (sigma * sqrt(2.0 * PI))
    }

    private fun fingerprint(inputs: InputRefs, ageRaw: String, ageValue: Double, ageSigma: Double): String {
        val canonical = buildString {
            append(ALGORITHM_VERSION).append('|')
            append("grid:").append(GRID_STEP).append('|')
            append("hpd:").append(HPD_MASS).append('|')
            append("m:").append(inputs.measurementId).append('@').append(inputs.measurementVersion).append('|')
            append("age:").append(ageRaw).append(':').append(ageValue).append(':').append(ageSigma).append('|')
            append("c:").append(inputs.curveId).append('@').append(inputs.curveVersion).append('|')
            append("r:").append(inputs.reservoirHypothesisId ?: "-").append('@')
                .append(inputs.reservoirHypothesisVersion ?: "-").append('|')
            append("y:").append(inputs.yearSystemId).append('@').append(inputs.yearSystemVersion)
        }
        return Hashing.sha256Hex(canonical)
    }

    data class RawGrid(val t: Int, val lik: Double, val sample: CurveSample, val shiftedAge: Double)

    fun calibrate(
        projectId: String,
        measurement: Measurement,
        curve: CalCurve,
        reservoir: ReservoirHypothesis?,
        yearSystem: YearSystem
    ): Calibration {
        val inputs = InputRefs(
            measurement.id, measurement.version, curve.id, curve.version,
            reservoir?.id, reservoir?.version, yearSystem.id, yearSystem.version
        )
        val diagnostics = mutableListOf<String>()
        if (!measurement.age.convertible || !measurement.sigma.convertible) {
            diagnostics += "MEASUREMENT_NOT_CONVERTIBLE: 原始表示无法转换为数值，保留在来源中，不参与绘图"
            return Calibration(
                id = calibrationId(projectId, inputs), projectId = projectId, inputs = inputs,
                algorithmVersion = ALGORITHM_VERSION,
                fingerprint = fingerprint(inputs, measurement.age.raw, Double.NaN, measurement.sigma.value ?: Double.NaN),
                status = CalStatus.UNKNOWN, diagnostics = diagnostics, grid = emptyList(), peaks = emptyList(),
                intervals95 = emptyList(), createdSeq = 0
            )
        }
        val age = measurement.age.value!!
        val ageSigma = measurement.sigma.value!!

        val raw = ArrayList<RawGrid>()
        var rawSum = 0.0
        var maxLik = 0.0
        for (t in curve.tMin..curve.tMax step GRID_STEP) {
            val s = interpolate(curve, t)!!
            var lik = 0.0
            var shiftedAge = s.c14BP
            val comps = reservoir?.components ?: listOf(OffsetComponent(0.0, 0.0, 1.0))
            val wSum = comps.sumOf { it.weight }
            for (comp in comps) {
                val mu = s.c14BP + comp.meanBP
                val sd = sqrt(ageSigma.pow(2) + s.c14Sigma.pow(2) + comp.sigmaBP.pow(2))
                lik += (comp.weight / wSum) * gauss(age, mu, sd)
                if (comp === comps.first()) shiftedAge = mu
            }
            raw += RawGrid(t, lik, s, shiftedAge)
            rawSum += lik
            if (lik > maxLik) maxLik = lik
        }

        val cutoff = EDGE_EPS * maxLik
        val edgeLeak = raw.first().lik > cutoff || raw.last().lik > cutoff
        if (edgeLeak) {
            diagnostics += "BOUNDARY_TRUNCATED: 曲线端点处似然仍高于峰值的 1e-6，积分在 ${curve.tMin}/${curve.tMax} cal BP 被截断"
        }

        val grid = raw.map { GridPoint(it.t, it.lik / rawSum) }
        val status = if (edgeLeak) CalStatus.BOUNDARY_TRUNCATED else CalStatus.OK

        val peaks = findPeaks(grid)
        val intervals = hpdIntervals(grid, raw, rawSum, curve)
        if (peaks.size > 1) {
            diagnostics += "MULTIMODAL: 检测到 ${peaks.size} 个众数，保留全部候选，不压缩为平均年"
        }
        if (intervals.any { it.crossesBceCe }) {
            diagnostics += "BCE_CE_CROSSING: 至少一个并列日历区间跨越 1 BCE/1 CE 界线（1950 cal BP）"
        }

        return Calibration(
            id = calibrationId(projectId, inputs), projectId = projectId, inputs = inputs,
            algorithmVersion = ALGORITHM_VERSION,
            fingerprint = fingerprint(inputs, measurement.age.raw, age, ageSigma),
            status = status, diagnostics = diagnostics, grid = grid, peaks = peaks,
            intervals95 = intervals, createdSeq = 0
        )
    }

    fun calibrationId(projectId: String, inputs: InputRefs): String {
        val basis = listOf(
            projectId,
            "${inputs.measurementId}@${inputs.measurementVersion}",
            "${inputs.curveId}@${inputs.curveVersion}",
            "${inputs.reservoirHypothesisId ?: "-"}@${inputs.reservoirHypothesisVersion ?: "-"}",
            "${inputs.yearSystemId}@${inputs.yearSystemVersion}"
        ).joinToString("|")
        return "cal_" + Hashing.sha256Hex(basis).take(16)
    }

    private fun findPeaks(grid: List<GridPoint>): List<Peak> {
        if (grid.isEmpty()) return emptyList()
        val maxP = grid.maxOf { it.p }
        val threshold = 0.02 * maxP
        val peaks = ArrayList<Peak>()
        for (i in grid.indices) {
            val cur = grid[i].p
            if (cur < threshold) continue
            val left = if (i == 0) 0.0 else grid[i - 1].p
            val right = if (i == grid.size - 1) 0.0 else grid[i + 1].p
            if (cur >= left && cur >= right && (cur > left || cur > right)) {
                peaks += Peak(grid[i].calBP, cur, cur)
            }
        }
        return mergeClosePeaks(peaks, 12)
    }

    private fun mergeClosePeaks(peaks: List<Peak>, minGap: Int): List<Peak> {
        if (peaks.size < 2) return peaks
        val out = ArrayList<Peak>()
        var i = 0
        while (i < peaks.size) {
            var j = i
            var best = peaks[i]
            while (j + 1 < peaks.size && peaks[j + 1].calBP - peaks[j].calBP < minGap) {
                j++
                if (peaks[j].p > best.p) best = peaks[j]
            }
            out += best
            i = j + 1
        }
        return out
    }

    private fun hpdIntervals(grid: List<GridPoint>, raw: List<RawGrid>, rawSum: Double, curve: CalCurve): List<CalendarInterval> {
        if (grid.isEmpty()) return emptyList()
        val byDesc = grid.withIndex().sortedByDescending { it.value.p }
        var acc = 0.0
        var threshold = 0.0
        for ((_, gp) in byDesc) {
            acc += gp.p
            threshold = gp.p
            if (acc >= HPD_MASS) break
        }
        val inside = BooleanArray(grid.size) { grid[it].p >= threshold - 1e-15 }
        val runs = ArrayList<Pair<Int, Int>>()
        var start = -1
        for (i in grid.indices) {
            if (inside[i] && start < 0) start = i
            if ((!inside[i] || i == grid.size - 1) && start >= 0) {
                val end = if (inside[i]) i else i - 1
                runs += start to end
                start = -1
            }
        }
        data class Run(val loI: Int, val hiI: Int, val mass: Double)
        val scored = runs.map { (loI, hiI) ->
            Run(loI, hiI, (loI..hiI).sumOf { grid[it].p })
        }.sortedByDescending { it.mass }
        return scored.mapIndexed { rank, run ->
            val lo = grid[run.loI].calBP; val hi = grid[run.hiI].calBP
            val intervalRawSum = (run.loI..run.hiI).sumOf { raw[it].lik }
            val sightings = buildSightings(raw, run.loI, run.hiI, curve)
            CalendarInterval(
                id = "i${rank + 1}",
                rank = rank + 1,
                loCalBP = lo, hiCalBP = hi,
                mass = run.mass,
                evidence = IntegrationEvidence(
                    gridStepYears = GRID_STEP,
                    gridSteps = run.hiI - run.loI + 1,
                    rawSum = intervalRawSum,
                    normalizer = rawSum * GRID_STEP,
                    normalizedMass = run.mass,
                    sightings = sightings
                ),
                crossesBceCe = lo <= 1950 && hi >= 1950
            )
        }
    }

    private fun buildSightings(raw: List<RawGrid>, loI: Int, hiI: Int, curve: CalCurve): List<InterpSighting> {
        if (loI > hiI) return emptyList()
        val midI = (loI + hiI) / 2
        val maxI = (loI..hiI).maxByOrNull { raw[it].lik }!!
        val picks = linkedSetOf(loI, midI, maxI, hiI).sorted()
        return picks.map { i ->
            val r = raw[i]
            InterpSighting(
                calBP = r.t,
                pointId = r.sample.lowerNode.pointId(curve.id, curve.version),
                curveC14BP = r.sample.c14BP,
                curveSigma = r.sample.c14Sigma,
                offsetShiftedAge = r.shiftedAge,
                likelihood = r.lik
            )
        }
    }
}
