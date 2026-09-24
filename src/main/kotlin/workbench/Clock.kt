package workbench

import java.time.Instant

interface Clock {
    fun now(): Instant
}

object SystemClock : Clock {
    override fun now(): Instant = Instant.now()
}

/** Deterministic virtual clock for tests: each call advances by a fixed step. */
class FixedClock(
    private val start: Instant = Instant.parse("2026-01-01T00:00:00Z"),
    private val stepSeconds: Long = 1
) : Clock {
    private var ticks = 0L
    override fun now(): Instant = start.plusSeconds(stepSeconds * ticks++)
}
