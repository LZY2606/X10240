package gsb.store

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Deterministic virtual clock. Default advances one minute per tick from a
 *  fixed epoch; tests inject a controlled sequence. Never reads wall clock. */
class VirtualClock(
    private var epochMillis: Long = 1767225600000L, // fixed 2026-01-01T00:00:00Z
    private var stepMillis: Long = 60_000L
) {
    private var ticks = 0L
    fun nowIso(): String {
        val t = epochMillis + ticks * stepMillis
        ticks++
        return java.time.OffsetDateTime.ofInstant(Instant.ofEpochMilli(t), ZoneOffset.UTC).toString()
    }
    fun peekIso(): String =
        return java.time.OffsetDateTime.ofInstant(
            Instant.ofEpochMilli(epochMillis + ticks * stepMillis), ZoneOffset.UTC).toString()
}
