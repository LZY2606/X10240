package c14.web

import c14.domain.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.http.content.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.io.File

@Serializable data class StartRequest(val projectId: String, val title: String, val eraSystem: String = "BP/BCE-CE astronomical", val notes: String = "", val actor: String = "reviewer")
@Serializable data class CommandRequest(val commands: List<Command>, val tickVirtual: Boolean = true)
@Serializable data class EventsAt(val seq: Int)
@Serializable data class EvidenceResponse(
    val intervalId: String,
    val fromBp: Int,
    val toBp: Int,
    val probability: Double,
    val level: Double,
    val modeBp: Int,
    val modeLabel: String,
    val integrationRule: String,
    val cells: List<Cell>,
    val algoVersion: String,
    val paramFingerprint: String,
    val directSources: List<String>,
    val resultId: String,
    val measurementId: String,
    val curveId: String,
    val offsetId: String?
)

data class ServerEnv(val app: AppEngine, val clock: Clock, val dataDir: File)

fun Application.webModule(env: ServerEnv) {
    install(ContentNegotiation) { json(domainJson) }
    val engine = env.app.engine

    fun ApplicationCall.pid(): String = parameters["id"]!!
    fun requireSnap(id: String, at: Int? = null): ProjectSnapshot =
        if (at != null) engine.snapshotAt(id, at) else engine.snapshot(id)

    routing {
        get("/api/health") { call.respond(buildJsonObject { put("status", "ok"); put("algo", ALGO_VERSION) }) }

        get("/api/projects") {
            call.respond(JsonArray(engine.listProjects().map { id ->
                val snap = engine.snapshot(id)
                buildJsonObject { put("projectId", id); put("title", snap.title); put("events", snap.eventCount) }
            }))
        }

        post("/api/projects") {
            val req = call.receive<StartRequest>()
            if (env.clock is VirtualClock) env.clock.tick()
            engine.startProject(req.projectId, req.title, req.eraSystem, req.notes, req.actor)
            call.respond(buildJsonObject { put("projectId", req.projectId); put("ok", true) })
        }

        delete("/api/projects/{id}") {
            val id = call.parameters["id"]!!
            env.app.store.deleteProject(id)
            engine.forget(id)
            call.respond(buildJsonObject { put("ok", true) })
        }

        get("/api/projects/{id}/state") {
            if (!engine.hasProject(call.parameters["id"]!!)) return@get call.respond(HttpStatusCode.NotFound, buildJsonObject { put("error", "unknown project") })
            call.respond(stateDto(engine.snapshot(call.parameters["id"]!!)))
        }

        get("/api/projects/{id}/state/at/{seq}") {
            if (!engine.hasProject(call.parameters["id"]!!)) return@get call.respond(HttpStatusCode.NotFound, buildJsonObject { put("error", "unknown project") })
            call.respond(stateDto(engine.snapshotAt(call.parameters["id"]!!, call.parameters["seq"]!!.toInt())))
        }

        get("/api/projects/{id}/events") {
            if (!engine.hasProject(call.parameters["id"]!!)) return@get call.respond(HttpStatusCode.NotFound, buildJsonObject { put("error", "unknown project") })
            call.respond(JsonArray(engine.eventLog(call.parameters["id"]!!).map { domainJson.encodeToJsonElement(Event.serializer(), it) }))
        }

        post("/api/projects/{id}/commands") {
            val id = call.parameters["id"]!!
            val req = call.receive<CommandRequest>()
            val all = mutableListOf<Event>()
            for (c in req.commands) {
                if (env.clock is VirtualClock && req.tickVirtual) env.clock.tick()
                all += engine.command(id, c)
            }
            call.respond(buildJsonObject {
                put("events", JsonArray(all.map { domainJson.encodeToJsonElement(Event.serializer(), it) }))
                put("state", domainJson.encodeToJsonElement(StateDto.serializer(), stateDto(engine.snapshot(id))))
            })
        }

        get("/api/projects/{id}/evidence") {
            val id = call.parameters["id"]!!
            val intervalId = call.request.queryParameters["interval"] ?: throw IllegalArgumentException("interval required")
            val at = call.request.queryParameters["at"]?.toInt()
            val snap = if (at != null) engine.snapshotAt(id, at) else engine.snapshot(id)
            val result = snap.results.firstOrNull { r ->
                (r.result.intervals68 + r.result.intervals95).any { it.id == intervalId }
            }?.result
            if (result == null) {
                call.respond(HttpStatusCode.NotFound, buildJsonObject { put("error", "interval not found: $intervalId") })
                return@get
            }
            val ivl = (result.intervals68 + result.intervals95).first { it.id == intervalId }
            call.respond(
                EvidenceResponse(
                    intervalId = ivl.id,
                    fromBp = ivl.fromBp,
                    toBp = ivl.toBp,
                    probability = ivl.probability,
                    level = ivl.level,
                    modeBp = ivl.modeBp,
                    modeLabel = Era.label(ivl.modeBp),
                    integrationRule = "normalized trapezoid integration on fixed ${result.gridStep}-yr grid; cells ${ivl.evidenceFromIndex}..${ivl.evidenceToIndex}",
                    cells = ivl.cells,
                    algoVersion = result.algoVersion,
                    paramFingerprint = result.paramFingerprint,
                    directSources = result.directSources,
                    resultId = result.id,
                    measurementId = result.measurementId,
                    curveId = result.curveId,
                    offsetId = result.offsetId
                )
            )
        }

        get("/api/projects/{id}/svg/{kind}") {
            val id = call.parameters["id"]!!
            val kind = call.parameters["kind"]!!
            val at = call.request.queryParameters["at"]?.toInt()
            val snap = if (at != null) engine.snapshotAt(id, at) else engine.snapshot(id)
            val svg = when (kind) {
                "curve" -> {
                    val sample = call.request.queryParameters["sample"]
                    val curve = sample?.let { snap.latestResult(it)?.curveId }?.let { cid -> snap.curves.firstOrNull { it.id == cid } }
                        ?: snap.curves.maxByOrNull { snap.curves.indexOf(it) }
                    val m = sample?.let { snap.measurements.filter { mm -> mm.sampleId == sample }.lastOrNull() }
                    if (curve == null) "<svg xmlns='http://www.w3.org/2000/svg' width='400' height='80'><text x='10' y='30'>no curve</text></svg>"
                    else Svg.curve(curve, m)
                }
                "density" -> {
                    val sample = call.request.queryParameters["sample"]!!
                    val rv = snap.results.lastOrNull { it.result.sampleId == sample }
                        ?: throw IllegalArgumentException("no result for $sample")
                    val m = snap.measurements.first { it.id == rv.result.measurementId }
                    Svg.density(rv.result, m)
                }
                "sequence" -> Svg.sequence(snap)
                else -> throw IllegalArgumentException("unknown svg kind: $kind")
            }
            call.respondText(svg, ContentType("image", "svg+xml"))
        }

        post("/api/import/baseline") {
            val bundle = call.receive<BaselineBundle>()
            if (env.clock is VirtualClock) env.clock.tick()
            val report = Importer.importBaseline(engine, bundle)
            call.respond(buildJsonObject {
                put("report", domainJson.encodeToJsonElement(ImportReport.serializer(), report))
                if (report.accepted) put("state", domainJson.encodeToJsonElement(StateDto.serializer(), stateDto(engine.snapshot(bundle.projectId))))
                else put("state", JsonNull)
            })
        }

        post("/api/import/events") {
            val bundle = call.receive<EventBundle>()
            val report = Importer.importEvents(engine, bundle)
            call.respond(buildJsonObject {
                put("report", domainJson.encodeToJsonElement(ImportReport.serializer(), report))
                if (report.accepted) put("state", domainJson.encodeToJsonElement(StateDto.serializer(), stateDto(engine.snapshot(bundle.projectId))))
                else put("state", JsonNull)
            })
        }

        get("/api/projects/{id}/export") {
            val id = call.parameters["id"]!!
            val snap = engine.snapshot(id)
            call.respond(Importer.exportEvents(engine, id, snap.title))
        }

        get("/api/cases") {
            call.respond(listCases())
        }

        get("/api/cases/{name}") {
            val name = call.parameters["name"]!!
            call.respondText(caseText(name), ContentType.Application.Json)
        }

        get("/") { call.respondRedirect("/web/index.html", permanent = false) }
        static("/web") { resources("web") }
    }
}

