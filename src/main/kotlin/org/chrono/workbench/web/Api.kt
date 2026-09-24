package org.chrono.workbench.web

import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.http.content.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import org.chrono.workbench.case.CasePack
import org.chrono.workbench.case.ImportLayer
import org.chrono.workbench.db.ProjectService
import org.chrono.workbench.domain.CalibrationEngine
import org.chrono.workbench.time.FixedEpochClock

@Serializable
data class ReviewRequest(val measurementId: String, val action: String, val reason: String, val decidedBy: String)

@Serializable
data class ReverseRequest(val eventId: String)

@Serializable
data class ImportSheetRequest(val csv: String, val sourceName: String = "pasted laboratory sheet")

@Serializable
data class ImportBundleRequest(val bundleJson: String)

@Serializable
data class ApiError(val error: String, val detail: String = "")

fun Application.apiModule(service: ProjectService, clock: FixedEpochClock) {
    install(ContentNegotiation) {
        json(Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        })
    }
    routing {
        staticResources("/static", "web")
        get("/") {
            call.respondRedirect("/static/index.html", permanent = false)
        }
        get("/api/state") {
            val s = service.state
            call.respond(
                mapOf(
                    "projectId" to s.projectId,
                    "projectName" to s.projectName,
                    "opened" to s.opened.toString(),
                    "activeCurveId" to (s.activeCurveId ?: ""),
                    "curves" to s.curves.values.toList(),
                    "measurements" to s.measurements.values.toList(),
                    "reservoirs" to s.reservoirs.values.toList(),
                    "eraSystem" to s.eraSystem,
                    "constraints" to s.constraints.values.toList(),
                    "decisions" to s.decisions.values.toList(),
                    "derived" to s.derived.values.toList(),
                    "reviewStatuses" to s.measurements.keys.associateWith { service.reviewStatus(it) },
                    "eventCount" to s.events.size.toString()
                )
            )
        }
        get("/api/events") {
            call.respond(service.state.events)
        }
        get("/api/events/{id}") {
            val e = service.state.events.firstOrNull { it.eventId == call.parameters["id"] }
            if (e == null) call.respond(HttpStatusCode.NotFound, ApiError("event not found"))
            else call.respond(e)
        }
        get("/api/replay/{seq}") {
            val seq = call.parameters["seq"]?.toIntOrNull()
                ?: return@get call.respond(HttpStatusCode.BadRequest, ApiError("seq must be an integer"))
            val snap = service.replayTo(seq)
            call.respond(
                mapOf(
                    "atSeq" to seq.toString(),
                    "measurements" to snap.measurements.values.toList(),
                    "curves" to snap.curves.values.toList(),
                    "reservoirs" to snap.reservoirs.values.toList(),
                    "constraints" to snap.constraints.values.toList(),
                    "decisions" to snap.decisions.values.toList(),
                    "derived" to snap.derived.values.toList(),
                    "events" to snap.events.toList(),
                    "reviewStatuses" to snap.measurements.keys.associateWith { id ->
                        snap.events.filter { it.type == "REVIEW_CANDIDATE" }
                            .lastOrNull { it.payload.jsonPrimitiveOrNull("measurementId") == id }
                            ?.payload?.jsonPrimitiveOrNull("toStatus") ?: "PENDING"
                    }
                )
            )
        }
        get("/api/derived/{measurementId}") {
            val id = call.parameters["measurementId"]!!
            val rec = service.state.derived.values
                .filter { it.input.measurementId == id }
                .maxByOrNull { it.createdAtEventSeq }
            if (rec == null) call.respond(HttpStatusCode.NotFound, ApiError("no derived calibration for $id"))
            else call.respond(rec)
        }
        get("/api/derived/{measurementId}/integral") {
            val id = call.parameters["measurementId"]!!
            val from = call.request.queryParameters["from"]?.toIntOrNull()
            val to = call.request.queryParameters["to"]?.toIntOrNull()
            if (from == null || to == null) {
                call.respond(HttpStatusCode.BadRequest, ApiError("from/to calBP required"))
                return@get
            }
            val rec = service.state.derived.values
                .filter { it.input.measurementId == id }
                .maxByOrNull { it.createdAtEventSeq }
            if (rec == null) call.respond(HttpStatusCode.NotFound, ApiError("no derived calibration for $id"))
            else call.respond(CalibrationEngine.integrate(rec.result, from, to))
        }
        get("/api/sequence/{constraintId}") {
            val v = service.evaluateSequence(call.parameters["constraintId"]!!)
            if (v == null) call.respond(HttpStatusCode.NotFound, ApiError("constraint not found or inactive"))
            else call.respond(v)
        }
        get("/api/svg/curve") {
            val curve = service.state.activeCurve()
            if (curve == null) call.respond(HttpStatusCode.NotFound, ApiError("no curve"))
            else call.respondText(SvgChart.calibrationCurveSvg(curve, service.state.derived.values.toList()), ContentType.Image.SVG)
        }
        get("/api/svg/posterior/{measurementId}") {
            val id = call.parameters["measurementId"]!!
            val rec = service.state.derived.values.filter { it.input.measurementId == id }.maxByOrNull { it.createdAtEventSeq }
            if (rec == null) call.respond(HttpStatusCode.NotFound, ApiError("no derived calibration"))
            else call.respondText(SvgChart.posteriorSvg(rec), ContentType.Image.SVG)
        }
        get("/api/svg/scenarios/{measurementId}") {
            val id = call.parameters["measurementId"]!!
            val rec = service.state.derived.values.filter { it.input.measurementId == id }.maxByOrNull { it.createdAtEventSeq }
            if (rec == null) call.respond(HttpStatusCode.NotFound, ApiError("no derived calibration"))
            else call.respondText(SvgChart.scenariosSvg(rec), ContentType.Image.SVG)
        }
        get("/api/svg/sequence") {
            call.respondText(
                SvgChart.sequenceSvg(
                    service.state.measurements.values.toList(),
                    service.state.derived.values.groupBy { it.input.measurementId }
                        .mapValues { it.value.maxBy { r -> r.createdAtEventSeq } },
                    service.state.constraints.values.filter { it.active }.toList()
                ), ContentType.Image.SVG
            )
        }
        post("/api/case/load") {
            clock.tick(1)
            val refs = CasePack.load(service) { clock.tick(60) }
            call.respond(mapOf("loaded" to "true", "refs" to refs.toString()))
        }
        post("/api/review") {
            val req = call.receive<ReviewRequest>()
            clock.tick(30)
            val e = service.reviewCandidate(req.measurementId, req.action, req.reason, req.decidedBy)
            call.respond(e)
        }
        post("/api/reverse") {
            val req = call.receive<ReverseRequest>()
            clock.tick(30)
            try {
                call.respond(service.reverseEvent(req.eventId))
            } catch (ex: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, ApiError(ex.message ?: "cannot reverse"))
            }
        }
        post("/api/import") {
            val req = call.receive<ImportSheetRequest>()
            clock.tick(10)
            try {
                val result = ImportLayer.parseLaboratorySheet(req.csv, req.sourceName, "import://pasted/${clock.now().epochSecond}")
                result.accepted.forEach { service.importMeasurement(it.measurement) }
                call.respond(
                    mapOf(
                        "accepted" to result.accepted.map { it.measurement },
                        "rejected" to result.rejected.map { mapOf("row" to it.measurement.source.snippet, "errors" to it.errors) }
                    )
                )
            } catch (ex: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, ApiError(ex.message ?: "format error"))
            }
        }
        get("/api/export") {
            call.respondText(service.exportBundleJson(), ContentType.Application.Json)
        }
        post("/api/import-bundle") {
            val req = call.receive<ImportBundleRequest>()
            try {
                call.respond(service.importBundleJson(req.bundleJson))
            } catch (ex: Exception) {
                call.respond(HttpStatusCode.BadRequest, ApiError(ex.message ?: "invalid bundle"))
            }
        }
        post("/api/reset") {
            service.store.deleteAll()
            service.rebuild()
            call.respond(mapOf("reset" to "true"))
        }
    }
}

private fun kotlinx.serialization.json.JsonElement.jsonPrimitiveOrNull(key: String): String? =
    (this as? kotlinx.serialization.json.JsonObject)?.get(key)?.jsonPrimitive?.content
