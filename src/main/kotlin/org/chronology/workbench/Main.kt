package org.chronology.workbench

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.http.content.staticResources
import io.ktor.server.routing.routing
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText

fun main(args: Array<String>) {
    var host = "127.0.0.1"
    var port = 5352
    var database = Path.of("data/radiocarbon-workbench.sqlite")
    val iterator = args.iterator()
    while (iterator.hasNext()) {
        when (iterator.next()) {
            "--host" -> host = iterator.next()
            "--port" -> port = iterator.next().toInt()
            "--database" -> database = Path.of(iterator.next())
            "--write-casepack" -> {
                Files.createDirectories(Path.of("src/main/resources/casepack"))
                Path.of("src/main/resources/casepack/carbon-candidate-bench.json").writeText(workbenchJson.encodeToString(Bundle.serializer(), CaseFactory().initialBundle()))
                return
            }
        }
    }
    Files.createDirectories(database.parent ?: Path.of("."))
    val store = SqliteEventStore(database)
    embeddedServer(Netty, host = host, port = port) { workbenchModule(store) }.start(wait = true)
}

fun Application.workbenchModule(store: SqliteEventStore) {
    install(ContentNegotiation) { json(workbenchJson) }
    val work = WorkService(store, SystemClock())
    val scenarios = CaseScenarioService(work)

    routing {
        get("/") {
            val html = javaClass.getResourceAsStream("/web/index.html")?.bufferedReader()?.use { it.readText() }
                ?: error("missing index.html")
            call.respondText(html, ContentType.Text.Html)
        }
        staticResources("/", "web")

        get("/api/state") {
            val asOf = call.request.queryParameters["asOfSeq"]?.toLongOrNull()
            call.respond(work.state(asOf))
        }

        get("/api/casepack") { call.respond(CaseFactory().initialBundle()) }
        get("/api/casepack/stages") { call.respond(CaseFactory().stages()) }

        post("/api/casepack/initialize") {
            val report = work.resetAndImport(CaseFactory().initialBundle())
            call.respond(report)
        }

        post("/api/casepack/stages/{stageId}") {
            val stageId = call.parameters["stageId"] ?: throw WorkException("UNKNOWN_STAGE", "缺少阶段身份")
            call.respond(scenarios.applyStage(stageId))
        }

        post("/api/initialize") {
            call.respond(work.initializeProject(call.receive()))
        }

        post("/api/curves") { call.respond(work.submitCurve(call.receive())) }
        post("/api/measurements") { call.respond(work.submitMeasurement(call.receive())) }
        post("/api/reservoirs") { call.respond(work.submitReservoir(call.receive())) }
        post("/api/era-systems") { call.respond(work.submitEraSystem(call.receive())) }
        post("/api/relations") { call.respond(work.submitRelation(call.receive())) }
        post("/api/exclusions") { call.respond(work.submitExclusion(call.receive())) }
        post("/api/calibrate") { call.respond(work.calibrate(call.receive())) }
        post("/api/sequence") { call.respond(work.evaluateSequence(call.receive())) }
        post("/api/decide") { call.respond(work.decide(call.receive())) }

        post("/api/undo-decision") {
            val payload = call.receive<UndoRequest>()
            call.respond(work.reverseDecision(payload.decisionEventId, payload.rationale))
        }

        get("/api/export") {
            call.response.headers.append("Content-Disposition", "attachment; filename=radiocarbon-workbench-export.json")
            call.respond(work.exportBundle())
        }

        post("/api/import") {
            val bundle = call.receive<Bundle>()
            call.respond(work.resetAndImport(bundle))
        }
    }

    install(io.ktor.server.plugins.statuspages.StatusPages) {
        exception<WorkException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, mapOf("code" to cause.code, "message" to cause.message))
        }
        exception<Throwable> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, mapOf("code" to "BAD_REQUEST", "message" to (cause.message ?: "请求格式错误")))
        }
    }
}

@kotlinx.serialization.Serializable
data class UndoRequest(val decisionEventId: String, val rationale: String)
