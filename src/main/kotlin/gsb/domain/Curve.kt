package gsb.domain

import kotlinx.serialization.Serializable

/**
 * 校准曲线：固定版本下的 (calBP 年, 14C BP, sigma) 控制点。
 * 控制点之间做分段线性插值；范围外请求给出明确边界诊断而非外推。
 */
@Serializable
data class CurveVersion(
    val id: String,
    val version: String,
    val name: String,
    val knots: List<CurveKnot>,
    val source: SourceRef
) {
    val minBp get() = knots.minOf { it.bpYear }
    val maxBp get() = knots.maxOf { it.bpYear }

    /** 分段线性插值，返回 null 表示越界（边界诊断）。 */
    fun muAt(bpYear: Double): Double? {
        if (bpYear > maxBp.toDouble() || bpYear < minBp.toDouble()) return null
        var lo = 0
        var hi = knots.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (knots[mid].bpYear >= bpYear) lo = mid else hi = mid
        }
        val a = knots[lo]; val b = knots[hi]
        if (lo == hi || a.bpYear == b.bpYear) return a.mu14c
        val t = (bpYear - a.bpYear) / (b.bpYear - a.bpYear)
        return a.mu14c + t * (b.mu14c - a.mu14c)
    }

    fun sigmaAt(bpYear: Double): Double? {
        if (bpYear > maxBp.toDouble() || bpYear < minBp.toDouble()) return null
        var lo = 0
        var hi = knots.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (knots[mid].bpYear >= bpYear) lo = mid else hi = mid
        }
        val a = knots[lo]; val b = knots[hi]
        if (lo == hi) return a.sigma14c
        val t = (bpYear - a.bpYear) / (b.bpYear - a.bpYear)
        return a.sigma14c + t * (b.sigma14c - a.sigma14c)
    }

    /** 落在哪两个控制点之间（边界插值证据）。 */
    fun bracket(bpYear: Double): Pair<CurveKnot, CurveKnot>? {
        if (bpYear > maxBp.toDouble() || bpYear < minBp.toDouble()) return null
        if (bpYear >= knots.first().bpYear.toDouble()) return knots.first() to knots.first()
        if (bpYear <= knots.last().bpYear.toDouble()) return knots.last() to knots.last()
        for (i in 0 until knots.size - 1) {
            val a = knots[i]; val b = knots[i + 1]
            if (bpYear in b.bpYear.toDouble()..a.bpYear.toDouble()) return a to b
        }
        return null
    }
}

@Serializable
data class CurveKnot(val bpYear: Int, val mu14c: Double, val sigma14c: Double)
