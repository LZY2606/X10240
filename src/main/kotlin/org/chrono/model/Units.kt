package org.chrono.model

import kotlinx.serialization.Serializable
import kotlin.math.abs

/**
 * A domain quantity that always carries its unit and (where relevant) its coordinate datum.
 * Raw textual representations are preserved on import; normalization happens only in the
 * derived layer, never by overwriting the original.
 */
@Serializable
data class Qty(
    val value: Double,
    val unit: String,
    val datum: String? = null,
) {
    init {
        require(value.isFinite()) { "quantity value must be finite, got $value ($unit)" }
    }
}

object Units {
    const val C14_YR_BP = "14C yr BP"
    const val CAL_YR_BP = "cal yr BP"
    const val YR = "yr"
    const val DATUM_BP1950 = "BP1950"
    const val DATUM_ASTRO = "astronomical-year"
    const val DATUM_BCE_CE = "BCE/CE"
}

object Years {
    /** Convert calendar years BP (Before 1950) to astronomical years: 1950-calBP. */
    fun calBpToAstro(calBp: Double): Double = 1950.0 - calBp

    fun astroToCalBp(astro: Double): Double = 1950.0 - astro

    /**
     * Format an astronomical year with BCE/CE using the astronomical convention
     * (year 0 = 1 BCE). Sign is explicit so the BCE/CE boundary is never ambiguous.
     */
    fun bceCeLabel(astroYear: Double): String {
        val rounded = Math.round(abs(astroYear))
        return when {
            astroYear > 0 -> "${rounded} CE"
            astroYear < 0 -> "${rounded} BCE"
            else -> "1 BCE (astronomical year 0)"
        }
    }

    fun crossesBceCe(astroLo: Double, astroHi: Double): Boolean = astroLo < 0.0 && astroHi >= 0.0
}
