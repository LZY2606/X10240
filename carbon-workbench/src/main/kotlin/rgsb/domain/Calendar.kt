package rgsb.domain

import kotlin.math.abs

/**
 * Year conventions used across the workbench.
 *
 * Internally everything uses astronomical years on the CE/BCE axis:
 *   2024 -> 2024 CE, 0 -> 1 BCE, -49 -> 50 BCE.
 * "cal BP" means years before 1950 CE, so:  calYear = 1950 - calBP.
 */
object CalendarConventions {
    const val CAL_BP_ZERO_CE: Int = 1950

    fun calBpToAstronomical(calBp: Int): Int = CAL_BP_ZERO_CE - calBp
    fun astronomicalToCalBp(year: Int): Int = CAL_BP_ZERO_CE - year

    /** Human label such as "50 BCE" / "1950 CE" / "1 BCE". */
    fun label(year: Int): String = when {
        year >= 1 -> "$year CE"
        year == 0 -> "1 BCE"
        else -> "${1 - year} BCE"
    }

    /** Short axis label for SVG ticks. */
    fun tick(year: Int): String = when {
        year >= 1 -> "${abs(year)}"
        year == 0 -> "1BCE"
        else -> "${1 - year}BCE"
    }
}
