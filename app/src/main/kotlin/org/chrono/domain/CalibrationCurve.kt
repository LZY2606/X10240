package org.chrono.domain

import kotlinx.serialization.Serializable
import kotlin.math.max

/**
 * 校准曲线固定版本：年际点可能不等距，mu 与 sigma 均做分段线性插值。
 * 曲线点是不可变原始材料；升版通过新版本 + 父版本关系表达。
 */
@Serializable
data class CurvePoint(
    val calYearAstro: Int,
    val c14Age: Double,   // 14C yr BP
    val sigma: Double     // 14C yr
)

@Serializable
data class CalibrationCurve(
    val curveId: String,
    val version: String,
    val parentVersion: String? = null,
    val name: String,
    val points: List<CurvePoint>,
    val sourceRef: String,
    /** 算法层使用的归一化网格步长（cal yr），固定版本下确定性可重现。 */
    val gridStep: Int = 1
) {
    init {
        require(points.size >= 2) { "curve $curveId@$version needs >= 2 points" }
        require(points.zipWithNext().all { it.first.calYearAstro < it.second.calYearAstro }) {
            "curve points must be strictly increasing in calendar year"
        }
        require(points.all { it.sigma >= 0.0 }) { "curve sigma must be non-negative" }
    }

    val minCal: Int get() = points.first().calYearAstro
    val maxCal: Int get() = points.last().calYearAstro

    fun mu(cal: Double): Double = interp(cal) { it.c14Age }
    fun sigma(cal: Double): Double = interp(cal) { it.sigma }

    fun covers(cal: Double): Boolean = cal in minCal.toDouble()..maxCal.toDouble()

    private inline fun interp(cal: Double, pick: (CurvePoint) -> Double): Double {
        if (cal <= points.first().calYearAstro) return pick(points.first())
        if (cal >= points.last().calYearAstro) return pick(points.last())
        var lo = 0
        var hi = points.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (points[mid].calYearAstro <= cal) lo = mid else hi = mid
        }
        val a = points[lo]
        val b = points[hi]
        val span = (b.calYearAstro - a.calYearAstro).toDouble()
        val f = if (span == 0.0) 0.0 else (cal - a.calYearAstro) / span
        return pick(a) + f * (pick(b) - pick(a))
    }

    /**
     * 内容指纹：仅依赖固定版本的点数据与网格设置。派生结果用它判断「局部输入变化」。
     */
    fun fingerprint(): String {
        val h = FNV1a()
        h.update(curveId); h.update(version)
        h.update(gridStep.toString())
        for (p in points) {
            h.update(p.calYearAstro.toString())
            h.update(trimNum(p.c14Age)); h.update(trimNum(p.sigma))
        }
        return h.hex()
    }

    /** 与另一版本逐点比较，返回发生变化的日历年区间（用于局部过期）。 */
    fun changedCalendarRange(other: CalibrationCurve): IntRange? {
        val lo = max(minCal, other.minCal)
        val hi = minOf(maxCal, other.maxCal)
        var from: Int? = null
        var to: Int? = null
        var y = lo
        while (y <= hi) {
            val d = max(kotlin.math.abs(mu(y.toDouble()) - other.mu(y.toDouble())),
                kotlin.math.abs(sigma(y.toDouble()) - other.sigma(y.toDouble())))
            if (d > 1e-9) { if (from == null) from = y; to = y }
            y++
        }
        return if (from == null) null else from..(to ?: from)
    }
}

internal fun trimNum(d: Double): String {
    val r = Math.round(d * 1000.0).toDouble() / 1000.0
    return if (r == r.toLong().toDouble()) r.toLong().toString() else r.toString()
}

class FNV1a {
    private var h = -0x7ee3623bL // 0xcbf29ce484222325
    fun update(s: String) {
        for (c in s) {
            h = h xor c.code.toLong()
            h *= 0x100000001b3L
        }
    }
    fun hex(): String = java.lang.Long.toUnsignedString(h, 16).padStart(16, '0')
}
