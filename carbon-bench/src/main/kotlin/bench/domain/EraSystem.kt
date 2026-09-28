package bench.domain

import kotlinx.serialization.Serializable

/**
 * Coordinate system for calendar years.
 *
 * Internal arithmetic uses continuous **cal BP** (years before the reference
 * year 1950 CE, increasing toward the past). Human labels use the standard
 * "no year zero" convention (1 BCE is followed by 1 CE).
 */
@Serializable
data class EraSystem(
    val id: String,
    val name: String,
    val referenceYearCE: Int,
    val hasYearZero: Boolean,
    val sourceRef: String,
) {
    /** cal BP -> astronomical integer year (astronomical: 1 BCE == year 0). */
    fun bpToAstronomical(bp: Int): Int = referenceYearCE - bp

    /**
     * cal BP -> signed display year. With no year zero, astronomical year 0
     * is reported as label year -1 and the sign flips at the boundary.
     */
    fun bpToLabel(bp: Int): Int {
        val astro = bpToAstronomical(bp)
        if (hasYearZero) return astro
        return if (astro <= 0) astro - 1 else astro
    }

    fun eraTag(bp: Int): String = if (bpToLabel(bp) < 0) "BCE" else "CE"

    /** Human label such as "24 BCE" or "73 CE". */
    fun label(bp: Int): String {
        val y = bpToLabel(bp)
        return "${if (y < 0) -y else y} ${eraTag(bp)}"
    }

    /** True when an inclusive BP interval straddles the 1 BCE / 1 CE line. */
    fun crossesEraBoundary(bpOlder: Int, bpYounger: Int): Boolean {
        val a = bpToLabel(bpOlder)
        val b = bpToLabel(bpYounger)
        return (a < 0 && b > 0) || (a > 0 && b < 0)
    }
}
