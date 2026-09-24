package chrono.state

import java.time.Instant

/** Injectable clock. Tests pass a fixed, advancing virtual clock; the server uses the system clock. */
fun interface Clock {
    fun now(): Instant
}

class VirtualClock(start: Instant) : Clock {
    @Volatile
    private var current: Instant = start
    override fun now(): Instant = current
    fun advance(millis: Long) {
        current = current.plusMillis(millis)
    }
    fun set(t: Instant) {
        current = t
    }
}

val SystemClock = Clock { Instant.now() }
