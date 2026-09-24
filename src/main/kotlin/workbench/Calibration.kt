package workbench

import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Deterministic radiocarbon calibration on a fixed 1-year calBP grid.
 * The multimodal posterior is kept as a full density vector; it is never
 * collapsed to a point estimate. Same inputs + same algorithm version =>
 * identical fingerprint and identical density (reproducible under a fixed
 * curve version).
 */
object Calibration {
    const val ALGORITHM_VERSION = "calib-1.0.0"
    const val GRID_MIN = 0.0
    const val GRID_MAX = 5000.0
    const val GRID_STEP = 1.0
    const val HPD_MASS = 0.9545
    private const val SIGMA_RANGE = 5.0

    data class Output(
        val resultStatus: RunResultStatus,
        val posterior: Posterior?,
        val intervals: List<CredibleInterval>,
        val diagnostics: List<Diagnostic>,
        val fingerprint: String
    )

    fun fingerprint(curve: CalibrationCurve, measurement: C14Measurement, reservoir: ReservoirOffset): String {
        val canonical = buildString {
            append(ALGORITHM_VERSION).append('|')
            append(curve.contentHash).append('|')
            append(measurement.ageBP).append('|').append(measurement.sigma).append('|').append(measurement.unit).append('|')
            append(AppJson.encodeToString(ReservoirOffset.serializer(), reservoir)).append('|')
            append(GRID_MIN).append('|').append(GRID_MAX).append('|').append(GRID_STEP).append('|').append(HPD_MASS)
        }
        return sha256(canonical)
    }

    /** Effective 14C distribution as weighted normal components (weight, mean, sd). */
    fun effectiveComponents(m: C14Measurement, r: ReservoirOffset): List<Triple<Double, Double, Double>> = when (r) {
        is ReservoirOffset.None -> listOf(Triple(1.0, m.ageBP, m.sigma))
        is ReservoirOffset.Normal -> listOf(Triple(1.0, m.ageBP - r.deltaR, sqrt(m.sigma * m.sigma + r.sigma * r.sigma)))
        is ReservoirOffset.Mixture -> {
            val total = r.components.sumOf { it.weight }
            if (total <= 0.0) listOf(Triple(1.0, m.ageBP, m.sigma))
            else r.components.map { c ->
                Triple(c.weight / total, m.ageBP - c.deltaR, sqrt(m.sigma * m.sigma + c.sigma * c.sigma))
            }
        }
    }

    fun calibrate(curve: CalibrationCurve, measurement: C14Measurement, reservoir: ReservoirOffset): Output {
        val diagnostics = mutableListOf<Diagnostic>()
        val fp = fingerprint(curve, measurement, reservoir)
        if (measurement.sigma <= 0.0) {
            diagnostics += Diagnostic("INVALID_MEASUREMENT", "标准差必须为正数: sigma=${measurement.sigma}")
            return Output(RunResultStatus.ERROR, null, emptyList(), diagnostics, fp)
        }
        val components = effectiveComponents(measurement, reservoir)
        val lo = components.minOf { it.second - SIGMA_RANGE * it.third }
        val hi = components.maxOf { it.second + SIGMA_RANGE * it.third }
        if (hi < curve.minC14 || lo > curve.maxC14) {
            diagnostics += Diagnostic(
                "OUT_OF_CURVE_RANGE",
                "测量值 ${measurement.ageBP}±${measurement.sigma} BP 的 5σ 范围 [$lo, $hi] 与曲线 ${curve.name} v${curve.version} 的 14C 范围 [${curve.minC14}, ${curve.maxC14}] 不相交；值保留在原位置，不做外推"
            )
            return Output(RunResultStatus.ERROR, null, emptyList(), diagnostics, fp)
        }
        if (lo < curve.minC14 || hi > curve.maxC14) {
            val edge = if (lo < curve.minC14) curve.minCalBP else curve.maxCalBP
            diagnostics += Diagnostic(
                "TRUNCATED_AT_BOUNDARY",
                "5σ 范围 [$lo, $hi] 超出曲线 14C 范围 [${curve.minC14}, ${curve.maxC14}]，后验在网格边界 ${edge.toInt()} calBP 处被截断",
                edge
            )
        }

        val n = ((GRID_MAX - GRID_MIN) / GRID_STEP).toInt() + 1
        val raw = DoubleArray(n)
        for (i in 0 until n) {
            val t = GRID_MIN + i * GRID_STEP
            val cp = curve.at(t) ?: continue
            var lik = 0.0
            for ((w, mean, sd) in components) {
                val s = sqrt(sd * sd + cp.sigma * cp.sigma)
                val z = (mean - cp.c14AgeBP) / s
                lik += w * exp(-0.5 * z * z) / (s * SQRT_2PI)
            }
            raw[i] = lik
        }
        val total = raw.sum() * GRID_STEP
        if (total <= 0.0 || total.isNaN()) {
            diagnostics += Diagnostic("ZERO_POSTERIOR", "后验总质量为零，无法归一化")
            return Output(RunResultStatus.ERROR, null, emptyList(), diagnostics, fp)
        }
        val density = raw.map { it / total }
        val posterior = Posterior(GRID_MIN, GRID_MAX, GRID_STEP, density, diagnostics.toList())
        val intervals = hpdIntervals(posterior, HPD_MASS)
        return Output(RunResultStatus.OK, posterior, intervals, diagnostics, fp)
    }

