package workbench

import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.json.Json

val AppJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    prettyPrint = false
}

val PrettyJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    prettyPrint = true
}

object Ids {
    fun new(prefix: String): String = prefix + "-" + UUID.randomUUID().toString()
}

fun sha256(text: String): String {
    val md = MessageDigest.getInstance("SHA-256")
    return md.digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}

interface Clock {
    fun now(): Instant
}

class SystemClock : Clock {
    override fun now(): Instant = Instant.now()
}

class VirtualClock(private var t: Instant = Instant.parse("2026-01-01T00:00:00Z")) : Clock {
    override fun now(): Instant = t
    fun advanceSeconds(s: Long) {
        t = t.plusSeconds(s)
    }
}

/** Calendar-epoch conversions. Internal coordinate is calBP (calendar years before 1950).
 *  Astronomical year numbering: year 0 == 1 BCE, year -50 == 51 BCE. */
object Eras {
    fun calBpToAstronomical(calBP: Double): Double = 1950.0 - calBP

    fun astronomicalLabel(y: Double): String {
        val f = kotlin.math.floor(y).toInt()
        return if (f >= 1) "$f CE" else "${1 - f} BCE"
    }

    fun calBpLabel(calBP: Double): String = "${calBP.toInt()} cal BP"

    fun label(calBP: Double, system: EraSystem): String = when (system) {
        EraSystem.CAL_BP -> calBpLabel(calBP)
        EraSystem.ASTRONOMICAL -> "astro ${calBpToAstronomical(calBP).toInt()}"
        EraSystem.BCE_CE -> astronomicalLabel(calBpToAstronomical(calBP))
    }
}

class DomainException(message: String) : Exception(message)
