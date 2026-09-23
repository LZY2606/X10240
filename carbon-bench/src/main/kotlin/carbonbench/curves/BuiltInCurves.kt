package carbonbench.curves

import carbonbench.domain.CalibrationCurve
import carbonbench.domain.CurvePoint
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 离线内置合成校准曲线（不依赖公网）。故意构造“wiggle 列车”，
 * 使若干放射性碳年龄在同一 sigma 带宽内与曲线多次相交 => 多峰。
 * 两个版本在 2400–2700 cal BP 附近形状不同，用于曲线升版对比与边界插值。
 */
object BuiltInCurves {

    const val CURVE_ID = "CBP-WIGGLE"

    val v1: CalibrationCurve = build(
        version = "1",
        spacing = 5.0,
        sourceRef = "carbon-bench offline synthetic curve CBP-WIGGLE v1 (seed=0)",
        bumpV2 = false
    )

    val v2: CalibrationCurve = build(
        version = "2",
        spacing = 2.5,
        sourceRef = "carbon-bench offline synthetic curve CBP-WIGGLE v2 (seed=0)",
        bumpV2 = true
    )

    fun get(version: String): CalibrationCurve = when (version) {
        "1" -> v1
        "2" -> v2
        else -> error("unknown curve version: $version")
    }

    fun exists(version: String): Boolean = version == "1" || version == "2"

    private fun c14AgeV1(t: Double): Double {
        val wig = 70.0 * sin(2.0 * PI * t / 430.0) +
                34.0 * sin(2.0 * PI * t / 255.0 + 1.3) +
                12.0 * sin(2.0 * PI * t / 130.0 + 0.4)
        return t + wig
    }

    private fun c14AgeV2(t: Double): Double {
        var age = c14AgeV1(t)
        // 2400–2700 cal BP 区间加入一个额外摆动并局部抬高，改变多峰结构
        if (t in 2400.0..2700.0) {
            val phase = (t - 2400.0) / 300.0
            val envelope = sin(PI * phase)
            age += envelope * (46.0 * sin(2.0 * PI * (t - 2400.0) / 120.0) + 24.0)
        }
        return age
    }

    private fun sigma(t: Double, v2: Boolean): Double {
        val base = 18.0 + 0.004 * t
        val flicker = 2.2 * sin(2.0 * PI * t / 310.0 + 0.7)
        var s = base + flicker
        if (v2 && t in 2400.0..2700.0) {
            val phase = (t - 2400.0) / 300.0
            s += 3.5 * kotlin.math.sin(PI * phase)
        }
        return sqrt(s * s)
    }

    private fun build(version: String, spacing: Double, sourceRef: String, bumpV2: Boolean): CalibrationCurve {
        val min = 0.0
        val max = 4000.0
        val points = ArrayList<CurvePoint>()
        var t = min
        while (t <= max + 1e-9) {
            val age = if (bumpV2) c14AgeV2(t) else c14AgeV1(t)
            points.add(CurvePoint(t, age, sigma(t, bumpV2)))
            t += spacing
        }
        return CalibrationCurve(
            curveId = CURVE_ID,
            version = version,
            minCalBP = min,
            maxCalBP = max,
            spacing = spacing,
            sourceRef = sourceRef,
            points = points
        )
    }
}
