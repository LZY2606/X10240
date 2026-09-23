package workbench

/** BCE/CE era arithmetic. No year zero: 1 BCE is followed by 1 CE.
 *  calBP is measured from 1950 CE. */
object Era {
    fun calBpToCeYear(calBp: Int, referenceYearCe: Int = 1950): Int = referenceYearCe - calBp

    /** Astronomical-free label: ceYear <= 0 maps to BCE (1 - ceYear... adjusted for no year 0). */
    fun label(calBp: Int, referenceYearCe: Int = 1950): String {
        val ce = calBpToCeYear(calBp, referenceYearCe)
        return if (ce >= 1) "$ce CE" else "${1 - ce} BCE"
    }

    fun crossesEraBoundary(fromBp: Int, toBp: Int, referenceYearCe: Int = 1950): Boolean {
        val lo = minOf(fromBp, toBp); val hi = maxOf(fromBp, toBp)
        // era boundary sits at calBP = referenceYearCe - 1 .. referenceYearCe (1 CE .. 1 BCE)
        return lo <= referenceYearCe && hi >= referenceYearCe - 1
    }
}
