package bench

import java.time.Instant

/** Virtual-time capable clock: production uses system time, tests drive a fixed clock. */
interface Clock {
    fun now(): Instant
}

object SystemClock : Clock {
    override fun now(): Instant = Instant.now()
}

class VirtualClock(start: Instant = Instant.parse("2026-01-01T00:00:00Z")) : Clock {
    private var current = start
    override fun now(): Instant = current
    fun advanceSeconds(seconds: Long) {
        current = current.plusSeconds(seconds)
    }
}
