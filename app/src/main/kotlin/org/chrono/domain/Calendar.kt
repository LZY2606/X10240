package org.chrono.domain

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.round

/**
 * 年代系统与单位。
 *
 * 内部日历坐标统一使用「天文纪年整数年」(proleptic astronomical year)：
 *   1 BCE = 0, 2 BCE = -1, ... ；不存在 0 年间隔问题。
 * 所有内部量都携带单位；原始导入表示保存在 RawInput 中，不做覆盖。
 */
@Serializable
data class Quantity(
    val value: Double,
    val unit: String,
    val raw: String? = null,
    val diagnostic: String? = null
) {
    val convertible: Boolean get() = diagnostic == null
}

object Units {
    const val BP = "14C yr BP"            // radiocarbon age (BP = before 1950 CE)
    const val CAL_YR_BP = "cal yr BP"
    const val CAL_YR = "cal yr (astronomical)" // 天文纪年，可负
    const val DELTA_R = "14C yr"          // reservoir offset, same scale as 14C age
    const val YEARS = "yr"
}

/** 天文纪年 <-> BCE/CE 标签。1 BCE/1 BCE 邻接 1 CE；没有 0 CE。 */
object EraScale {
    fun label(year: Double): String {
        val y = round(year).toInt()
        return if (y <= 0) "${abs(y) + 1} BCE" else "$y CE"
    }

    /** 解析 "12 BCE" / "50 CE" 为天文纪年。无法转换时返回 null。 */
    fun parse(label: String): Int? {
        val m = Regex("""^\s*(\d+)\s*(BCE|CE|BC|AD)\s*$""", RegexOption.IGNORE_CASE).matchEntire(label) ?: return null
        val n = m.groupValues[1].toInt()
        if (n == 0) return null
        return when (m.groupValues[2].uppercase()) {
            "CE", "AD" -> n
            else -> 1 - n
        }
    }

    /** 该区间是否跨越 BCE/CE 边界（包含 1 BCE <-> 1 CE，即天文年 0/1 之间）。 */
    fun crossesBceCe(fromAstro: Double, toAstro: Double): Boolean {
        val lo = minOf(fromAstro, toAstro)
        val hi = maxOf(fromAstro, toAstro)
        return lo < 1.0 && hi >= 1.0
    }
}
