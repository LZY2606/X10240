package gsb

import kotlin.math.abs
import kotlin.math.sin

/** 曲线上的一个节点（坐标基准：cal BP，1950 为 0）。 */
data class CurvePoint(val calBp: Double, val c14Age: Double, val sigma: Double)

/** 固定版本的校准曲线：5 年节点间距，节点间线性插值，可重现。 */
class CalibrationCurve(val curveId: String, val version: String, val points: List<CurvePoint>) {
    val minBp: Double = points.first().calBp
    val maxBp: Double = points.last().calBp

    /** 节点间线性插值；超出表格范围返回 null（边界截断由调用方处理）。 */
    fun interp(calBp: Double): Pair<Double, Double>? {
        if (calBp < minBp || calBp > maxBp) return null
        var lo = 0
        var hi = points.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) / 2
            if (points[mid].calBp <= calBp) lo = mid else hi = mid
        }
        val a = points[lo]
        val b = points[hi]
        if (b.calBp == a.calBp) return a.c14Age to a.sigma
        val f = (calBp - a.calBp) / (b.calBp - a.calBp)
        return (a.c14Age + f * (b.c14Age - a.c14Age)) to (a.sigma + f * (b.sigma - a.sigma))
    }
}

/** 离线内置曲线注册表：确定性的类 IntCal 合成曲线，两个版本用于“曲线升版”情形。 */
object Curves {
    const val CURVE_ID = "INTCAL-SYN"
    const val MIN_BP = 0.0
    const val MAX_BP = 5000.0
    const val NODE_STEP = 5.0

    private fun base2020(t: Double): Double =
        4400.0 - 0.82 * t + 60.0 * sin(t / 97.0) + 35.0 * sin(t / 31.0 + 1.3) + 18.0 * sin(t / 11.0 + 0.5)

    private fun base2024(t: Double): Double = base2020(t) + 12.0 * sin(t / 140.0 + 0.7) + 4.0

    private fun sigma(t: Double): Double = 15.0 + 5.0 * abs(sin(t / 53.0))

    val versions: Map<String, (Double) -> Double> = mapOf("2020" to ::base2020, "2024" to ::base2024)

    fun get(curveId: String, version: String): CalibrationCurve? {
        if (curveId != CURVE_ID) return null
        val fn = versions[version] ?: return null
        val pts = ArrayList<CurvePoint>()
        var t = MIN_BP
        while (t <= MAX_BP + 1e-9) {
            pts.add(CurvePoint(t, fn(t), sigma(t)))
            t += NODE_STEP
        }
        return CalibrationCurve(curveId, version, pts)
    }
}
