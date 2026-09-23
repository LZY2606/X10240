package c14

import c14.domain.SystemClock
import c14.domain.VirtualClock
import c14.web.AppEngine
import c14.web.ServerEnv
import c14.web.webModule
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import java.io.File

fun main(args: Array<String>) {
    var host = "127.0.0.1"
    var port = 5352
    var dbDir = File("data")
    var virtualTime = false
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--host" -> { host = args[++i] }
            "--port" -> { port = args[++i].toInt() }
            "--data-dir" -> { dbDir = File(args[++i]) }
            "--virtual-time" -> { virtualTime = true }
            "--help", "-h" -> {
                println("Usage: run --host 127.0.0.1 --port 5352 [--data-dir data] [--virtual-time]")
                return
            }
        }
        i++
    }
    dbDir.mkdirs()
    val clock = if (virtualTime) VirtualClock() else SystemClock
    val app = AppEngine(clock, File(dbDir, "c14-bench.sqlite").toPath())
    embeddedServer(Netty, host = host, port = port) {
        webModule(ServerEnv(app, clock, dbDir))
    }.start(wait = true)
}
