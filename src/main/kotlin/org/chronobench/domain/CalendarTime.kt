package org.chronobench.domain

import kotlinx.serialization.Serializable
import kotlin.math.PI
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

const val SQRT_2PI: Double = 2.5066282746310005

fun normalDensity(x: Double, mean: Double, sd: Double): Double {
    val z = (x - mean) / sd
    return kotlin.math.exp(-0.5 * z * z) / (sd * SQRT_2PI)
}

/**
 * Calendar coordinate systems supported by the workbench.
 * All internal computation happens on the [BP_1950] axis (years before 1950 CE, larger = older).
 * The [ANNO_ASTRONOMICAL] system is used for BCE/CE presentation; astronomical year 0 == 1 BCE.
 */
@Serializable
enum class EraSystem(val id: String, val label: String) {
    BP_1950("bp1950", "Years BP (before 1950 CE)"),
    ANNO_ASTRONOMICAL("anno-astronomical", "Astronomical years (BCE/CE, year 0 = 1 BCE)");

    companion object {
        fun fromId(id: String): EraSystem = entries.first { it.id == id }
    }
}

@Serializable
data class EraLabel(val text: String, val astronomicalYear: Int, val isBCE: Boolean)

object CalendarAxis {
    /** Convert cal BP to astronomical year (1 BCE == 0, 2 BCE == -1, 1 CE == 1). */
    fun toAstronomicalYear(calBP: Double): Double = 1950.0 - calBP

    fun toEraLabel(calBP: Double): EraLabel {
        val astro = toAstronomicalYear(calBP)
        val rounded = kotlin.math.round(astro).toInt()
        return if (rounded <= 0) EraLabel("${1 - rounded} BCE", rounded, true)
        else EraLabel("$rounded CE", rounded, false)
    }

    fun formatInterval(startCalBP: Double, endCalBP: Double, era: EraSystem): String = when (era) {
        EraSystem.BP_1950 -> "${fmt(startCalBP)}-${fmt(endCalBP)} cal BP"
        EraSystem.ANNO_ASTRONOMICAL -> "${toEraLabel(endCalBP).text} \u2013 ${toEraLabel(startCalBP).text}"
    }

    /** True when the interval straddles the BCE/CE boundary (astronomical year 0 = 1 BCE). */
    fun crossesBceCe(startCalBP: Double, endCalBP: Double): Boolean {
        val a0 = toAstronomicalYear(startCalBP)
        val a1 = toAstronomicalYear(endCalBP)
        val lo = minOf(a0, a1)
        val hi = maxOf(a0, a1)
        return lo < 0.0 && hi > 0.0
    }

    private fun fmt(v: Double): String = if (v == kotlin.math.floor(v)) v.toInt().toString() else v.toString()
}
