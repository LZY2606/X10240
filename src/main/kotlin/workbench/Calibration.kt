package workbench

import java.security.MessageDigest
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sqrt

object Calibration {
    const val ALGO_VERSION = "multipeak-cal-1.0.0"
    const val HPD_MASS = 0.9545
    const val GRID_STEP = 1.0
    const val BOUNDARY_RULE = "clamp-endpoint+linear-interp-v1"

    fun sha256(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    fun eraLabel(calBp: Double, era: String): String {
        if (era == "CAL_BP") return "${calBp.roundToInt()} calBP"
        val y = 1950.0 - calBp
        return if (y >= 1.0) "CE ${y.roundToInt()}" else "BCE ${(1.0 - y).roundToInt()}"
    }

    /** 曲线采样：区间内线性插值；端点外按端点值截断（boundary=true 表示发生截断）。 */
    fun sampleCurve(points: List<CurvePoint>, t: Double): Triple<Double, Double, Boolean> {
        val first = points.first()
        val last = points.last()
        if (t <= first.calBp) return Triple(first.c14Age, first.sigma, t < first.calBp)
        if (t >= last.calBp) return Triple(last.c14Age, last.sigma, t > last.calBp)
        var lo = 0
        var hi = points.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) / 2
            if (points[mid].calBp <= t) lo = mid else hi = mid
        }
        val a = points[lo]
        val b = points[hi]
        val f = (t - a.calBp) / (b.calBp - a.calBp)
        return Triple(a.c14Age + f * (b.c14Age - a.c14Age), a.sigma + f * (b.sigma - a.sigma), false)
    }

    fun fingerprint(m: MeasurementV, curve: CurveV, offset: OffsetV?, era: String): String =
        sha256(
            listOf(
                ALGO_VERSION,
                "${m.measurementId}@${m.version}", m.c14AgeBp.toString(), m.stdDev.toString(),
                "${curve.curveId}@${curve.version}",
                offset?.let { "${it.offsetId}@${it.version}:${it.meanYears}:${it.sigmaYears}" } ?: "no-offset",
                era
            ).joinToString("|")
        )

    fun calibrate(id: String, m: MeasurementV, curve: CurveV, offset: OffsetV?, era: String): CalibrationResult {
        val fp = fingerprint(m, curve, offset, era)
        val base = CalibrationResult(
            id = id, measurementId = m.measurementId, measurementVersion = m.version,
            curveId = curve.curveId, curveVersion = curve.version,
            offsetId = offset?.offsetId, offsetVersion = offset?.version,
            eraSystem = era, algoVersion = ALGO_VERSION, fingerprint = fp, status = "OK"
        )
        val std = m.stdDev
        if (std == null) {
            return base.copy(
                status = "UNKNOWN",
                diagnostics = listOf(
                    "标准差缺失，无法完成归一化：${m.stdDevNote.ifBlank { "实验室未报告" }}；" +
                        "原始测量保留在源事件 ${m.id}，未为绘图丢弃任何字段"
                )
            )
        }
        val offMean = offset?.meanYears ?: 0.0
        val offSigma = offset?.sigmaYears ?: 0.0
        val rEff = m.c14AgeBp - offMean
        val sEff = sqrt(std * std + offSigma * offSigma)
        val start = curve.points.first().calBp
        val end = curve.points.last().calBp
        val n = ((end - start) / GRID_STEP).roundToInt() + 1
        val raw = DoubleArray(n)
        for (i in 0 until n) {
            val t = start + i * GRID_STEP
            val (c14, cs, _) = sampleCurve(curve.points, t)
            val sTot = sqrt(sEff * sEff + cs * cs)
            val z = (rEff - c14) / sTot
            raw[i] = exp(-0.5 * z * z)
        }
        val norm = raw.sum() * GRID_STEP
        val density = raw.map { it / norm }
        val maxD = density.max()
        // 边界触检：端点密度不可忽略 => HPD 区域被曲线端点截断
        val boundaryTouched = density.first() > 1e-4 * maxD || density.last() > 1e-4 * maxD
        // HPD 阈值
        val sorted = density.sortedDescending()
        var acc = 0.0
        var theta = 0.0
        for (d in sorted) {
            acc += d * GRID_STEP
            theta = d
            if (acc >= HPD_MASS) break
        }
        val peaks = mutableListOf<CalPeak>()
        val evidence = mutableMapOf<String, IntegrationEvidence>()
        var i = 0
        var idx = 0
        while (i < n) {
            if (density[i] >= theta && density[i] > 0.0) {
                var j = i
                var mass = 0.0
                while (j < n && density[j] >= theta) {
                    mass += density[j] * GRID_STEP
                    j++
                }
                val from = start + i * GRID_STEP
                val to = start + (j - 1) * GRID_STEP
                peaks.add(CalPeak(idx, from, to, mass, eraLabel(from, era), eraLabel(to, era)))
                evidence["p$idx"] = IntegrationEvidence(
                    gridStep = GRID_STEP, gridStart = start, gridEnd = end,
                    normConstant = norm, intervalMass = mass, pointsInInterval = j - i,
                    boundaryRule = BOUNDARY_RULE + if (boundaryTouched) ";boundary-clamped" else ";interior",
                    algoVersion = ALGO_VERSION
                )
                idx++
                i = j
            } else {
                i++
            }
        }
        val diags = mutableListOf<String>()
        if (boundaryTouched) diags.add("积分区间触及曲线端点，按 $BOUNDARY_RULE 在边界截断；端点外密度按端点值处理")
        return base.copy(
            diagnostics = diags, peaks = peaks, evidence = evidence,
            gridStart = start, gridEnd = end, gridStep = GRID_STEP, density = density
        )
    }
}
