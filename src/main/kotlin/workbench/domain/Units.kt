package workbench.domain

import kotlinx.serialization.Serializable
import kotlin.math.abs

/**
 * Calendar coordinate handling.
 *
 * Internally every calendar position is stored in cal BP (years before 1950 CE),
 * as a non-negative Double. The display layer converts to signed astronomical
 * years and to BCE/CE labels. No value is ever converted to an unsigned BCE
 * integer internally, because that would erase the BCE/CE boundary (there is no
 * year zero in the conventional BCE/CE notation).
 */
object CalendarConventions {
    const val BP_REFERENCE_YEAR = 1950.0

    /** Signed astronomical year: 1 BCE = 0, 2 BCE = -1, 1 CE = 1. */
    fun calBpToSignedYear(calBp: Double): Double = BP_REFERENCE_YEAR - calBp

    /** Conventional label, e.g. 50 calBP -> "1900 CE", 1960 calBP -> "11 BCE". */
    fun calBpToEraLabel(calBp: Double): String {
        val signed = calBpToSignedYear(calBp)
        val roundedYear = Math.round(signed)
        return when {
            roundedYear >= 1 -> "$roundedYear CE"
            roundedYear == 0 -> "1 BCE"
            else -> "${1 - roundedYear} BCE"
        }
    }

    fun signedYearToCalBp(signedYear: Double): Double = BP_REFERENCE_YEAR - signedYear

    /** True when a cal BP interval straddles the BCE/CE boundary (1950 cal BP). */
    fun straddlesBceCe(startCalBp: Double, endCalBp: Double): Boolean =
        startCalBp < BP_REFERENCE_YEAR && endCalBp > BP_REFERENCE_YEAR
}

@Serializable
data class Quantity(val value: Double, val unit: String, val rawText: String)

@Serializable
data class CoordinateRef(
    val system: String,
    val axis: String,
    val unit: String,
    val zeroConvention: String
) {
    companion object {
        val CAL_BP = CoordinateRef(
            system = "ASTRONOMICAL_SIGNED",
            axis = "calendar",
            unit = "cal yr BP",
            zeroConvention = "BP measured from 1950 CE; signed astronomical year = 1950 - calBP"
        )
        val C14_BP = CoordinateRef(
            system = "C14_LAB",
            axis = "radiocarbon",
            unit = "14C yr BP",
            zeroConvention = "BP measured from 1950 CE"
        )
    }
}

object UnitMath {
    /**
     * Normalise a radiocarbon quantity to 14C yr BP. Values that cannot be
     * converted stay where they were and carry a diagnostic instead of being
     * dropped for plotting.
     */
    fun normalizeC14Bp(value: Double, unit: String): Double? {
        val normalised = unit.trim().lowercase().replace(" ", " ")
        return when {
            normalised.contains("14c") && normalised.contains("bp") -> value
            normalised == "bp" || normalised == "yr bp" || normalised == "years bp" -> value
            normalised.contains("cal") -> null // calendar units must never silently enter a 14C likelihood
            else -> null
        }
    }

    fun nearlyEqual(a: Double, b: Double, tolerance: Double = 1e-9): Boolean =
        abs(a - b) <= tolerance
}
