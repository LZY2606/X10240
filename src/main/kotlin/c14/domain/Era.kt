package c14.domain

/**
 * Calendar era conversion.
 *
 * The internal axis is *cal BP*: years before 1950 CE, ascending toward the
 * present. Human-facing labels use the astronomical numbering convention
 * (year 0 = 1 BCE), which keeps BCE/CE crossing unambiguous.
 */
object Era {
    const val REFERENCE_YEAR_CE = 1950

    /** cal BP -> astronomical year (0 = 1 BCE, -1 = 2 BCE, 1 = 1 CE). */
    fun toAstronomicalYear(calBp: Int): Int = REFERENCE_YEAR_CE - calBp

    /** Astronomical year -> "2024 CE" / "100 BCE" style label. */
    fun label(calBp: Int): String {
        val y = toAstronomicalYear(calBp)
        return when {
            y >= 1 -> "$y CE"
            y == 0 -> "1 BCE"
            else -> "${-y + 1} BCE"
        }
    }

    /** True when the inclusive [fromCalBp,toCalBp] window straddles the BCE/CE boundary. */
    fun crossesBceCe(fromCalBp: Int, toCalBp: Int): Boolean {
        val from = toAstronomicalYear(toCalBp)
        val to = toAstronomicalYear(fromCalBp)
        return from <= 0 && to >= 0
    }
}
