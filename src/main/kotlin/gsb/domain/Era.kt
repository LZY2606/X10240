package gsb.domain

/**
 * 天文纪年换算（无 0 年）：astroYear = 1950 - calBP
 *  astroYear 1 -> 1 CE；astroYear 0 -> 1 BCE；astroYear -1 -> 2 BCE。
 * BCE/CE 跨界区间会被显式标记 crossesAnchor，不强行归到一侧。
 */
object EraConversions {
    fun toLabel(calBp: Double, era: EraSystem = EraSystem("era-bcece", "BCE_CE_ASTRO", "BCE/CE astronomical")): EraLabel {
        val astro = era.anchorCalBp - calBp
        return if (astro >= 1.0) {
            EraLabel(astro.toInt(), "CE", "${astro.toInt()} CE")
        } else {
            val bce = (1.0 - astro).toInt()
            EraLabel(bce, "BCE", "$bce BCE")
        }
    }

    fun crossesAnchor(startCalBp: Double, endCalBp: Double, era: EraSystem): Boolean {
        // 区间（calBP 轴上 [end,start]）跨过 anchor-1 .. anchor（1 BCE .. 1 CE 之间的边界）
        val hi = maxOf(startCalBp, endCalBp)
        val lo = minOf(startCalBp, endCalBp)
        return hi >= era.anchorCalBp - 1.0 && lo <= era.anchorCalBp
    }
}