@Serializable
data class StateDto(
    val projectId: String,
    val title: String,
    val eraSystem: String,
    val notes: String,
    val eventCount: Int,
    val sources: List<Source>,
    val curves: List<CalibrationCurve>,
    val curveFamilies: Map<String, String>,
    val offsets: List<OffsetModel>,
    val measurements: List<Measurement>,
    val assumptions: List<Assumption>,
    val links: List<SequenceLink>,
    val exclusions: List<Exclusion>,
    val decisions: List<DecisionView>,
    val conflicts: List<DecisionConflict>,
    val acceptedIntervals: Set<String>,
    val rejectedIntervals: Set<String>,
    val results: List<ResultView>,
    val analyses: List<SequenceAnalysis>,
    val samples: List<String>
)

fun stateDto(snap: ProjectSnapshot): StateDto = StateDto(
    projectId = snap.projectId,
    title = snap.title,
    eraSystem = snap.eraSystem,
    notes = snap.notes,
    eventCount = snap.eventCount,
    sources = snap.sources,
    curves = snap.curves,
    curveFamilies = snap.curveFamilies,
    offsets = snap.offsets,
    measurements = snap.measurements,
    assumptions = snap.assumptions.values.sortedBy { it.eventSeq },
    links = snap.links,
    exclusions = snap.exclusions,
    decisions = snap.decisions,
    conflicts = snap.conflicts,
    acceptedIntervals = snap.acceptedIntervals,
    rejectedIntervals = snap.rejectedIntervals,
    results = snap.results,
    analyses = snap.allAnalyses,
    samples = snap.measurements.map { it.sampleId }.distinct()
)

private val caseDir = File("cases/c14-offline-bench").let { if (it.exists()) it else File("app/cases/c14-offline-bench") }

fun listCases(): List<JsonObject> {
    val dir = caseDir
    if (!dir.exists()) return emptyList()
    return dir.listFiles { f -> f.extension == "json" }!!.sortedBy { it.name }.map {
        buildJsonObject {
            put("name", it.name)
            put("title", runCatching {
                (domainJson.parseToJsonElement(it.readText() as String) as? JsonObject)?.get("title")?.jsonPrimitive?.content ?: it.name
            }.getOrDefault(it.name))
        }
    }
}

fun caseText(name: String): String {
    val safe = name.substringAfterLast('/').substringAfterLast('\\')
    val file = File(caseDir, safe)
    require(file.exists() && file.extension == "json") { "unknown case: $name" }
    return file.readText()
}

