package rgsb.web

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json
import rgsb.domain.Hashing
import rgsb.model.DecisionOutcome
import rgsb.model.EventPayload
import rgsb.model.ProjectBundle
import rgsb.store.EventStore
import rgsb.store.Workflow

/**
 * The review workbench UI. It is a real workspace, not a static report: reviewers import
 * the domain case pack, generate candidates, make rulings, revoke them, inspect history
 * (each old event restores the dependencies visible then), and export/reimport.
 */
class WebServer(private val workflow: Workflow, private val indexHtml: String) {

    val json = Json { encodeDefaults = true; prettyPrint = false }

    fun start(host: String, port: Int) = embeddedServer(CIO, host = host, port = port) {
        install(ContentNegotiation) { json(json) }
        routing {
            get("/") { call.respondText(indexHtml, ContentType.Text.Html) }

            get("/api/snapshot") {
                val at = call.request.queryParameters["at"]
                val branch = call.request.queryParameters["branch"] ?: EventStore.MAIN
                val snap = at?.let { workflow.viewAt(it, branch) } ?: workflow.currentView(branch)
                call.respond(snap)
            }

            post("/api/import-pack") {
                val text = call.receiveText()
                val result = workflow.importCasePack(text)
                call.respond(if (result.accepted) HttpStatusCode.OK else HttpStatusCode.UnprocessableEntity, result)
            }

            post("/api/calibrate") {
                val params = call.receiveText().split("&").associate {
                    val (k, v) = it.split("=", "="); k to v
                }
                val measurementId = params["measurementId"] ?: error("measurementId required")
                val curveId = params["curveId"] ?: error("curveId required")
                val offsetId = params["offsetId"]?.takeIf { it.isNotBlank() }
                val branch = params["branch"] ?: EventStore.MAIN
                val result = workflow.calibrate(measurementId, curveId, offsetId, branch)
                call.respond(result)
            }

            post("/api/sequence-check") {
                workflow.runSequenceChecks()
                call.respond(workflow.currentView())
            }

            post("/api/decide") {
                val body = json.decodeFromString(DecisionRequest.serializer(), call.receiveText())
                val outcome: DecisionOutcome = when (body.kind) {
                    "ACCEPTED" -> DecisionOutcome.Accepted(body.intervalId)
                    "REJECTED" -> DecisionOutcome.Rejected(body.reason ?: "")
                    "ADJUSTED" -> DecisionOutcome.Adjusted(
                        body.candidateId ?: error("candidateId required"),
                        json.parseToJsonElement(body.patchJson ?: "{}"),
                    )
                    else -> error("unknown kind ${body.kind}")
                }
                val record = workflow.decide(body.resultId, outcome, body.note, body.branch ?: EventStore.MAIN)
                call.respond(record)
            }

            post("/api/revoke") {
                val body = json.decodeFromString(RevokeRequest.serializer(), call.receiveText())
                workflow.revokeDecision(body.decisionId, body.reason)
                call.respond(workflow.currentView())
            }

            post("/api/branch") {
                val body = json.decodeFromString(BranchRequest.serializer(), call.receiveText())
                call.respond(workflow.branchAt(body.fromEventId, body.name))
            }

            get("/api/svg/curve") {
                val snap = workflow.currentView()
                val highlight = snap.results.firstOrNull()?.let { r ->
                    r.grid.takeIf { it.isNotEmpty() }?.let { g -> g.first().year..g.last().year }
                }
                call.respondText(SvgCharts.calibrationCurve(snap.curves, highlight), ContentType("image", "svg+xml"))
            }

            get("/api/svg/posterior") {
                val resultId = call.request.queryParameters["resultId"] ?: error("resultId required")
                val at = call.request.queryParameters["at"]
                val snap = at?.let { workflow.viewAt(it) } ?: workflow.currentView()
                val result = snap.results.first { it.resultId == resultId }
                call.respondText(SvgCharts.posterior(result), ContentType("image", "svg+xml"))
            }

            get("/api/svg/sequence") {
                call.respondText(SvgCharts.sequence(workflow.currentView().sequenceVerdicts), ContentType("image", "svg+xml"))
            }

            get("/api/svg/offsets") {
                val measurementId = call.request.queryParameters["measurementId"] ?: error("measurementId required")
                val snap = workflow.currentView()
                call.respondText(
                    SvgCharts.offsetHypotheses(snap.results.filter { it.measurementId == measurementId }),
                    ContentType("image", "svg+xml"),
                )
            }

            get("/api/evidence") {
                val intervalId = call.request.queryParameters["intervalId"] ?: error("intervalId required")
                val at = call.request.queryParameters["at"]
                val snap = at?.let { workflow.viewAt(it) } ?: workflow.currentView()
                val interval = snap.results.flatMap { it.intervals }.first { it.intervalId == intervalId }
                val result = snap.results.first { r -> r.intervals.any { it.intervalId == intervalId } }
                call.respond(EvidenceResponse(
                    interval = interval,
                    resultId = result.resultId,
                    measurementId = result.measurementId,
                    algorithmVersion = result.algorithmVersion,
                    parameterFingerprint = result.parameterFingerprint,
                    directSources = result.directSources,
                    computationEventId = result.computationEventId,
                    gridStepYears = result.gridStepYears,
                    contributingCells = result.grid.filter { it.year in interval.yearFrom..interval.yearTo }
                        .map { EvidenceCell(it.year, it.weight, it.unnormalized, it.curveCarbonAge, it.curveSigma) },
                ))
            }

            get("/api/export") {
                val bundle = workflow.exportBundle()
                call.respondText(json.encodeToString(ProjectBundle.serializer(), bundle),
                    ContentType.Application.Json)
            }

            post("/api/import-bundle") {
                val bundle = json.decodeFromString(ProjectBundle.serializer(), call.receiveText())
                workflow.importBundle(bundle)
                call.respond(workflow.currentView())
            }
        }
    }
}

@kotlinx.serialization.Serializable
data class DecisionRequest(
    val resultId: String,
    val kind: String,
    val intervalId: String? = null,
    val reason: String? = null,
    val candidateId: String? = null,
    val patchJson: String? = null,
    val note: String = "",
    val branch: String? = null,
)

@kotlinx.serialization.Serializable
data class RevokeRequest(val decisionId: String, val reason: String)

@kotlinx.serialization.Serializable
data class BranchRequest(val fromEventId: String, val name: String)

@kotlinx.serialization.Serializable
data class EvidenceCell(
    val year: Int,
    val weight: Double,
    val unnormalized: Double,
    val curveCarbonAge: Double,
    val curveSigma: Double,
)

@kotlinx.serialization.Serializable
data class EvidenceResponse(
    val interval: rgsb.model.IntervalEvidence,
    val resultId: String,
    val measurementId: String,
    val algorithmVersion: String,
    val parameterFingerprint: String,
    val directSources: List<String>,
    val computationEventId: String,
    val gridStepYears: Int,
    val contributingCells: List<EvidenceCell>,
)
