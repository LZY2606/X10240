package workbench

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    var host = "127.0.0.1"
    var port = 5352
    var index = 0
    while (index < args.size) {
        when (args[index]) {
            "--host" -> host = args[++index]
            "--port" -> port = args[++index].toInt()
        }
        index++
    }
    val database = Paths.get(System.getProperty("workbench.db", "workbench.sqlite"))
    embeddedServer(Netty, host = host, port = port) { workbenchModule(database) }.start(wait = true)
}

fun Application.workbenchModule(database: Path) {
    val service = AppService(database, SystemTimeSource())
    if (EventStore(database, SystemTimeSource()).isEmpty()) {
        val bundled = object {}::class.java.getResourceAsStream("/cases/carbon-candidate-bench.json")?.bufferedReader()?.readText()
        if (bundled != null) service.importPackage(bundled)
    }
    routing {
        get("/") { call.respondText(indexHtml(), ContentType.Text.Html) }
        get("/api/state") {
            val at = call.request.queryParameters["atEventId"]
            call.respondText(json.writeValueAsString(if (at == null) service.snapshot() else service.snapshotAt(at)), ContentType.Application.Json)
        }
        get("/api/events") { call.respondText(json.writeValueAsString(service.snapshot().events), ContentType.Application.Json) }
        get("/api/svg") { call.respondText(SvgCharts.workbench(service.snapshot()), ContentType("image", "svg+xml")) }
        post("/api/cases/bundled") { write(service.importPackage(readBundledCase())) }
        post("/api/import-case") {
            val text = call.receiveText()
            try { write(service.importPackage(text)) } catch (e: ValidationException) { badRequest(e.diagnostics) }
        }
        post("/api/revisions/reservoir") {
            val node = json.readTree(call.receiveText())
            try {
                write(service.reviseReservoir(node.path("reservoirId").asText("res-marine-mixed"), node.path("mean").asDouble(70.0), node.path("sigma").asDouble(25.0), node.path("sourceRef").asText("review:reservoir-v2"), node.path("version").asText("v2")))
            } catch (e: ValidationException) { badRequest(e.diagnostics) }
        }
        post("/api/demo/measurement-edge") {
            write(service.reviseMeasurement("m-edge", "1595", "18", "boundary-book:B3", "v2"))
        }
        post("/api/demo/curve-upgrade") {
            write(service.activateCurve("curve-intcal-mini-2025", "curve-review:2025#1", listOf("m-beta")))
        }
        post("/api/decisions") {
            val node = json.readTree(call.receiveText())
            val years = node.path("selectedYears").map { row -> row.map { it.asInt() } }
            try { write(service.resolveConflict(node.path("sequenceId").asText("seq-alpha-before-gamma"), node.path("action").asText(), years, node.path("rationale").asText("review decision"), node.path("sourceRef").asText("review-form"))) }
            catch (e: ValidationException) { badRequest(e.diagnostics) }
        }
        post("/api/decisions/undo") {
            val node = json.readTree(call.receiveText())
            write(service.undoLastDecision(node.path("rationale").asText("undo last decision")))
        }
        get("/api/export") {
            call.response.header("Content-Disposition", "attachment; filename=\"radiocarbench-export.json\"")
            call.respondText(json.writeValueAsString(service.exportProject()), ContentType.Application.Json)
        }
        post("/api/import-project") {
            try { write(service.importProject(call.receiveText())) } catch (e: ValidationException) { badRequest(e.diagnostics) }
        }
        post("/api/reset") {
            service.reset()
            write(service.importPackage(readBundledCase()))
        }
    }

    suspend fun RoutingContext.write(state: ProjectState) = call.respondText(json.writeValueAsString(state), ContentType.Application.Json)
    suspend fun RoutingContext.badRequest(messages: List<String>) {
        call.response.status(HttpStatusCode.BadRequest)
        call.respondText(json.writeValueAsString(mapOf("diagnostics" to messages)), ContentType.Application.Json)
    }
}

private fun readBundledCase(): String =
    object {}::class.java.getResourceAsStream("/cases/carbon-candidate-bench.json")!!.bufferedReader().readText()

private fun indexHtml(): String =
    object {}::class.java.getResourceAsStream("/web/index.html")!!.bufferedReader().readText()
