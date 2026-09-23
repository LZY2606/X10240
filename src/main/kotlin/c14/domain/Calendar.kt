package c14.domain

import kotlinx.serialization.Serializable

/** Calendar systems supported by the workbench. */
@Serializable
enum class CalendarSystem(val code: String, val label: String) {
    BP("BP", "years BP (before 1950 CE)"),
    BCE_CE("BCE/CE", "BCE/CE with astronomical year zero (0 = 1 BCE)"),
}

/**
 * Coordinate conversion between BP years and BCE/CE labels.
 *
 * Uses astronomical year numbering internally: calendar year 0 is 1 BCE,
 * year -1 is 2 BCE. The label format keeps "BCE"/"CE" so distributions that
 * cross the BCE/CE boundary render correctly and never collapse to a signed int.
 */
object Calendars {
    fun bpToAstronomical(calBP: Double): Double = 1950.0 - calBP

    fun labelOf(calBP: Double): YearLabel {
        val astro = bpToAstronomical(calBP)
        return if (astro >= 1.0) YearLabel(Math.round(astro).toLong(), "CE")
        else {
            val bce = 1L - Math.round(astro)
            YearLabel(bce, "BCE")
        }
    }

    fun format(calBP: Double): String {
        val l = labelOf(calBP)
        return "${l.year} ${l.era}"
    }

    fun formatRange(calBPStart: Double, calBPEnd: Double): String {
        // BP axis grows "older"; show younger label first in human order.
        return "${format(calBPEnd)} \u2013 ${format(calBPStart)}"
    }

    /** True when the inclusive BP-year interval straddles 1950 BP (the BCE/CE line). */
    fun crossesBceCe(calBPStart: Double, calBPEnd: Double): Boolean =
        calBPStart <= 1950.0 && calBPEnd >= 1950.0
}

@Serializable
data class YearLabel(val year: Long, val era: String)
