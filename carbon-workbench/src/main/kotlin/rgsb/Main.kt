package rgsb

import io.ktor.server.engine.stop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import rgsb.domain.CalibrationEngine
import rgsb.store.EventStore
import rgsb.store.SystemClock
import rgsb.store.Workflow
import rgsb.web.WebServer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.concurrent.thread

private data class Args(val host: String = "127.0.0.1", val port: Int = 5352, val data: String = ".carbon-workbench/data.db")

fun main(argv: Array<String>) {
    var a = Args()
    var i = 0
    while (i < argv.size) {
        when (argv[i]) {
            "--host" -> a = a.copy(host = argv[++i])
            "--port" -> a = a.copy(port = argv[++i].toInt())
            "--data" -> a = a.copy(data = argv[++i])
            else -> System.err.println("unknown arg ${argv[i]}")
        }
        i++
    }
    val dbPath = Paths.get(a.data)
    Files.createDirectories(dbPath.toAbsolutePath().parent)
    val store = EventStore(dbPath, "carbon-workbench-project", SystemClock)
    val workflow = Workflow(store, CalibrationEngine(seed = 0L), SystemClock)

    val indexHtml = object {}.javaClass.getResourceAsStream("/web/index.html")!!.bufferedReader().use { it.readText() }
    val server = WebServer(workflow, indexHtml).start(a.host, a.port)
    Runtime.getRuntime().addShutdownHook(thread(start = false) {
        runBlocking { withContext(Dispatchers.IO) { server.stop(500, 1000) } }
        store.close()
    })
    println("Carbon workbench listening on http://${a.host}:${a.port}")
    Thread.currentThread().join()
}
