package rcw

interface Clock {
    fun nowMillis(): Long
}

class SystemClock : Clock {
    override fun nowMillis(): Long = System.currentTimeMillis()
}

/** Deterministic clock for tests: each call advances by [stepMillis]. */
class VirtualClock(private var t: Long = 1_700_000_000_000L, private val stepMillis: Long = 1000L) : Clock {
    override fun nowMillis(): Long {
        val v = t
        t += stepMillis
        return v
    }
}
