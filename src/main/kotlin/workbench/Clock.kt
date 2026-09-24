package workbench

import java.time.Instant

interface Clock {
    fun now(): String
}

class SystemClock : Clock {
    override fun now(): String = Instant.now().toString()
}

class VirtualClock(start: Instant = Instant.parse("2026-01-01T00:00:00Z"), private val stepSeconds: Long = 1) : Clock {
    private var tick = 0L
    private val base = start
    override fun now(): String {
        tick += 1
        return base.plusSeconds(tick * stepSeconds).toString()
    }
}
