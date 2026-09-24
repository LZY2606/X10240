package workbench

import kotlinx.serialization.Serializable
import kotlin.math.*

const val CALIB_ALGORITHM_VERSION = "calib-grid-1.0"

@Serializable
data class DensityPoint(val calBp: Double, val raw: Double, val norm: Double)

@Serializable
data class Peak(val calBp: Double, val density: Double, val label: String)

@Serializable
data class CalInterval(
    val loCalBp: Double,
    val hiCalBp: Double,
    val loLabel: String,
    val hiLabel: String,
    val probability: Double,
    val level: String,
    val bceCeCross: Boolean
)

@Serializable
data class IntegrationEvidence(
    val loCalBp: Double,
    val hiCalBp: Double,
    val loLabel: String,
    val hiLabel: String,
    val stepYears: Double,
    val gridPointCount: Int,
    val rawMass: Double,
    val normalizationConstant: Double,
    val probability: Double,
    val formula: String,
    val algorithmVersion: String,
    val bceCeCross: Boolean
)

@Serializable
data class CalibrationResult(
    val algorithmVersion: String,
    val paramFingerprint: String,
    val measurementSourceId: String,
    val curveSourceId: String,
    val offsetSourceId: String?,
    val offsetSummary: String,
    val gridLo: Double,
    val gridHi: Double,
    val stepYears: Double,
    val points: List<DensityPoint>,
    val peaks: List<Peak>,
    val intervals68: List<CalInterval>,
    val intervals95: List<CalInterval>,
    val diagnostics: List<String>,
    val unknown: Boolean,
    val unknownReason: String? = null
) {
    fun evidenceFor(loCalBp: Double, hiCalBp: Double): IntegrationEvidence =
        Integration.evidence(points, loCalBp, hiCalBp, stepYears, zOf(), algorithmVersion)
    fun zOf(): Double = trapezoidRaw(points, stepYears)
}

/** 正态密度。 */
private fun gauss(x: Double, mean: Double, sd: Double): Double {
    if (sd <= 0) return if (x == mean) 1.0 else 0.0
    val z = (x - mean) / sd
    return exp(-0.5 * z * z) / (sd * sqrt(2.0 * PI))
}

/** 校准曲线的线性插值；超出范围时钳到端点并由调用方记录诊断（边界插值）。 */
class InterpolatedCurve(val data: CurveData) {
    val sorted = data.points.sortedBy { it.calBp }
    fun at(calBp: Double): Pair<Double, Double> {
        if (calBp <= sorted.first().calBp) return sorted.first().c14Age to sorted.first().sigma
        if (calBp >= sorted.last().calBp) return sorted.last().c14Age to sorted.last().sigma
        var i = 0
        while (sorted[i + 1].calBp < calBp) i++
        val a = sorted[i]; val b = sorted[i + 1]
        val f = (calBp - a.calBp) / (b.calBp - a.calBp)
        return (a.c14Age + f * (b.c14Age - a.c14Age)) to
                (a.sigma + f * (b.sigma - a.sigma))
    }
}

private fun trapezoidRaw(points: List<DensityPoint>, step: Double): Double {
    var s = 0.0
    for (i in 0 until points.size - 1) {
        val w = points[i + 1].calBp - points[i].calBp
        s += (points[i].raw + points[i + 1].raw) * 0.5 * w
    }
    return s
}

