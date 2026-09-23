package gsb

import java.io.File
import java.nio.file.Files
import java.time.Instant

/** Fixed virtual time (advances one second per read) and temp directories:
 *  no wall clock, no shared database, no network in tests. */
class VirtualClock(start: String = "2026-01-01T00:00:00Z") {
    var t: Instant = Instant.parse(start)
    fun now(): Instant {
        val current = t
        t = t.plusSeconds(1)
        return current
    }
}

fun tempDb(): File {
    val dir = Files.createTempDirectory("gsb-test-").toFile()
    dir.deleteOnExit()
    return File(dir, "workbench.db")
}

fun newEngine(): Pair<Engine, VirtualClock> {
    val clock = VirtualClock()
    return Engine(Store(tempDb().absolutePath), clock::now) to clock
}

fun calibFor(engine: Engine, measurementSourceId: String): CalibrationResult? {
    val d = CasePack.latestCalibrationFor(engine, measurementSourceId) ?: return null
    return jsonFmt.decodeFromString(CalibrationResult.serializer(), d.payload)
}
