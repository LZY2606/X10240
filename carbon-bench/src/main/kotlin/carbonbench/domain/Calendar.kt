package carbonbench.domain

import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * cal BP 以 1950 CE 为基准。采用天文纪年（含 0 年）：
 *  calBP = 1950 - astronomicalYear
 * BCE/CE 标注按习惯跳过 0：天文 1..  = CE 1..；天文 0 = 1 BCE；天文 -k = (k+1) BCE
 */
object CalendarAxis {
    const val BASIS_YEAR_CE = 1950

    fun calBpToAstronomicalYear(calBP: Double): Double = BASIS_YEAR_CE - calBP

    /** 返回 BCE/CE 标签；跨年区间会跨界显示。 */
    fun label(calBP: Double): String {
        val year = calBpToAstronomicalYear(calBP)
        val rounded = year.roundToLong()
        return when {
            rounded >= 1L -> "${rounded} CE"
            rounded == 0L -> "1 BCE"
            else -> "${abs(rounded) + 1L} BCE"
        }
    }

    fun rangeLabel(startCalBP: Double, endCalBP: Double): String {
        val startYear = calBpToAstronomicalYear(startCalBP)
        val endYear = calBpToAstronomicalYear(endCalBP)
        return "${label(endCalBP)} – ${label(startCalBP)} (${fmt(startYear)}–${fmt(endYear)} 天文年)"
    }

    fun crossesBceCe(startCalBP: Double, endCalBP: Double): Boolean {
        val y1 = calBpToAstronomicalYear(startCalBP)
        val y2 = calBpToAstronomicalYear(endCalBP)
        return (y1 < 1.0 && y2 >= 0.0) || (y2 < 1.0 && y1 >= 0.0)
    }

    private fun fmt(v: Double): String {
        val r = v.roundToLong()
        return if (r >= 0L) r.toString() else "${-r} BCE天文($r)"
    }
}