object Integration {
    /**
     * 区间 [lo,hi] 积分证据：归一化前后都保留，可逐步复核。
     * 密度在格点间按线性处理；端点精确插值，不用“平均年”代替。
     */
    fun evidence(
        points: List<DensityPoint>,
        lo: Double,
        hi: Double,
        step: Double,
        z: Double,
        algo: String
    ): IntegrationEvidence {
        val a = min(lo, hi); val b = max(lo, hi)
        val seg = points.filter { it.calBp in a..b }.toMutableList()
        fun interpAt(x: Double): Pair<Double, Double> {
            if (x <= points.first().calBp) return points.first().raw to points.first().norm
            if (x >= points.last().calBp) return points.last().raw to points.last().norm
            var i = 0
            while (points[i + 1].calBp < x) i++
            val p0 = points[i]; val p1 = points[i + 1]
            val f = (x - p0.calBp) / (p1.calBp - p0.calBp)
            return (p0.raw + f * (p1.raw - p0.raw)) to (p0.norm + f * (p1.norm - p1.norm))
        }
        if (seg.isEmpty() || seg.first().calBp != a) seg.add(0, DensityPoint(a, interpAt(a).first, interpAt(a).second))
        if (seg.last().calBp != b) seg.add(DensityPoint(b, interpAt(b).first, interpAt(b).second))
        seg.sortBy { it.calBp }
        var rawMass = 0.0
        for (i in 0 until seg.size - 1) {
            rawMass += (seg[i].raw + seg[i + 1].raw) * 0.5 * (seg[i + 1].calBp - seg[i].calBp)
        }
        val prob = if (z > 0) rawMass / z else 0.0
        return IntegrationEvidence(
            loCalBp = a, hiCalBp = b,
            loLabel = Era.calBpToLabel(a), hiLabel = Era.calBpToLabel(b),
            stepYears = step, gridPointCount = points.size,
            rawMass = rawMass, normalizationConstant = z, probability = prob,
            formula = "P(t in [lo,hi]) = trapz(raw density)/Z ; Z = trapz over full grid",
            algorithmVersion = algo,
            bceCeCross = Era.crossesBceCe(b, a)
        )
    }
}

object Calibrator {

    fun canonicalParams(
        measSourceId: String, m: Measurement,
        curveSourceId: String, c: CurveData,
        offsetSourceId: String? = null, r: ReservoirOffset? = null,
        gridLo: Double, gridHi: Double, step: Double
    ): String {
        val curveDigest = sha256(c.points.joinToString(",") { "%.3f/%.3f/%.3f".format(it.calBp, it.c14Age, it.sigma) }).take(12)
        val off = if (r == null) "none" else
            "%.3f+-%.3f#%s#%s".format(r.mean, r.sigma, r.samples.joinToString(";") { "%.1f".format(it) }, offsetSourceId)
        return listOf(
            "meas=$measSourceId@${m.labCode}:${m.ageBp}+-${m.sigma}",
            "curve=$curveSourceId@${c.name}:n=${c.points.size}:$curveDigest",
            "offset=$off",
            "grid=$gridLo..$gridHi..$step"
        ).joinToString("|")
    }

    fun calibrate(
        measSourceId: String, m: Measurement,
        curveSourceId: String, c: CurveData,
        offsetSourceId: String? = null, r: ReservoirOffset? = null,
        requestedLo: Double? = null, requestedHi: Double? = null,
        step: Double = 1.0
    ): CalibrationResult {
        val diagnostics = mutableListOf<String>()
        val curve = InterpolatedCurve(c)
        var lo = requestedLo ?: c.minCalBp
        var hi = requestedHi ?: c.maxCalBp
        if (lo < c.minCalBp) {
            diagnostics.add("boundary-clamp:low:requested=%.1f:curveMin=%.1f:curve value held constant below edge".format(lo, c.minCalBp))
            lo = c.minCalBp
        }
        if (hi > c.maxCalBp) {
            diagnostics.add("boundary-clamp:high:requested=%.1f:curveMax=%.1f:curve value held constant above edge".format(hi, c.maxCalBp))
            hi = c.maxCalBp
        }
        if (lo > hi) {
            return CalibrationResult(
                CALIB_ALGORITHM_VERSION, "invalid", measSourceId, curveSourceId, offsetSourceId,
                offsetSummary(r), lo, hi, step, emptyList(), emptyList(), emptyList(), emptyList(),
                listOf("grid-inverted:lo>hi"), unknown = true, unknownReason = "grid range inverted"
            )
        }
        val n = ((hi - lo) / step).roundToInt() + 1
        val raws = DoubleArray(n)
        val ts = DoubleArray(n) { lo + it * step }
        for (i in 0 until n) {
            val (muC, sigC) = curve.at(ts[i])
            raws[i] = if (r != null && r.samples.isNotEmpty()) {
                // 显式偏移样本 -> 混合分布，每个样本权重相等（确定性，无随机抽样）
                r.samples.sumOf { d ->
                    gauss(m.ageBp, muC + d, sqrt(sigC * sigC + m.sigma * m.sigma))
                } / r.samples.size
            } else {
                val d = r?.mean ?: 0.0
                val sd = sqrt(sigC * sigC + m.sigma * m.sigma + (r?.sigma ?: 0.0).let { it * it })
                gauss(m.ageBp, muC + d, sd)
            }
        }
        var z = 0.0
        for (i in 0 until n - 1) z += (raws[i] + raws[i + 1]) * 0.5 * step
        if (z <= 0) {
            return CalibrationResult(
                CALIB_ALGORITHM_VERSION, "zero-mass", measSourceId, curveSourceId, offsetSourceId,
                offsetSummary(r), lo, hi, step, emptyList(), emptyList(), emptyList(), emptyList(),
                diagnostics + "zero-normalization-mass", unknown = true, unknownReason = "no curve overlap"
            )
        }
        val points = (0 until n).map { DensityPoint(ts[it], raws[it], raws[it] / z) }

        val peaks = findPeaks(points)
        val i68 = hdrIntervals(points, 0.682, "HDR-68.2%")
        val i95 = hdrIntervals(points, 0.954, "HDR-95.4%")
        if (i68.size > 1) diagnostics.add("parallel-calendar-intervals:68%:count=${i68.size}:multimodal-not-averaged")
        if (i68.any { it.bceCeCross } || i95.any { it.bceCeCross }) diagnostics.add("bce-ce-boundary-crossed")
        if (r != null && r.samples.isNotEmpty()) diagnostics.add("reservoir-offset-mixture:samples=${r.samples.size}")
        else if (r != null) diagnostics.add("reservoir-offset-gaussian:mean=%.1f:sigma=%.1f".format(r.mean, r.sigma))

        val fp = fingerprintOf(
            CALIB_ALGORITHM_VERSION,
            canonicalParams(measSourceId, m, curveSourceId, c, offsetSourceId, r, lo, hi, step)
        )
        return CalibrationResult(
            CALIB_ALGORITHM_VERSION, fp, measSourceId, curveSourceId, offsetSourceId,
            offsetSummary(r), lo, hi, step, points, peaks, i68, i95, diagnostics, unknown = false
        )
    }

