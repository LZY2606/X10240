package rgsb.store

import java.time.Instant

/**
 * Time source. Production uses system UTC; tests inject a fixed virtual clock so every
 * replay and assertion is deterministic.
 */
fun interface VClock {
    fun now(): Instant
}

val SystemClock = VClock { Instant.now() }

class VirtualClock(start: String = "2024-01-01T00:00:00Z") : VClock {
    private var current = Instant.parse(start)
    override fun now(): Instant = current
    fun advanceSeconds(seconds: Long) { current = current.plusSeconds(seconds) }
    fun set(instant: String) { current = Instant.parse(instant) }
}
