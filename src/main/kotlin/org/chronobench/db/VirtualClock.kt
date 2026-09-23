package org.chronobench.db

/** Injectable clock so replay and tests use virtual time (fixed timestamps) instead of wall time. */
interface Clock {
    fun nowEpochSecond(): Long
}

class SystemClock : Clock {
    override fun nowEpochSecond(): Long = System.currentTimeMillis() / 1000L
}

/** Deterministic test/replay clock: starts at [startEpochSecond], advances [tickSeconds] each read. */
class VirtualClock(
    private var current: Long,
    private val tickSeconds: Long = 1L
) : Clock {
    override fun nowEpochSecond(): Long {
        val v = current
        current += tickSeconds
        return v
    }

    fun advance(seconds: Long) { current += seconds }
}
