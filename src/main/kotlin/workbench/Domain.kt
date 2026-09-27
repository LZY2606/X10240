package workbench

import kotlinx.serialization.Serializable
import java.security.MessageDigest
import kotlin.math.*

const val ALGO_CALIBRATION = "calib-hpd-1.0.0"
const val ALGO_SEQUENCE = "seq-order-1.0.0"
const val ALGO_INTERVAL_EVIDENCE = "interval-evidence-1.0.0"

fun sha256(text: String): String =
    MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
        .joinToString("") { "%02x".format(it) }

fun fingerprintOf(vararg parts: String): String = sha256(parts.joinToString("|"))

/** 日历纪元换算：天文纪年 0 = 1 BCE，-1 = 2 BCE；calBP 相对 1950 CE。 */
object Era {
    fun bpToAstronomical(bp: Double): Int = (1950.0 - bp).roundToInt()
    fun astronomicalLabel(astro: Int): String =
        if (astro >= 1) "$astro CE" else "${1 - astro} BCE"
    fun bpLabel(bp: Double): String = astronomicalLabel(bpToAstronomical(bp))
    fun bpToAstronomicalExact(bp: Double): Double = 1950.0 - bp
}

@Serializable
data class CurvePoint(val calBp: Int, val c14Age: Double, val sigma: Double)

@Serializable
data class CurveSample(val c14Age: Double, val sigma: Double, val clamped: Boolean)

@Serializable
data class CurveData(val points: List<CurvePoint>) {
    val sorted: List<CurvePoint> get() = points.sortedBy { it.calBp }

    /** 线性插值；超出曲线端点时钳制到端点并标记 clamped（边界插值诊断）。 */
    fun sample(calBp: Double): CurveSample {
        val pts = sorted
        require(pts.isNotEmpty()) { "curve has no points" }
        if (calBp <= pts.first().calBp)
            return CurveSample(pts.first().c14Age, pts.first().sigma, clamped = calBp < pts.first().calBp)
        if (calBp >= pts.last().calBp)
            return CurveSample(pts.last().c14Age, pts.last().sigma, clamped = calBp > pts.last().calBp)
        var lo = 0
        var hi = pts.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) / 2
            if (pts[mid].calBp <= calBp) lo = mid else hi = mid
        }
        val a = pts[lo]; val b = pts[hi]
        val f = (calBp - a.calBp) / (b.calBp - a.calBp).toDouble()
        return CurveSample(
            a.c14Age + f * (b.c14Age - a.c14Age),
            a.sigma + f * (b.sigma - a.sigma),
            clamped = false
        )
    }
}

@Serializable
data class Peak(val bp: Int, val ceLabel: String, val density: Double)

@Serializable
data class HpdInterval(
    val level: Double,
    val fromBp: Int,
    val toBp: Int,
    val fromLabel: String,
    val toLabel: String,
    val mass: Double,
    val threshold: Double,
    val cellCount: Int
)

@Serializable
data class CalibratedDistribution(
    val gridFromBp: Int,
    val gridToBp: Int,
    val step: Int,
    val density: List<Double>,
    val normalization: Double,
    val clampedCells: Int,
    val peaks: List<Peak>,
    val intervals: List<HpdInterval>
) {
    fun massBetween(fromBp: Int, toBp: Int): Double {
        val lo = maxOf(fromBp, gridFromBp)
        val hi = minOf(toBp, gridToBp)
        if (hi < lo) return 0.0
        var sum = 0.0
        var t = lo
        while (t <= hi) { sum += density[t - gridFromBp]; t += step }
        return sum
    }
}

object Calibration {
    fun normalPdf(x: Double, mu: Double, sd: Double): Double {
        val z = (x - mu) / sd
        return exp(-0.5 * z * z) / (sd * sqrt(2.0 * PI))
    }

    /**
     * 在固定曲线版本下对单个测量做校准。输出多峰密度（不归并成平均年），
     * 归一化常数随结果保存，保证同版本同参数下可重现。
     */
    fun calibrate(
        measBp: Double, measSigma: Double,
        curve: CurveData,
        offsetMean: Double, offsetSigma: Double,
        gridFromBp: Int, gridToBp: Int,
        levels: List<Double> = listOf(0.682, 0.954)
    ): CalibratedDistribution {
        val n = gridToBp - gridFromBp + 1
        val raw = DoubleArray(n)
        var clamped = 0
        for (i in 0 until n) {
            val t = gridFromBp + i
            val s = curve.sample(t.toDouble())
            if (s.clamped) clamped++
            val expected = s.c14Age + offsetMean
            val sd = sqrt(measSigma * measSigma + s.sigma * s.sigma + offsetSigma * offsetSigma)
            raw[i] = normalPdf(measBp, expected, sd)
        }
        val z = raw.sum()
        require(z > 0.0) { "calibration likelihood underflow: no support in grid" }
        val density = raw.map { it / z }
        val peaks = mutableListOf<Peak>()
        for (i in 1 until n - 1) {
            if (density[i] > density[i - 1] && density[i] >= density[i + 1] && density[i] > 0.0) {
                val bp = gridFromBp + i
                peaks.add(Peak(bp, Era.bpLabel(bp.toDouble()), density[i]))
            }
        }
        val intervals = levels.flatMap { level -> hpd(density, gridFromBp, level) }
        return CalibratedDistribution(gridFromBp, gridToBp, 1, density, z, clamped, peaks, intervals)
    }

