package org.chronology.workbench.model

import java.math.BigInteger
import java.security.MessageDigest

/** Units and coordinate datums handled by the import layer. */
object Units {
    private val aliases = mapOf(
        "14c yr bp" to "14C_BP",
        "14c bp" to "14C_BP",
        "bp" to "14C_BP",
        "c14 yr bp" to "14C_BP",
        "yr bp" to "14C_BP",
        "delta r" to "DELTA_R",
        "delta-r" to "DELTA_R",
        "deltar" to "DELTA_R",
        "14c yr" to "DELTA_R",
        "yr" to "DELTA_R",
        "cal bp" to "CAL_BP",
        "cal yr bp" to "CAL_BP",
    )

    fun normalizeUnit(raw: String): String = aliases[raw.trim().lowercase()] ?: raw.trim().uppercase()

    fun parseDouble(text: String): Double? = text.trim().toDoubleOrNull()

    fun parseRaw(text: String, unit: String): RawNumber {
        val normalizedUnit = normalizeUnit(unit)
        val numeric = parseDouble(text)
        return RawNumber(text = text.trim(), unit = normalizedUnit, value = numeric)
    }
}

/** Stable content identities survive delete/re-import because they derive from content, not row ids. */
object Identities {
    private val digest = ThreadLocal.withInitial { MessageDigest.getInstance("SHA-256") }

    fun sha256(vararg parts: String): String {
        val d = digest.get()
        d.reset()
        parts.forEachIndexed { i, p ->
            if (i > 0) d.update(0)
            d.update(p.toByteArray(Charsets.UTF_8))
        }
        val hex = String.format("%064x", BigInteger(1, d.digest()))
        digest.set(MessageDigest.getInstance("SHA-256"))
        return hex
    }

    fun short(vararg parts: String): String = sha256(parts.joinToString(" ")).take(16)
}

/**
 * Calendar era system. Conventional radiocarbon BP counts years before 1950 CE.
 * astronomical year = 1950 - calBP, so 1950 calBP -> 0 (1 BCE), and the BCE/CE boundary
 * (astronomical 0 <-> 1, i.e. 1 BCE / 1 CE) is crossed by any interval spanning calBP 1949/1950.
 */
object CalendarEra {
    fun astronomicalYear(calBP: Int, originYearCE: Int = 1950): Int = originYearCE - calBP

    fun label(calBP: Int, originYearCE: Int = 1950, convention: String = "BCE/CE"): String {
        val astro = astronomicalYear(calBP, originYearCE)
        return when {
            convention.equals("BP", true) -> "$calBP cal BP"
            astro <= 0 -> "${1 - astro} BCE"
            else -> "$astro CE"
        }
    }

    fun rangeLabel(calBPLo: Int, calBPHi: Int, originYearCE: Int = 1950, convention: String = "BCE/CE"): String =
        "${label(calBPHi, originYearCE, convention)} \u2013 ${label(calBPLo, originYearCE, convention)}"

    fun crossesBceCe(calBPLo: Int, calBPHi: Int, originYearCE: Int = 1950): Boolean =
        calBPLo <= originYearCE - 1 && calBPHi >= originYearCE
}
