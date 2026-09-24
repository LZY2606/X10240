package org.chrono.workbench.time

import java.time.Instant

interface Clock {
    fun now(): Instant
}

class FixedEpochClock(startEpochSecond: Long) : Clock {
    private var current = startEpochSecond
    override fun now(): Instant = Instant.ofEpochSecond(current)
    fun tick(seconds: Long) {
        current += seconds
    }
    fun set(epochSecond: Long) {
        current = epochSecond
    }
}

class SystemClockService : Clock {
    override fun now(): Instant = Instant.now()
}