    private const val SQRT_2PI = 2.5066282746310002

    /** Highest-posterior-density regions at the given mass; disjoint regions stay disjoint. */
    fun hpdIntervals(p: Posterior, mass: Double): List<CredibleInterval> {
        val n = p.density.size
        val order = p.density.indices.sortedByDescending { p.density[it] }
        var acc = 0.0
        var k = 0
        while (k < n && acc < mass) {
            acc += p.density[order[k]] * p.step
            k++
        }
        if (k == 0) return emptyList()
        val threshold = p.density[order[k - 1]] * (1.0 - 1e-9)
        val intervals = mutableListOf<CredibleInterval>()
        var i = 0
        while (i < n) {
            if (p.density[i] >= threshold) {
                var j = i
                var prob = 0.0
                while (j < n && p.density[j] >= threshold) {
                    prob += p.density[j] * p.step
                    j++
                }
                intervals += CredibleInterval(p.gridMin + i * p.step, p.gridMin + (j - 1) * p.step, prob)
                i = j
            } else {
                i++
            }
        }
        return intervals.sortedByDescending { it.probability }
    }

    /** Trapezoidal integration of the posterior over [fromCalBP, toCalBP], with full evidence. */
    fun intervalEvidence(runId: String, p: Posterior, fromCalBP: Double, toCalBP: Double, fingerprint: String): IntervalEvidence {
        require(fromCalBP <= toCalBP) { "from must be <= to" }
        fun densityAt(t: Double): Double {
            if (t < p.gridMin || t > p.gridMax) return 0.0
            val pos = (t - p.gridMin) / p.step
            val i = pos.toInt().coerceIn(0, p.density.size - 1)
            val f = pos - i
            val d0 = p.density[i]
            val d1 = if (i + 1 < p.density.size) p.density[i + 1] else 0.0
            return d0 + f * (d1 - d0)
        }
        var sum = 0.0
        var t = fromCalBP
        var prev = densityAt(t)
        var points = 1
        while (t < toCalBP) {
            val next = minOf(toCalBP, p.gridMin + (kotlin.math.floor((t - p.gridMin) / p.step) + 1) * p.step)
            val dNext = densityAt(next)
            sum += (prev + dNext) * 0.5 * (next - t)
            prev = dNext
            t = next
            points++
        }
        return IntervalEvidence(
            runId = runId,
            fromCalBP = fromCalBP,
            toCalBP = toCalBP,
            probability = sum,
            gridPoints = points,
            riemannSum = "trapezoid(step=${p.step} calBP, points=$points, range=[$fromCalBP,$toCalBP])",
            algorithmVersion = ALGORITHM_VERSION,
            fingerprint = fingerprint
        )
    }
}
