package gsb

import io.ktor.server.engine.*
import io.ktor.server.netty.*
import java.io.File

fun main(args: Array<String>) {
    var host = "127.0.0.1"
    var port = 5352
    var dbPath = "workbench.db"
    var casepackDir = "casepack"
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--host" -> host = args[++i]
            "--port" -> port = args[++i].toInt()
            "--db" -> dbPath = args[++i]
            "--casepack-dir" -> casepackDir = args[++i]
        }
        i++
    }
    val store = Store(File(dbPath), SystemClock())
    val wb = Workbench(store, File(casepackDir))
    println("radiocarbon workbench on http://$host:$port  (db=$dbPath, casepack=$casepackDir)")
    embeddedServer(Netty, port = port, host = host) {
        configureRouting(wb)
    }.start(wait = true)
}
