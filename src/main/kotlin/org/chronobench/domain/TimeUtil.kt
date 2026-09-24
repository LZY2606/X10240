package org.chronobench.domain

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

interface Clock {
    fun nowMillis(): Long
    fun nowIso(): String = DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(nowMillis()))
}

class SystemClock : Clock {
    override fun nowMillis(): Long = System.currentTimeMillis()
}

/** Virtual time for tests: advances from a fixed epoch; never depends on the host clock. */
class VirtualClock(private var millis: Long = 1_700_000_000_000L) : Clock {
    override fun nowMillis(): Long = millis
    fun advance(ms: Long) { millis += ms }
}

object CalYears {
    /** cal BP (BP=before 1950 CE) -> proleptic Gregorian astronomical year. */
    fun astronomicalYear(calBP: Int): Int = 1950 - calBP

    /** Human label using 1 BCE for astronomical year 0; no year zero is displayed. */
    fun label(calBP: Int): String {
        val y = astronomicalYear(calBP)
        return if (y >= 1) "$y CE" else "${1 - y} BCE"
    }

    fun rangeLabel(loCalBP: Int, hiCalBP: Int): String = "${label(loCalBP)} \u2013 ${label(hiCalBP)}"
}

object Hashing {
    fun sha256Hex(text: String): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        return md.digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
