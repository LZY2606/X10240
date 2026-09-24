package workbench

import java.time.Instant

interface Clock {
    fun now(): Instant
}

class SystemClock : Clock {
    override fun now(): Instant = Instant.now()
}

class MutableClock(private var t: Instant = Instant.parse("2026-01-01T00:00:00Z")) : Clock {
    override fun now(): Instant = t
    fun advanceSeconds(s: Long) { t = t.plusSeconds(s) }
    fun set(instant: Instant) { t = instant }
}
