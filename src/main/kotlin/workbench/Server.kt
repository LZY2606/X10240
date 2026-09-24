package workbench

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.*

fun Application.workbenchModule(dbPath: String, clock: Clock) {
    fun state() = Store(dbPath, clock).use { it.state() }
    fun <T> withWb(block: (Workbench) -> T): T = Store(dbPath, clock).use { block(Workbench(it)) }

    routing {
        get("/") { call.respondText(Web.page("项目总览", Web.dashboard(state())), ContentType.Text.Html) }

        get("/casepack") { call.respondText(Web.page("离线案例包", Web3.casepackPage()), ContentType.Text.Html) }
        post("/casepack/install-baseline") {
            val r = runCatching { withWb { wb ->
                require(wb.store.eventCount() == 0) { "项目非空：请先在 /reset 删除本地库" }
                CasePack.installBaseline(wb) } }
            call.respondText(Web.page("案例包", Web3.message(r.isSuccess,
                r.map { "基线已导入 ${it.size} 个带来源的输入" }.getOrElse { it.message ?: "失败" })), ContentType.Text.Html)
        }
        post("/casepack/run") {
            val r = runCatching { withWb { wb ->
                require(wb.store.eventCount() == 0) { "项目非空：请先在 /reset 删除本地库" }
                CasePack.runScript(wb) } }
            call.respondText(Web.page("案例包剧本",
                r.map { (logs, checks) -> Web3.scriptResult(logs, checks) }
                    .getOrElse { Web3.message(false, it.message ?: "失败") }), ContentType.Text.Html)
        }

        get("/sources/new") { call.respondText(Web.page("手工导入", Web3.sourceForm()), ContentType.Text.Html) }
        post("/sources/new") {
            val p = call.receiveParameters()
            val r = runCatching { withWb { wb ->
                wb.submitSource(SourceKind.valueOf(p["kind"]!!),
                    Json.parseToJsonElement(p["payload"]!!).jsonObject,
                    p["provenance"]!!, p["unit"]!!, family = p["family"]?.ifBlank { null }) } }
            call.respondText(Web.page("导入结果", Web3.message(r.isSuccess,
                r.map { "已提交源输入 $it（原始表示不可覆盖）" }.getOrElse { it.message ?: "失败" })), ContentType.Text.Html)
        }
        get("/sources/{id}") {
            val st = state()
            call.respondText(Web.page("来源详情", Web2.sourceDetail(st, call.parameters["id"]!!)), ContentType.Text.Html)
        }
        post("/sources/{id}/revise") {
            val p = call.receiveParameters()
            val r = runCatching { withWb { wb ->
                wb.reviseSource(call.parameters["id"]!!,
                    Json.parseToJsonElement(p["payload"]!!).jsonObject, p["provenance"]!!) } }
            call.respondText(Web.page("修订结果", Web3.message(r.isSuccess,
                r.map { "修订已提交为新版本 $it（父版本保持不变）" }.getOrElse { it.message ?: "失败" })), ContentType.Text.Html)
        }

        get("/calibrate/new") { call.respondText(Web.page("产生校准候选", Web3.calibrateForm(state())), ContentType.Text.Html) }
        post("/calibrate/new") {
            val p = call.receiveParameters()
            val r = runCatching { withWb { wb ->
                wb.recordCalibration(p["measurement"]!!, p["curve"]!!,
                    p["offset"]?.ifBlank { null }, p["gridLo"]?.ifBlank { null }?.toDoubleOrNull(),
                    p["gridHi"]?.ifBlank { null }?.toDoubleOrNull()) } }
            call.respondText(Web.page("校准结果", Web3.message(r.isSuccess,
                r.map { "校准候选已记录为派生事件 $it" }.getOrElse { it.message ?: "失败" })), ContentType.Text.Html)
        }
        get("/sequence/new") { call.respondText(Web.page("层序检查", Web3.sequenceForm(state())), ContentType.Text.Html) }
        post("/sequence/new") {
            val p = call.receiveParameters()
            val r = runCatching { withWb { wb ->
                wb.recordSequenceCheck(p["constraint"]!!, p["beforeCal"]!!, p["afterCal"]!!) } }
            call.respondText(Web.page("层序检查", Web3.message(r.isSuccess,
                r.map { "层序检查已记录为派生事件 $it" }.getOrElse { it.message ?: "失败" })), ContentType.Text.Html)
        }

        get("/derived/{id}") {
            val st = state()
            call.respondText(Web.page("派生结果", Web2.derivedDetail(st, call.parameters["id"]!!)), ContentType.Text.Html)
        }
        get("/derived/{id}/evidence") {
            val id = call.parameters["id"]!!
            val lo = call.request.queryParameters["lo"]!!.toDouble()
            val hi = call.request.queryParameters["hi"]!!.toDouble()
            val st = state()
            val rec = st.derived[id]
            if (rec == null) { call.respondText("not found", status = HttpStatusCode.NotFound); return@get }
            val res = Json.decodeFromString(CalibrationResult.serializer(), rec.payload)
            val ev = res.evidenceFor(lo, hi)
            if (call.request.queryParameters["format"] == "json")
                call.respondText(Json.encodeToString(IntegrationEvidence.serializer(), ev), ContentType.Application.Json)
            else
                call.respondText(Web.page("积分证据", Web3.evidencePage(ev, id)), ContentType.Text.Html)
        }

        post("/decisions") {
            val p = call.receiveParameters()
            val r = runCatching { withWb { wb ->
                wb.decide(p["targetType"]!!, p["targetId"]!!, p["verdict"]!!,
                    p["reason"]!!, p["author"]!!, labCode = p["labCode"]?.ifBlank { null },
                    adjustPayload = p["newPayload"]?.ifBlank { null }?.let { Json.parseToJsonElement(it).jsonObject },
                    adjustProvenance = p["adjustProvenance"]?.ifBlank { null },
                    reviseSourceId = p["reviseSourceId"]?.ifBlank { null }) } }
            call.respondText(Web.page("裁定", Web3.message(r.isSuccess,
                r.map { "裁定已写入事件历史：$it" }.getOrElse { it.message ?: "失败" })), ContentType.Text.Html)
        }
        post("/decisions/{id}/void") {
            val r = runCatching { withWb { it.voidDecision(call.parameters["id"]!!, "界面撤销") } }
            call.respondText(Web.page("撤销", Web3.message(r.isSuccess,
                r.map { "撤销事件已写入：$it" }.getOrElse { it.message ?: "失败" })), ContentType.Text.Html)
        }
        get("/decisions") { call.respondRedirect("/") }

        get("/history") { call.respondText(Web.page("事件历史", Web3.historyList(state())), ContentType.Text.Html) }
        get("/history/{seq}") {
            val seq = call.parameters["seq"]!!.toLong()
            val st = Store(dbPath, clock).use { it.state(seq) }
            call.respondText(Web.page("历史视图 @$seq", Web3.historyView(st, seq)), ContentType.Text.Html)
        }

        get("/export") {
            val dump = Store(dbPath, clock).use { it.snapshotJson() }
            call.response.header(HttpHeaders.ContentDisposition, "attachment; filename=workbench-snapshot.json")
            call.respondText(dump, ContentType.Application.Json)
        }
        get("/import-snapshot") { call.respondText(Web.page("导入快照", Web3.importPage()), ContentType.Text.Html) }
        post("/import-snapshot") {
            val text = call.receiveParameters()["snapshot"] ?: ""
            val r = runCatching { withWb { it.store.restoreSnapshot(text) } }
            call.respondText(Web.page("导入快照", Web3.message(r.isSuccess,
                r.map { "已恢复 $it 个事件；身份、顺序与来源关系保持" }
                    .getOrElse { (it as? ImportException)?.errors?.joinToString("<br>") ?: (it.message ?: "失败") })),
                ContentType.Text.Html)
        }
        get("/reset") { call.respondText(Web.page("删除本地库", """
            <div class='card'><p>删除本地 SQLite 数据库（事件全部清除）。建议先<a href='/export'>导出档案</a>。</p>
            <form method='post' action='/reset'><button class='danger'>确认删除本地库</button></form></div>
        """.trimIndent()), ContentType.Text.Html) }
        post("/reset") {
            Store(dbPath, clock).use { it.wipe() }
            call.respondText(Web.page("删除本地库", Web3.message(true, "本地数据库已清空；可通过导入快照恢复。")), ContentType.Text.Html)
        }
    }
}

fun startServer(host: String, port: Int, dbPath: String, clock: Clock) {
    embeddedServer(Netty, port = port, host = host) {
        workbenchModule(dbPath, clock)
    }.start(wait = true)
}
