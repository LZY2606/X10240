package org.chrono.workbench

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import org.chrono.workbench.db.ProjectService
import org.chrono.workbench.db.SqliteStore
import org.chrono.workbench.time.FixedEpochClock
import org.chrono.workbench.web.apiModule
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.exists

fun main(args: Array<String>) {
    var host = "127.0.0.1"
    var port = 5352
    var dbPath: Path = Paths.get("data", "gsb-workbench.sqlite")
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--host" -> { host = args[i + 1]; i += 2 }
            "--port" -> { port = args[i + 1].toInt(); i += 2 }
            "--db" -> { dbPath = Paths.get(args[i + 1]); i += 2 }
            else -> i++
        }
    }
    val clock = FixedEpochClock(java.time.Instant.parse("2026-09-24T00:00:00Z").epochSecond)
    val store = SqliteStore(dbPath)
    store.connect()
    val service = ProjectService(store, clock)
    embeddedServer(Netty, host = host, port = port) {
        apiModule(service, clock)
    }.start(wait = true)
}
