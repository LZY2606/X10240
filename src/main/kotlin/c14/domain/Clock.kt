package c14.domain

/** Abstraction over wall-clock time so the whole workbench can run on virtual time. */
interface Clock {
    fun nowMillis(): Long
}

object SystemClock : Clock {
    override fun nowMillis(): Long = System.currentTimeMillis()
}

/** Deterministic clock. Time only advances when a command is processed. */
class VirtualClock(var millis: Long = 1_500_000_000_000L, private val tickMillis: Long = 60_000L) : Clock {
    override fun nowMillis(): Long = millis

    fun tick() {
        millis += tickMillis
    }
}
