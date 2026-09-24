package chrono

import kotlin.math.roundToInt

/**
 * cal BP <-> 日历年显示。内部一律 cal BP（1950=0，越大越早）。
 * 显示采用天文计年：1 BCE = 0，2 BCE = -1 ……，BCE/CE 跨界在同一条轴上呈现。
 */
object CalTime {
    const val REFERENCE_YEAR = 1950

    /** cal BP -> 天文年（1 BCE 表示为 0，2 BCE 为 -1）。 */
    fun calbpToAstronomicalYear(calbp: Double): Int = REFERENCE_YEAR - calbp.roundToInt()

    /** 天文年 -> 显示标签，如 "55 BCE"、"1 BCE"、"1 CE"、"1950 CE"。 */
    fun formatYear(calbp: Double): String {
        val astro = calbpToAstronomicalYear(calbp)
        return when {
            astro <= 0 -> "${1 - astro} BCE"
            else -> "$astro CE"
        }
    }

    fun formatRange(fromCalbp: Double, toCalbp: Double): String =
        "${formatYear(toCalbp)} – ${formatYear(fromCalbp)}"

    /** 显示标签 + 原始 cal BP 数值，保证坐标基准可见。 */
    fun labeled(calbp: Double): String = "${formatYear(calbp)} (${"%.1f".format(calbp)} cal BP)"
}
