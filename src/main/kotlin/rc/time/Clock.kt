package rc.time

interface Clock {
    fun nowMillis(): Long
}

class SystemClock : Clock {
    override fun nowMillis(): Long = System.currentTimeMillis()
}

/**
 * Virtual clock used by tests and by deterministic replays. Time only moves
 * forward when [advance] is called; there is no wall-clock or random input.
 */
class FixedClock(initial: Long = 1_700_000_000_000L) : Clock {
    private var current = initial
    override fun nowMillis(): Long = current
    fun advance(millis: Long = 1L) {
        require(millis >= 0)
        current += millis
    }
}
