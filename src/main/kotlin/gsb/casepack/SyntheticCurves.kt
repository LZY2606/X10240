package gsb.casepack

import gsb.calc.sha256Hex
import gsb.domain.CalibrationCurve
import gsb.domain.CurveKnot
import gsb.domain.SourceRef

/**
 * 离线自带的合成校准曲线（SIMC = Synthetic IntCal-style Marine/Calibration curve）。
 * 确定性：固定 builder 版本 + 固定控制点，任何机器逐字节相同。
 *
 * 专门构造的区段（验收用）：
 *  - 多峰摆动带 2380..2620 calBP（两次反向摆动 ⇒ 多峰后验）
 *  - 并列平台 760..1080 calBP（三段同高度平台 ⇒ 并列日历区间）
 *  - BCE/CE 边界 1880..2020 calBP（峰跨 1 BCE/1 CE）
 *  - 平坦储库检验带 3200..3450 calBP
 * v2 仅改动多峰摆动带（局部升版，演示“只有依赖该区段的校准过期”）。
 */
object SyntheticCurves {
    const val BUILDER_VERSION = "simc-builder-1.0.0"

    private data class Pt(val calBp: Double, val radio: Double)

    private val baseLine: (Double) -> Double = { it } // 斜率 1：radio=calBP

    // 控制点相对基线的偏移（calBP, radioOffset）
    private val wiggleV1 = listOf(
        2360.0 to 0.0, 2380.0 to 0.0, 2400.0 to +42.0, 2420.0 to +78.0,
        2440.0 to +40.0, 2460.0 to -18.0, 2480.0 to -70.0, 2500.0 to -96.0,
        2520.0 to -72.0, 2540.0 to -28.0, 2560.0 to +18.0, 2580.0 to +58.0,
        2600.0 to +40.0, 2620.0 to 0.0, 2640.0 to 0.0
    )
    private val wiggleV2 = listOf(
        2360.0 to 0.0, 2380.0 to 0.0, 2400.0 to +24.0, 2420.0 to +52.0,
        2440.0 to +18.0, 2460.0 to -34.0, 2480.0 to -88.0, 2500.0 to -118.0,
        2520.0 to -94.0, 2540.0 to -50.0, 2560.0 to -4.0, 2580.0 to +34.0,
        2600.0 to +18.0, 2620.0 to 0.0, 2640.0 to 0.0
    )

    // 并列平台：三段 radio 完全相同的平台，用浅谷相连
    private val tiePlateau = listOf(
        760.0 to 0.0,
        800.0 to +20.0, 820.0 to +20.0, 860.0 to +20.0,
        900.0 to 0.0,
        940.0 to +20.0, 980.0 to +20.0, 1000.0 to +20.0,
        1040.0 to +16.0,
        // 第三平台与第二平台通过浅谷相连，且同高度
        1050.0 to +20.0, 1060.0 to +20.0, 1080.0 to +20.0,
        1100.0 to 0.0
    )

    // BCE/CE 边界小摆动（峰顶恰在 1950 calBP）
    private val anchorWiggle = listOf(
        1880.0 to 0.0, 1905.0 to -26.0, 1930.0 to -40.0, 1950.0 to -40.0,
        1970.0 to -40.0, 1995.0 to -26.0, 2020.0 to 0.0
    )

    // 平坦海洋带：radio 恒定 3330
    private val marineFlat = listOf(
        3180.0 to 0.0, 3200.0 to 0.0, 3220.0 to 0.0, 3430.0 to 0.0,
        3450.0 to 0.0, 3470.0 to 0.0
    )

    fun build(version: String, source: SourceRef): CalibrationCurve {
        val v = when (version) {
            "2026-v1" -> wiggleV1
            "2026-v2" -> wiggleV2
            else -> error("unknown SIMC version $version")
        }
        val zones = listOf(v, tiePlateau, anchorWiggle, marineFlat)
        val knots = linkedMapOf<Int, Double>()
        val maxBp = 3500
        for (bp in 0..maxBp step 5) knots[bp] = baseLine(bp.toDouble())
        for (zone in zones) {
            for ((bp, off) in zone) {
                knots[bp.toInt()] = bp + off
            }
            for (p in 0 until zone.size - 1) {
                val (x1, y1) = zone[p]; val (x2, y2) = zone[p + 1]
                var bp = (kotlin.math.ceil(x1 / 5.0) * 5).toInt()
                while (bp < x2) {
                    val t = (bp - x1) / (x2 - x1)
                    val off = y1 + t * (y2 - y1)
                    knots[bp] = bp + off
                    bp += 5
                }
            }
        }
        val list = knots.toSortedMap().map { CurveKnot(it.key.toDouble(), it.value) }
        val fp = sha256Hex(
            BUILDER_VERSION + "|" + version + "|" + list.joinToString(",") { "${it.calBp}:${it.radioAge}" }
        )
        return CalibrationCurve(
            id = "SIMC-$version", code = "SIMC", version = version,
            name = "Synthetic IntCal-style curve $version",
            knots = list, builtin = true, builderVersion = BUILDER_VERSION,
            fingerprintSha256 = fp, source = source,
            parentId = if (version == "2026-v2") "SIMC-2026-v1" else null
        )
    }
}
