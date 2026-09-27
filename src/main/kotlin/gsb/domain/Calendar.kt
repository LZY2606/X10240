package gsb.domain

import kotlinx.serialization.Serializable

/**
 * 日历坐标：内部统一使用 calBP（距 1950-01-01 的日历年，BP 越大越早）。
 * BCE/CE 仅作为显示年代系统；BP=1950 对应 BCE/CE 跨界点。
 */
@Serializable
data class CalendarEra(
    val id: String,
    val name: String,
    val bpAnchor: Int = 1950,
    val source: SourceRef = SourceRef.UNKNOWN
) {
    companion object {
        val DEMO = CalendarEra("ERA-BP", "calBP / BCE-CE（BP 锚点 1950 CE）")
    }
}

object CalendarAxis {
    /** calBP 年 -> 带年代标签的年份：1950 BP = 1 BCE/CE 跨界。 */
    fun label(bp: Int): String {
        if (bp >= 1950) {
            val bce = bp - 1949
            return "${bce} BCE"
        }
        val ce = 1950 - bp
        return "${ce} CE"
    }

    /** 是否跨越 BCE/CE 边界（概率质量同时落在 BP>=1950 与 BP<1950 两侧）。 */
    fun crossesBceCe(bpYears: IntRange, mass: DoubleArray): Boolean {
        var bce = 0.0
        var ce = 0.0
        for (i in bpYears.indices) {
            if (bpYears.first + i >= 1950) bce += mass[i] else ce += mass[i]
        }
        return bce > 0.01 && ce > 0.01
    }
}
