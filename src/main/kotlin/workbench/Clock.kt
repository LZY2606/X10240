package workbench

import java.time.Instant
import java.util.UUID

/** Injectable clock so tests can use virtual time. */
interface Clock {
    fun now(): Instant
}

class SystemClock : Clock {
    override fun now(): Instant = Instant.now()
}

/** Virtual clock for tests and deterministic replay. */
class FixedClock(private var t: Instant = Instant.parse("2026-01-01T00:00:00Z")) : Clock {
    private var tick = 0L
    override fun now(): Instant = t.plusSeconds(tick++)
    fun set(t: Instant) { this.t = t; tick = 0 }
}

/** Deterministic id generator (fixed seed prefix + counter) so tests and replay are reproducible. */
class IdGen(private val prefix: String = "id") {
    private var counter = 0
    fun next(kind: String): String = "$prefix-$kind-${(counter++).toString().padStart(4, '0')}"
    companion object {
        fun random(): String = UUID.randomUUID().toString()
    }
}
