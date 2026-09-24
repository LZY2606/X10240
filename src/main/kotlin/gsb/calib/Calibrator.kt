package gsb.calib

import gsb.model.*
import kotlin.math.*

/**
 * 放射性碳校准引擎。
 * 固定算法版本字符串；同一曲线版本 + 参数指纹下结果逐位可重现（无随机过程）。
 * 偏移分布使用固定 seed 的确定性蒙特卡洛（实际采用解析混合，seed 仅用于可追溯标注）。
 */
object Calibrator {
    const val ALGORITHM_VERSION = "calib-gauss-linear-v1"
    private const val SQRT_2PI = 2.5066282746310002

    /** 曲线线性插值（含 sigma 插值）。落在端点之外返回 null，触发边界诊断而非静默外推。 */
    fun interpolate(curve: CurvePayload, calBp: Double): CurvePoint? {
        val pts = curve.points
        if (pts.size < 2) return null
        if (calBp < pts.first().calBp || calBp > pts.last().calBp) return null
        var lo = 0
        var hi = pts.size - 1
        // 二分查找所在区间
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (pts[mid].calBp <= calBp) lo = mid else hi = mid
        }
        val a = pts[lo]; val b = pts[hi]
        val span = b.calBp - a.calBp
        val f = if (span == 0.0) 0.0 else (calBp - a.calBp) / span
        return CurvePoint(
            calBp = calBp,
            c14Bp = a.c14Bp + f * (b.c14Bp - a.c14Bp),
            sigma = a.sigma + f * (b.sigma - a.sigma)
        )
    }

    private fun normalPdf(x: Double, mu: Double, sd: Double): Double {
        val z = (x - mu) / sd
        return exp(-0.5 * z * z) / (sd * SQRT_2PI)
    }

    /**
     * 后验：对偏移分布每个分量，
     * mu_c(t)=c14Bp(t)+deltaR，sig_c(t)=sqrt(curveSigma^2 + measSigma^2 + offsetSigma^2)。
     * 密度按分量权重混合。
     */
    fun calibrate(
        measurement: MeasurementPayload,
        curve: CurvePayload,
        offset: OffsetPayload,
        seed: Long,
        computedAt: String,
        eventId: String,
        sourceIds: List<String>,
        resultId: String
    ): CalibrationResult {
        val age = measurement.age.value!!
        val measSigma = measurement.sigma.value!!

        val minBp = curve.points.first().calBp
        val maxBp = curve.points.last().calBp
        // 1 年 bin；bin 中心从 ceil(minBp) 到 floor(maxBp)
        val firstCenter = ceil(minBp).toInt()
        val lastCenter = floor(maxBp).toInt()
        val n = lastCenter - firstCenter + 1
        val raw = DoubleArray(n)

        val weights = offset.components.map { it.weight }
        val wSum = weights.sum()
        val normWeights = offset.components.map { it.weight / wSum }

        var touchLower = false
        var touchUpper = false

        for (i in 0 until n) {
            val centerBp = firstCenter + i.toDouble()
            val cp = interpolate(curve, centerBp) ?: continue
            var mix = 0.0
            for (k in offset.components.indices) {
                val c = offset.components[k]
                val mu = cp.c14Bp + c.deltaR
                val sd = sqrt(cp.sigma * cp.sigma + measSigma * measSigma + c.sigma * c.sigma)
                mix += normWeights[k] * normalPdf(age, mu, sd)
            }
            raw[i] = mix
            // 边界（端点 bin）是否有显著质量
            if (i == 0 && raw[0] > 1e-12) touchLower = true
            if (i == n - 1 && raw[n - 1] > 1e-12) touchUpper = true
        }

        // 归一化（梯形/等宽即求和*binWidth）
        val sum = raw.sum()
        val binWidth = 1.0
        val p = if (sum > 0) raw.map { it * binWidth / sum } else raw.map { 0.0 }
        val density = Density(
            gridMinYear = Era.calBpToAstronomical(lastCenter.toDouble()),
            gridMaxYear = Era.calBpToAstronomical(firstCenter.toDouble()),
            binWidth = binWidth,
            p = p,
            normalized = sum > 0,
            normalizationSum = sum
        )

        val intervals = findIntervals(density, resultId)
        val cross = intervals.any { it.loYear <= 0 && it.hiYear >= 1 }
        val msgs = mutableListOf<String>()
        if (touchLower) msgs += "密度触及校准曲线老端边界 ${Era.label(density.gridMaxYear)}（边界插值，未外推）"
        if (touchUpper) msgs += "密度触及校准曲线年轻端边界 ${Era.label(density.gridMinYear)}（边界插值，未外推）"
        if (!density.normalized) msgs += "ERROR: 后验总质量为零，无法归一化"
        if (cross) msgs += "候选区间跨越 BCE/CE 分界"

        val fp = fingerprint(measurement, curve, offset)
        val prov = Provenance(
            algorithmVersion = ALGORITHM_VERSION,
            paramFingerprint = fp,
            seed = seed,
            sources = sourceIds,
            curveVersion = "${curve.curveId}@${curve.version}",
            offsetSignature = offsetSig(offset),
            computedAt = computedAt,
            eventId = eventId
        )
        return CalibrationResult(
            id = resultId,
            measurementNodeId = "",
            density = density,
            intervals = intervals,
            provenance = prov,
            status = ArtifactStatus.CURRENT,
            boundary = BoundaryInfo(
                touchesCurveLower = touchLower,
                touchesCurveUpper = touchUpper,
                crossesBceCe = cross,
                withinCurve = density.normalized,
                messages = msgs
            )
        )
    }

    fun offsetSig(o: OffsetPayload): String =
        o.components.sortedBy { it.deltaR }.joinToString("|") { "dR=${it.deltaR},s=${it.sigma},w=${it.weight}" }

    fun fingerprint(m: MeasurementPayload, c: CurvePayload, o: OffsetPayload): String {
        val base = "${m.age.raw}|${m.sigma.raw}|${c.curveId}@${c.version}|${c.points.size}|${offsetSig(o)}"
        val h = base.hashCode().toLong() and 0xffffffffL
        return "fp-%08x".format(h)
    }

    /**
     * 找多峰区间：先定位众数（局部极大），再按 95% HPD 收集各峰的连续支撑。
     * 不压成平均年——返回所有并列区间。
     */
    fun findIntervals(d: Density, resultId: String): List<DateInterval> {
        val p = d.p
        val n = p.size
        if (n == 0 || p.sum() <= 0) return emptyList()
        // 众峰（局部极大，含平台）
        val peaks = mutableListOf<Int>()
        for (i in p.indices) {
            val left = if (i == 0) 0.0 else p[i - 1]
            val right = if (i == n - 1) 0.0 else p[i + 1]
            if (p[i] >= left && p[i] >= right && p[i] > 0) {
                if (peaks.isEmpty() || i - peaks.last() > 1) peaks += i
                else if (p[i] > p[peaks.last()]) { peaks[peaks.size - 1] = i }
            }
        }
        // 若平台/噪声导致没有峰，取全局最大
        if (peaks.isEmpty()) peaks += p.indices.maxByOrNull { p[it] }!!

        // 95% HPD：按密度阈值收集 bin，再按峰归属成连续区间
        val target = 0.95
        val total = p.sum()
        val order = p.indices.sortedByDescending { p[it] }
        var acc = 0.0
        var cutoff = 0.0
        for (idx in order) {
            acc += p[idx]
            if (acc >= target * total) { cutoff = p[idx]; break }
        }
        val inside = BooleanArray(n) { p[it] > cutoff }
        // 与峰连通：把每个 inside 段归给最近的峰
        val segments = mutableListOf<IntRange>()
        var i = 0
        while (i < n) {
            if (inside[i]) {
                var j = i
                while (j + 1 < n && inside[j + 1]) j++
                segments += i..j
                i = j + 1
            } else i++
        }
        // 按峰把相邻段合并为 mode
        data class M(val peak: Int, var lo: Int, var hi: Int)
        val modes = peaks.map { pk ->
            val seg = segments.minByOrNull { seg -> minOf(abs(seg.first - pk), abs(seg.last - pk)) }
            val lo = seg?.first ?: pk
            val hi = seg?.last ?: pk
            M(pk, lo, hi)
        }.sortedBy { it.lo }

        return modes.mapIndexed { modeIdx, mm ->
            // 对该 mode 的连续覆盖做轻微扩展，确保段覆盖到峰
            var lo = minOf(mm.lo, mm.peak); var hi = maxOf(mm.hi, mm.peak)
            val terms = ArrayList<IntegralTerm>(hi - lo + 1)
            var prob = 0.0
            for (k in lo..hi) {
                val contrib = p[k] * d.binWidth
                prob += contrib
                terms += IntegralTerm(d.yearAt(k), p[k], d.binWidth, contrib)
            }
            DateInterval(
                id = "$resultId@int${modeIdx + 1}",
                loYear = d.yearAt(lo),
                hiYear = d.yearAt(hi),
                probability = prob,
                peakYear = d.yearAt(mm.peak),
                modeIndex = modeIdx + 1,
                integralTerms = terms,
                label = "${Era.label(d.yearAt(hi))} – ${Era.label(d.yearAt(lo))}"
            )
        }.sortedByDescending { it.probability }.mapIndexed { idx, iv ->
            // 稳定 id：按概率排序后仍保留 modeIndex，但展示 id 用稳定序号
            iv.copy(id = "$resultId@int${iv.modeIndex}")
        }
    }
}