    /** 最高密度区域：按密度降序累积到 level，阈值以上的连续段为一个区间。 */
    fun hpd(density: List<Double>, gridFromBp: Int, level: Double): List<HpdInterval> {
        val order = density.indices.sortedByDescending { density[it] }
        var acc = 0.0
        var threshold = 0.0
        for (i in order) {
            acc += density[i]
            threshold = density[i]
            if (acc >= level) break
        }
        val inside = BooleanArray(density.size) { density[it] >= threshold && density[it] > 0.0 }
        val out = mutableListOf<HpdInterval>()
        var i = 0
        while (i < density.size) {
            if (inside[i]) {
                var j = i
                var mass = 0.0
                while (j < density.size && inside[j]) { mass += density[j]; j++ }
                val fromBp = gridFromBp + i
                val toBp = gridFromBp + j - 1
                out.add(
                    HpdInterval(
                        level, fromBp, toBp,
                        Era.bpLabel(fromBp.toDouble()), Era.bpLabel(toBp.toDouble()),
                        mass, threshold, j - i
                    )
                )
                i = j
            } else i++
        }
        return out.sortedBy { it.fromBp }
    }

    /** 区间积分证据：展开任一区间概率时可读回的机器可读证据。 */
    fun intervalEvidence(dist: CalibratedDistribution, interval: HpdInterval): Map<String, String> {
        val cells = mutableListOf<Map<String, String>>()
        var t = interval.fromBp
        val top = mutableListOf<Triple<Int, Double, Double>>() // bp, density, raw
        while (t <= interval.toBp) {
            val d = dist.density[t - dist.gridFromBp]
            top.add(Triple(t, d, d * dist.normalization))
            t += dist.step
        }
        top.sortByDescending { it.second }
        for ((bp, d, rawL) in top.take(10)) {
            cells.add(mapOf(
                "calBp" to bp.toString(),
                "ceLabel" to Era.bpLabel(bp.toDouble()),
                "density" to "%.10e".format(d),
                "rawLikelihood" to "%.10e".format(rawL)
            ))
        }
        return mapOf(
            "algorithm" to ALGO_INTERVAL_EVIDENCE,
            "level" to interval.level.toString(),
            "rangeBp" to "${interval.fromBp}..${interval.toBp}",
            "rangeCe" to "${interval.toLabel} .. ${interval.fromLabel}",
            "mass" to "%.12f".format(interval.mass),
            "threshold" to "%.10e".format(interval.threshold),
            "cellCount" to interval.cellCount.toString(),
            "gridStep" to dist.step.toString(),
            "normalization" to "%.10e".format(dist.normalization),
            "recomputedMass" to "%.12f".format(dist.massBetween(interval.fromBp, interval.toBp)),
            "topCells" to cells.toString()
        )
    }
}

@Serializable
data class SequenceCandidate(val kind: String, val detail: String)

@Serializable
data class SequencePairReport(
    val olderId: String,
    val youngerId: String,
    val pOrder: Double,
    val conflict: Boolean,
    val candidates: List<SequenceCandidate>
)

@Serializable
data class SequenceReport(
    val pairs: List<SequencePairReport>,
    val conflicts: Int
)

object Sequence {
    /** P(older 比 younger 更老)：两个独立后验在同一网格上的离散卷积。 */
    fun pOlderThan(older: CalibratedDistribution, younger: CalibratedDistribution): Double {
        require(older.gridFromBp == younger.gridFromBp && older.gridToBp == younger.gridToBp) {
            "distributions must share a grid"
        }
        val n = older.density.size
        val cumYounger = DoubleArray(n)
        var acc = 0.0
        for (i in 0 until n) { acc += younger.density[i]; cumYounger[i] = acc }
        var p = 0.0
        for (i in 0 until n) {
            val youngerBelow = if (i == 0) 0.0 else cumYounger[i - 1]
            p += older.density[i] * (1.0 - youngerBelow)
        }
        return p
    }

    /**
     * 层序冲突产生候选调整，不改写测量本身。
     */
    fun check(
        dists: Map<String, CalibratedDistribution>,
        constraints: List<Pair<String, String>>
    ): SequenceReport {
        val pairs = constraints.map { (olderId, youngerId) ->
            val older = dists.getValue(olderId)
            val younger = dists.getValue(youngerId)
            val p = pOlderThan(older, younger)
            val conflict = p < 0.5
            val candidates = mutableListOf<SequenceCandidate>()
            if (conflict) {
                candidates.add(SequenceCandidate("exclude-measurement",
                    "排除 $olderId 后约束可满足（测量值保留，仅标记排除决定）"))
                candidates.add(SequenceCandidate("exclude-measurement",
                    "排除 $youngerId 后约束可满足（测量值保留，仅标记排除决定）"))
                if (p < 0.1) candidates.add(SequenceCandidate("reverse-constraint",
                    "后验强烈支持反向顺序 (p=%.4f)，建议复核层序记录".format(p)))
                candidates.add(SequenceCandidate("inflate-uncertainty",
                    "对两个测量 sigma ×2 重校作为候选参数调整，原测量不变"))
            }
            SequencePairReport(olderId, youngerId, p, conflict, candidates)
        }
        return SequenceReport(pairs, pairs.count { it.conflict })
    }
}
