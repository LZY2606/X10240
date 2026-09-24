package org.gsb.carbon.model

import kotlin.math.roundToInt

/**
 * 日历坐标基准。
 *
 * 内部统一坐标是 cal BP：距公元 1950 年（BP_DATUM）的日历年数，单位「cal yr BP」。
 * 显示层使用 BCE/CE（天文计年：1 CE 之前是 0 = 1 BCE，-1 = 2 BCE）。
 * 所有换算都是纯函数，保证固定曲线版本下可重现。
 */
object CalendarScale {
    const val BP_DATUM_CE_YEAR: Int = 1950

    /** cal BP -> 天文年（1=1 CE，0=1 BCE，-1=2 BCE） */
    fun bpToAstronomicalYear(bp: Double): Double = BP_DATUM_CE_YEAR - bp

    /** 天文年 -> cal BP */
    fun astronomicalYearToBp(year: Double): Double = BP_DATUM_CE_YEAR - year

    data class EraLabel(val astronomicalYear: Double, val text: String, val side: Side) {
        enum class Side { BCE, CE, BOUNDARY }
    }

    /**
     * 把 cal BP 坐标格式化为 BCE/CE 标签。
     * cal BP = 1950 对应 1 CE 与 1 BCE 之间的跨界点（0.0），标记为 BOUNDARY。
     */
    fun bpToEraLabel(bp: Double): EraLabel {
        val y = bpToAstronomicalYear(bp)
        return when {
            y > 0.0 -> EraLabel(y, formatText(y, ce = true), EraLabel.Side.CE)
            y < 0.0 -> EraLabel(y, formatText(y, ce = false), EraLabel.Side.BCE)
            else -> EraLabel(0.0, "1 BCE/1 CE 跨界点 (0)", EraLabel.Side.BOUNDARY)
        }
    }

    private fun formatText(astronomicalYear: Double, ce: Boolean): String {
        val rounded = astronomicalYear.roundToInt()
        return if (ce) {
            "${rounded} CE"
        } else {
            val bceYear = (1 - rounded)
            "${bceYear} BCE"
        }
    }

    /** 区间是否跨越 BCE/CE 边界（区间以 cal BP 表示，[hiBp] 较老） */
    fun crossesBceCeBoundary(loBp: Double, hiBp: Double): Boolean =
        loBp < BP_DATUM_CE_YEAR.toDouble() && hiBp > BP_DATUM_CE_YEAR.toDouble()
}
