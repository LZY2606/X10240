package gsb

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import java.io.File
import java.time.Instant

fun main(args: Array<String>) {
    var host = "127.0.0.1"
    var port = 5352
    var db = "data/workbench.db"
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--host" -> { host = args[i + 1]; i += 2 }
            "--port" -> { port = args[i + 1].toInt(); i += 2 }
            "--db" -> { db = args[i + 1]; i += 2 }
            else -> i += 1
        }
    }
    File(db).parentFile?.mkdirs()
    val store = Store(db)
    val engine = Engine(store) { Instant.now() }
    val app = WebApp(engine)
    println("radiocarbon workbench on http://$host:$port (db=$db)")
    embeddedServer(Netty, port = port, host = host) { with(app) { module() } }.start(wait = true)
}
