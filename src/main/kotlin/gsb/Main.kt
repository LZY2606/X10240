package gsb

fun main(args: Array<String>) {
    var host = "127.0.0.1"
    var port = 5352
    var dbPath = "data/workbench.db"
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--host" -> { host = args[++i] }
            "--port" -> { port = args[++i].toInt() }
            "--db" -> { dbPath = args[++i] }
        }
        i++
    }
    val store = Store(dbPath, SystemClock)
    println("放射性碳校准工作台已启动: http://$host:$port （数据库 $dbPath）")
    val server = io.ktor.server.engine.embeddedServer(
        io.ktor.server.cio.CIO, port = port, host = host
    ) { Web.configure(this, store) }
    server.start(wait = true)
}
