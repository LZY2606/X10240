package gsb.store

import java.time.Instant

interface Clock {
    fun now(): Instant
}

class SystemClock : Clock {
    override fun now(): Instant = Instant.now()
}

/** Deterministic virtual time for tests and reproducible scenario replay. */
class VirtualClock(private var t: Instant = Instant.parse("2026-01-01T00:00:00Z")) : Clock {
    override fun now(): Instant = t
    fun advanceSeconds(s: Long) { t = t.plusSeconds(s) }
}
