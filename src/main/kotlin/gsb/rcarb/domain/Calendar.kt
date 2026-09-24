package gsb.rcarb.domain

import kotlinx.serialization.Serializable

/**
 * 日历年代坐标。
 *
 * 内部统一使用两种可互转的坐标：
 *  - calBP：相对于公元 1950 年的“年前”，整数网格，越大越早。
 *  - astroYear：天文纪年整数（1 BCE 记为 0，2 BCE 记为 -1），消除 BCE/CE 跨界歧义。
 *
 * 展示层使用 eraYear：BCE/CE（也接受 BC/AD）标签 + 纪年（正整数，无 0 年）。
 * 所有转换都是纯函数，固定可重现。
 */
@Serializable
data class EraYear(val label: String, val year: Int) {
    init {
        require(year >= 1) { "era year must be >= 1, got $year" }
    }
    val isBce: Boolean get() = label.uppercase() in BCE_LABELS
    companion object {
        val BCE_LABELS = setOf("BCE", "BC")
        val CE_LABELS = setOf("CE", "AD")
    }
}

object Calendar {
    const val CALBP_ANCHOR_ASTRO_YEAR = 1950

    /** calBP -> 天文年：calBP=0 对应 1950 CE，calBP=1950 对应 1 BCE（天文 0）。 */
    fun calBpToAstroYear(calBp: Int): Int = CALBP_ANCHOR_ASTRO_YEAR - calBp

    fun astroYearToCalBp(astroYear: Int): Int = CALBP_ANCHOR_ASTRO_YEAR - astroYear

    fun calBpToEra(calBp: Int): EraYear {
        val astro = calBpToAstroYear(calBp)
        return if (astro >= 1) EraYear("CE", astro) else EraYear("BCE", 1 - astro)
    }

    fun eraToCalBp(era: EraYear): Int {
        val astro = if (era.isBce) 1 - era.year else era.year
        return astroYearToCalBp(astro)
    }

    /** "250 BCE" / "33 CE" / "100 bc" -> calBP；非法输入返回 null（保留原值并附诊断）。 */
    fun parseEraLabel(text: String): EraYear? {
        val cleaned = text.trim()
        val m = Regex("(?i)^\\s*(\\d+)\\s*(BCE|BC|CE|AD)\\s*$").matchEntire(cleaned) ?: return null
        val year = m.groupValues[1].toInt()
        if (year < 1) return null
        val label = m.groupValues[2].uppercase().let { if (it == "BC") "BCE" else if (it == "AD") "CE" else it }
        return runCatching { EraYear(label, year) }.getOrNull()
    }

    fun formatEra(calBp: Int): String {
        val e = calBpToEra(calBp)
        return "${e.year} ${e.label}"
    }

    /** 两个 calBP 网格点（闭区间 [older, younger]，older>=younger）之间是否跨越 BCE/CE 边界（1 BCE <-> 1 CE，即天文 0/1，calBP 1950/1949）。 */
    fun spansBceCeBoundary(calBpOlder: Int, calBpYounger: Int): Boolean {
        val lo = minOf(calBpOlder, calBpYounger)
        val hi = maxOf(calBpOlder, calBpYounger)
        return hi <= 1950 && lo >= 1949 && !(lo == hi)
    }
}
