package workbench

import java.time.Instant

/** Time source. Production uses [SystemClock]; tests use [VirtualClock]. */
interface Clock {
    fun now(): Instant
}

class SystemClock : Clock {
    override fun now(): Instant = Instant.now()
}

/** Deterministic clock for tests and replay: each call to [now] advances one second. */
class VirtualClock(private var current: Instant = Instant.parse("2026-01-01T00:00:00Z")) : Clock {
    override fun now(): Instant {
        val t = current
        current = current.plusSeconds(1)
        return t
    }
}
