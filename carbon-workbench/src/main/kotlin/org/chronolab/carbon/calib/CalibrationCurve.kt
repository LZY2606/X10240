package org.chronolab.carbon.calib

import kotlinx.serialization.Serializable
import org.chronolab.carbon.model.CalendarEra
import kotlin.math.PI
import kotlin.math.ln
import kotlin.math.sin

/**
 * 离线合成校准曲线。不引用外部数据文件，函数式生成，因此“固定版本 + 固定采样网格”可完全重现。
 * calBP 越大越早。曲线以 5 年节点采样，校准引擎在节点之间做线性边界插值。
 *
 * wiggle 分量制造真实校准曲线中常见的平台/回折段，使某些测量得到双峰后验。
 */
@Serializable
data class CalCurvePoint(val calBP: Int, val radiocarbonBP: Double, val sigma: Double)

@Serializable
data class CalibrationCurve(
    val curveId: String,
    val version: String,
    val points: List<CalCurvePoint>,
    val description: String
) {
    val minCalBP: Int get() = points.minOf { it.calBP }
    val maxCalBP: Int get() = points.maxOf { it.calBP }

    /** 节点间线性插值；超出曲线域返回 null（调用方据此标记 OUTSIDE_CURVE_DOMAIN）。 */
    fun interp(calBP: Double): Pair<Double, Double>? {
        if (calBP < minCalBP || calBP > maxCalBP) return null
        var lo = 0
        var hi = points.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (points[mid].calBP <= calBP) lo = mid else hi = mid
        }
        val a = points[lo]
        val b = points[hi]
        if (a.calBP == b.calBP) return a.radiocarbonBP to a.sigma
        val f = (calBP - a.calBP) / (b.calBP - a.calBP)
        val c14 = a.radiocarbonBP + f * (b.radiocarbonBP - a.radiocarbonBP)
        val sigma = a.sigma + f * (b.sigma - a.sigma)
        return c14 to sigma
    }
}

object SyntheticCurves {
    /**
     * 单一 wiggle 项可在“平台 + 陡降”处对同一 14C 年龄产生两个日历解。
     * amp=62、period=106 经构造后，目标测量（~2050 14C BP）呈现至少两个分离后验峰。
     */
    private fun base(calBP: Double): Double =
        calBP - 28.0 * ln(1.0 + calBP / 700.0)

    private fun wiggle(calBP: Double): Double =
        62.0 * sin((calBP - 1700.0) * 2.0 * PI / 106.0)

    private fun sigma(calBP: Double): Double =
        14.0 + 5.0 * sin(calBP * 2.0 * PI / 430.0)

    fun v1(
        fromCalBP: Int = 1500,
        toCalBP: Int = 2600,
        knotEvery: Int = 5
    ): CalibrationCurve {
        require(toCalBP > fromCalBP)
        val pts = (fromCalBP..toCalBP step knotEvery).map { t ->
            CalCurvePoint(t, base(t.toDouble()) + wiggle(t.toDouble()), sigma(t.toDouble()))
        }
        return CalibrationCurve(
            curveId = CURVE_ID,
            version = "synthetic-intcal-like-v1",
            points = pts,
            description = "离线合成 wiggle 校准曲线 v1（构造性多峰，5 年节点）"
        )
    }

    /**
     * v2 仅在一个局部回折窗口 [1880,1935] calBP 内加入一个额外 wiggle，
     * 用于“曲线升版只使依赖该窗口的校准过期”。窗口外逐点一致。
     */
    fun v2(
        fromCalBP: Int = 1500,
        toCalBP: Int = 2600,
        knotEvery: Int = 5
    ): CalibrationCurve {
        val pts = (fromCalBP..toCalBP step knotEvery).map { t ->
            val bump = if (t in 1880..1935)
                46.0 * sin((t - 1880) * 2.0 * PI / 55.0) else 0.0
            CalCurvePoint(
                t,
                base(t.toDouble()) + wiggle(t.toDouble()) + bump,
                sigma(t.toDouble())
            )
        }
        return CalibrationCurve(
            curveId = CURVE_ID,
            version = "synthetic-intcal-like-v2",
            points = pts,
            description = "离线合成 wiggle 校准曲线 v2（仅 1880–1935 calBP 局部修订）"
        )
    }

    const val CURVE_ID = "curve-offline-wiggle"
}

fun main() {
    val c = SyntheticCurves.v1()
    val near = c.points.filter { it.calBP in 2000..2200 }
    near.forEach { println("${it.calBP} -> ${"%.1f".format(it.radiocarbonBP)} ${CalendarEra.label(it.calBP.toDouble())}") }
}