    private fun offsetSummary(r: ReservoirOffset?): String = when {
        r == null -> "no reservoir offset"
        r.samples.isNotEmpty() -> "mixture of ${r.samples.size} explicit samples"
        else -> "ΔR=%.0f±%.0f yr".format(r.mean, r.sigma)
    }

    private fun findPeaks(points: List<DensityPoint>): List<Peak> {
        val out = mutableListOf<Peak>()
        for (i in 1 until points.size - 1) {
            if (points[i].norm >= points[i - 1].norm && points[i].norm > points[i + 1].norm)
                out.add(Peak(points[i].calBp, points[i].norm, Era.calBpToLabel(points[i].calBp)))
        }
        return out
    }

    /** 最高密度区域：扫描密度阈值，取覆盖质量 ≈ level 的全部连续段（可能并列多个）。 */
    fun hdrIntervals(points: List<DensityPoint>, level: Double, label: String): List<CalInterval> {
        if (points.size < 2) return emptyList()
        val step = points[1].calBp - points[0].calBp
        val maxD = points.maxOf { it.norm }
        fun massAbove(tau: Double): Double {
            var s = 0.0
            for (i in 0 until points.size - 1) {
                val v0 = if (points[i].norm >= tau) points[i].norm else 0.0
                val v1 = if (points[i + 1].norm >= tau) points[i + 1].norm else 0.0
                if (v0 > 0 && v1 > 0) s += (v0 + v1) * 0.5 * (points[i + 1].calBp - points[i].calBp)
            }
            return s
        }
        var loT = 0.0; var hiT = maxD
        repeat(60) {
            val mid = (loT + hiT) / 2
            if (massAbove(mid) >= level) loT = mid else hiT = mid
        }
        val tau = loT
        val runs = mutableListOf<IntRange>()
        var start = -1
        for (i in points.indices) {
            if (points[i].norm >= tau) { if (start < 0) start = i }
            else if (start >= 0) { runs.add(start until i); start = -1 }
        }
        if (start >= 0) runs.add(start until points.size)
        return runs.map { rn ->
            val seg = points.slice(rn)
            var mass = 0.0
            for (i in 0 until seg.size - 1) mass += (seg[i].norm + seg[i + 1].norm) * 0.5 * step
            CalInterval(
                seg.first().calBp, seg.last().calBp,
                Era.calBpToLabel(seg.first().calBp), Era.calBpToLabel(seg.last().calBp),
                mass, label, Era.crossesBceCe(seg.last().calBp, seg.first().calBp)
            )
        }
    }
}
