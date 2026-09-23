package org.chrono.timex

import java.time.Instant

/**
 * Time source. Production uses the system clock; tests use a fixed/virtual clock so that
 * replay and event histories are fully deterministic and never depend on wall-clock state.
 */
fun interface CClock {
    fun now(): Instant
}

object SystemClock : CClock {
    override fun now(): Instant = Instant.now()
}

/** Deterministic virtual clock: starts at [epoch] and only advances when told to. */
class FakeClock(
    @Volatile private var current: Instant = Instant.parse("2026-09-24T00:00:00Z"),
) : CClock {
    override fun now(): Instant = current
    fun advanceSeconds(seconds: Long) {
        current = current.plusSeconds(seconds)
    }
    fun set(instant: Instant) {
        current = instant
    }
}
