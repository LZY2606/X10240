package gsb

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

fun esc(s: String): String = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    .replace("\"", "&quot;")

fun fmt(d: Double, n: Int = 4): String = "%.${n}f".format(d)

fun urlenc(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")

val prettyJson = Json { prettyPrint = true }

fun Application.configureRouting(wb: Workbench) {
    routing {
        get("/healthz") { call.respondText("ok") }

        get("/") {
            val asof = call.parameters["asof"]?.toIntOrNull()
            val st = if (asof == null) wb.state else ProjectState.replay(wb.state.events, asof)
            call.respondText(renderDashboard(wb, st, asof), ContentType.Text.Html)
        }

        get("/import") { call.respondText(renderImportPage(wb), ContentType.Text.Html) }

        post("/import") {
            val p = call.receiveParameters()
            val batch = Json.parseToJsonElement(p["batch"] ?: "{}").jsonObject
            wb.applyBatch(batch)
            call.respondRedirect("/")
        }

        post("/casepack/{name}") {
            wb.applyCasepackStep(call.parameters["name"]!!)
            call.respondRedirect("/")
        }

        post("/compute") {
            val p = call.receiveParameters()
            val era = wb.state.sourcesOfKind("eraSystem").maxByOrNull { it.version }?.sourceId
                ?: error("import an era system first")
            val d = wb.compute(p["measurement"]!!, p["curve"]!!, p["offset"]!!, era,
                p["step"]?.toIntOrNull() ?: 1)
            call.respondRedirect("/derived/${urlenc(d.derivedId)}")
        }

        get("/derived/{id}") {
            val id = call.parameters["id"]!!
            val d = wb.state.derived[id]
            if (d == null) { call.respondText("not found", status = HttpStatusCode.NotFound); return@get }
            call.respondText(renderDerived(wb, d, call.parameters["evidence"]), ContentType.Text.Html)
        }

        post("/adjudicate") {
            val p = call.receiveParameters()
            wb.adjudicate(p["derivedId"]!!, p["intervalIndex"]!!.toInt(),
                p["decision"]!!, p["reason"] ?: "", p["reviewer"] ?: "reviewer:anonymous")
            call.respondRedirect("/derived/${urlenc(p["derivedId"]!!)}")
        }

        post("/undo") {
            val p = call.receiveParameters()
            wb.undo(p["targetEventId"]!!, p["reason"] ?: "reviewer undo")
            call.respondRedirect(p["back"] ?: "/")
        }

        get("/stratigraphy") { call.respondText(renderStratigraphy(wb), ContentType.Text.Html) }

        post("/candidate/{id}") {
            val p = call.receiveParameters()
            wb.adjudicateCandidate(call.parameters["id"]!!, p["decision"]!!,
                p["reason"] ?: "", p["reviewer"] ?: "reviewer:anonymous")
            call.respondRedirect("/stratigraphy")
        }

        get("/events") { call.respondText(renderEventList(wb), ContentType.Text.Html) }

        get("/export") {
            val body = prettyJson.encodeToString(JsonObject.serializer(), wb.exportJson())
            call.response.header(HttpHeaders.ContentDisposition, "attachment; filename=\"gsb-export.json\"")
            call.respondText(body, ContentType.Application.Json)
        }

        post("/import-log") {
            val p = call.receiveParameters()
            val json = Json.parseToJsonElement(p["log"] ?: "{}").jsonObject
            wb.importLog(json)
            call.respondRedirect("/")
        }
    }
}

fun page(title: String, body: String, banner: String = ""): String = """<!DOCTYPE html>
<html lang="zh"><head><meta charset="utf-8"><title>${esc(title)}</title>
<style>
 body{font-family:-apple-system,'PingFang SC',sans-serif;margin:0;background:#f6f5f1;color:#222}
 nav{background:#243447;color:#fff;padding:8px 18px;font-size:14px}
 nav a{color:#cfe0ff;margin-right:14px;text-decoration:none}
 main{padding:18px;max-width:1080px;margin:0 auto}
 h1{font-size:20px} h2{font-size:16px;margin-top:26px;border-left:4px solid #4a6fa5;padding-left:8px}
 table{border-collapse:collapse;width:100%;background:#fff;font-size:13px}
 th,td{border:1px solid #d8d4c8;padding:4px 8px;text-align:left;vertical-align:top}
 th{background:#ecebe4}
 .stale{background:#f3e4e0} .ok{color:#1a7a3c;font-weight:600} .bad{color:#a23122;font-weight:600}
 .warn{color:#9a6a00;font-weight:600} .muted{color:#777}
 .card{background:#fff;border:1px solid #d8d4c8;border-radius:6px;padding:12px;margin:10px 0}
 details{background:#fbfaf5;border:1px dashed #b9b29a;border-radius:4px;padding:6px 10px;margin:4px 0}
 summary{cursor:pointer;font-weight:600;color:#3a4f6e}
 code{background:#efece2;padding:1px 4px;border-radius:3px;font-size:12px}
 textarea{width:100%;height:200px;font-family:ui-monospace,monospace;font-size:12px}
 input[type=text]{width:220px}
 .pill{display:inline-block;padding:1px 8px;border-radius:10px;font-size:12px;background:#e3e9f2;color:#243447}
 .banner{background:#fff4d6;border:1px solid #d9b24a;padding:8px 12px;border-radius:6px;margin-bottom:10px}
 svg{background:#fff;border:1px solid #d8d4c8;border-radius:6px}
 .flow li{margin:4px 0}
</style></head><body>
<nav><b>放射性碳年代工作台</b>
 <a href="/">总览</a><a href="/import">导入/案例包</a><a href="/stratigraphy">层序关系</a>
 <a href="/events">事件历史</a><a href="/export">导出</a></nav>
<main>
$banner
$body
</main></body></html>"""
