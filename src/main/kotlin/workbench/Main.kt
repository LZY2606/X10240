package workbench

fun main(args: Array<String>) {
    var host = "127.0.0.1"
    var port = 5352
    var db = "workbench.db"
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--host" -> { host = args[i + 1]; i += 2 }
            "--port" -> { port = args[i + 1].toInt(); i += 2 }
            "--db" -> { db = args[i + 1]; i += 2 }
            else -> i += 1
        }
    }
    println("放射性碳多峰分布工作台 -> http://$host:$port  (db=$db)")
    startServer(host, port, db, SystemClock())
}
