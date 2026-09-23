package org.chronocarbon

import java.time.Instant

/** Injectable clock so the whole state machine can be replayed with virtual time. */
fun interface Clock {
    fun now(): Instant
}

class SystemClock : Clock {
    override fun now(): Instant = Instant.now()
}

/** Deterministic clock: advances only when [advance] is called; starts at a fixed instant. */
class FixedClock(private var instant: Instant = Instant.parse("2026-01-01T00:00:00Z")) : Clock {
    override fun now(): Instant = instant
    fun set(instant: Instant) { this.instant = instant }
    fun advance(seconds: Long) { instant = instant.plusSeconds(seconds) }
}
