package carbonbench.time

import java.time.Instant

interface BenchClock {
    fun now(): Instant
}

class SystemClock : BenchClock {
    override fun now(): Instant = Instant.now()
}

class VirtualClock(var current: Instant) : BenchClock {
    override fun now(): Instant = current
    fun advanceMillis(millis: Long) {
        current = current.plusMillis(millis)
    }
    fun advanceSeconds(seconds: Long) = advanceMillis(seconds * 1000L)
}
