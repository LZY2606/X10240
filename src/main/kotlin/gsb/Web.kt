package gsb

import io.ktor.http.ContentType
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.decodeFromString
import java.net.URLEncoder

private fun he(s: String?): String = (s ?: "").replace("&", "&amp;").replace("<", "&lt;")
    .replace(">", "&gt;").replace("\"", "&quot;")

private fun enc(s: String): String = URLEncoder.encode(s, Charsets.UTF_8)

private fun page(title: String, body: String): String = """<!DOCTYPE html>
<html><head><meta charset='utf-8'><title>${he(title)}</title>
<style>
 body{font-family:-apple-system,Segoe UI,Arial,sans-serif;margin:0;background:#f6f8fa;color:#1f2328}
 header{background:#24292f;color:#fff;padding:10px 20px}
 header a{color:#9ecbff;margin-right:14px;text-decoration:none;font-size:14px}
 main{padding:18px 20px;max-width:1080px;margin:0 auto}
 h1{font-size:20px} h2{font-size:16px;margin-top:26px;border-bottom:1px solid #d0d7de;padding-bottom:4px}
 table{border-collapse:collapse;width:100%;margin:8px 0;font-size:13px;background:#fff}
 th,td{border:1px solid #d0d7de;padding:5px 8px;text-align:left;vertical-align:top}
 th{background:#f0f3f6}
 .id{font-family:ui-monospace,Menlo,monospace;font-size:11px;color:#57606a}
 .tag{display:inline-block;padding:1px 7px;border-radius:10px;font-size:11px;color:#fff;background:#57606a}
 .cur{background:#1a7f37} .stale{background:#bf8700} .acc{background:#1a7f37} .rej{background:#d1242f} .adj{background:#8250df}
 .card{background:#fff;border:1px solid #d0d7de;border-radius:6px;padding:10px 14px;margin:8px 0}
 .banner{background:#fff8c5;border:1px solid #d4a72c;border-radius:6px;padding:8px 12px;margin:8px 0}
 .err{background:#ffebe9;border:1px solid #d1242f;border-radius:6px;padding:8px 12px;margin:8px 0}
 .ok{background:#dafbe1;border:1px solid #1a7f37;border-radius:6px;padding:8px 12px;margin:8px 0}
 details{margin:4px 0} summary{cursor:pointer;font-size:12px;color:#57606a}
 pre{white-space:pre-wrap;font-size:11px;background:#f6f8fa;padding:6px;border-radius:4px;margin:4px 0}
 form{display:inline} input[type=text],select,textarea{font-size:13px;padding:3px 6px}
 button{font-size:13px;padding:4px 10px;border-radius:5px;border:1px solid #d0d7de;background:#f6f8fa;cursor:pointer}
 button.primary{background:#1f6feb;color:#fff;border-color:#1f6feb}
</style></head><body>
<header><b>放射性碳/年代校准/多峰分布工作台</b><br>
<a href='/'>工作台</a><a href='/curves'>校准曲线</a><a href='/sequence'>层序关系</a>
<a href='/events'>事件历史</a><a href='/io'>导入/导出</a></header>
<main>
$body
</main></body></html>"""

class WebApp(val eng: Engine) {

    private fun asOf(call: ApplicationCall): Long =
        call.request.queryParameters["asOf"]?.toLongOrNull() ?: Long.MAX_VALUE

    fun Application.module() {
        routing {
            get("/") { dashboard(call) }
            get("/curves") { curvesPage(call) }
            get("/derived/{id}") { derivedPage(call) }
            get("/sequence") { sequencePage(call) }
            get("/compare/{ms}") { comparePage(call) }
            get("/events") { eventsPage(call, null) }
            get("/events/{seq}") { eventsPage(call, call.parameters["seq"]!!.toLong()) }
            get("/io") { ioPage(call) }
            get("/export") { export(call) }

            post("/import/baseline") {
                CasePack.applyBaseline(eng)
                CasePack.calibrateAll(eng)
                call.respondRedirect("/")
            }
            post("/import/revisions") {
                CasePack.applyRevisions(eng)
                call.respondRedirect("/")
            }
            post("/import/text") {
                val p = call.receiveParameters()
                try {
                    val sources = parseSampleSources(p["payload"].orEmpty())
                    sources.forEach { eng.submitSource(it.kind, it.sourceId, it.unit, it.provenance, it.raw, "reviewer") }
                    call.respondRedirect("/?msg=" + enc("imported ${sources.size} source(s)"))
                } catch (e: Exception) {
                    call.respondRedirect("/io?err=" + enc(e.message ?: "format error"))
                }
            }
            post("/import/file") {
                val p = call.receiveParameters()
                try {
                    val b = eng.importFromString(p["payload"].orEmpty(), "reviewer")
                    call.respondRedirect("/?msg=" + enc("re-imported ${b.sourceVersions.size} source versions, ${b.derived.size} derived, ${b.events.size} events"))
                } catch (e: Exception) {
                    call.respondRedirect("/io?err=" + enc(e.message ?: "import failed"))
                }
            }
            post("/calibrate") {
                val p = call.receiveParameters()
                try {
                    val d = eng.calibrate(
                        p["measurement"]!!, p["curve"] ?: "curve-labcal",
                        p["offset"]?.ifBlank { null }, p["era"] ?: "era-bce-ce", "reviewer"
                    )
                    call.respondRedirect("/derived/${d.id}")
                } catch (e: Exception) {
                    call.respondRedirect("/?err=" + enc(e.message ?: "calibration failed"))
                }
            }
            post("/sequence/run") {
                val constraints = eng.store.latestSources().values.filter { it.kind == SourceKind.SEQUENCE }
                constraints.forEach { runCatching { eng.sequenceCheck(it.sourceId, "reviewer") } }
                call.respondRedirect("/sequence")
            }
            post("/adjudicate") {
                val p = call.receiveParameters()
                try {
                    eng.adjudicate(p["derivedId"]!!, p["verdict"]!!, p["reason"].orEmpty(), "reviewer")
                } catch (e: Exception) {
                    call.respondRedirect("/?err=" + enc(e.message ?: "adjudication failed"))
                    return@post
                }
                call.respondRedirect(p["back"] ?: "/")
            }
            post("/undo") {
                val p = call.receiveParameters()
                try {
                    eng.undo(p["seq"]!!.toLong(), p["reason"].orEmpty(), "reviewer")
                } catch (e: Exception) {
                    call.respondRedirect("/events?err=" + enc(e.message ?: "undo failed"))
                    return@post
                }
                call.respondRedirect("/events")
            }
        }
    }

    /** Copyable mini format: blocks separated by blank lines; header line
     *  "# kind=MEASUREMENT id=... unit=... provenance=..." then raw JSON body. */
    fun parseSampleSources(text: String): List<PackSource> {
        if (text.isBlank()) throw IllegalArgumentException("empty payload")
        jsonFmt.runCatching { jsonFmt.decodeFromString<List<PackSource>>(text) }.getOrNull()?.let { return it }
        val out = mutableListOf<PackSource>()
        for (block in text.trim().split(Regex("\n[ \t]*\n"))) {
            val lines = block.trim().lines()
            val header = lines.firstOrNull()?.takeIf { it.startsWith("#") }
                ?: throw IllegalArgumentException("block must start with '# kind=... id=...' header")
            val kv = Regex("""(\w+)=("[^"]*"|\S+)""").findAll(header).associate {
                it.groupValues[1] to it.groupValues[2].trim('"')
            }
            val kind = kv["kind"] ?: throw IllegalArgumentException("missing kind in header")
            if (kind !in SourceKind.ALL) throw IllegalArgumentException("unknown kind '$kind'")
            val id = kv["id"] ?: throw IllegalArgumentException("missing id in header")
            val raw = lines.drop(1).joinToString("\n").trim()
            if (!raw.startsWith("{")) throw IllegalArgumentException("raw body must be JSON for $id")
            out.add(PackSource(kind, id, kv["unit"] ?: "", kv["provenance"] ?: "", raw))
        }
        return out
    }

    private fun msg(call: ApplicationCall): String {
        val ok = call.request.queryParameters["msg"]
        val err = call.request.queryParameters["err"]
        return when {
            ok != null -> "<div class='ok'>${he(ok)}</div>"
            err != null -> "<div class='err'>${he(err)}</div>"
            else -> ""
        }
    }

    private fun parentLine(sv: SourceVersion): String =
        if (sv.parentId != null) "<br><span class='id'>parent: ${sv.parentId}</span>" else ""

    private fun verdictBadge(v: VerdictInfo): String {
        val cls = when (v.verdict) { Verdict.ACCEPTED -> "acc"; Verdict.REJECTED -> "rej"; else -> "adj" }
        return "<span class='tag $cls'>${v.verdict}</span><br><span class='id'>${he(v.actor)} @<a href='/events/${v.eventSeq}'>#${v.eventSeq}</a><br>${he(v.reason)}</span>"
    }

    private fun atSuffix(at: Long) = if (at == Long.MAX_VALUE) "" else "?asOf=$at"

    // ---------------- dashboard ----------------

    private suspend fun dashboard(call: ApplicationCall) {
        val at = asOf(call)
        val latest = eng.store.latestSources(at)
        val views = eng.derivedViews(at)
        val verdicts = eng.verdicts(at)
        val sb = StringBuilder()
        if (at != Long.MAX_VALUE) {
            sb.append("<div class='banner'>历史视图：截止事件 <b>#$at</b>。展示当时的依赖版本与可见结论，不用最新结果覆盖历史。 <a href='/'>回到当前</a></div>")
        }
        sb.append(msg(call))
        sb.append("<h1>碳年候选席：校准曲线、储库偏移与多峰年代证据</h1>")
        sb.append("<p><form method='post' action='/import/baseline'><button class='primary'>导入离线基线案例包</button></form> ")
        sb.append("<form method='post' action='/import/revisions'><button>应用修订包（参数修订/局部改动/曲线升版/冲突裁定）</button></form> ")
        sb.append("<a href='/export'><button type='button'>导出档案 JSON</button></a> ")
        sb.append("<a href='/io'><button type='button'>导入/粘贴/重新导入</button></a></p>")

        sb.append("<h2>源输入（原始表示不可覆盖；修订 = 新版本 + 父版本链）</h2>")
        sb.append("<table><tr><th>逻辑ID</th><th>类型</th><th>版本</th><th>单位/基准</th><th>来源</th><th>原始表示 / 诊断</th></tr>")
        for ((sid, sv) in latest.toSortedMap()) {
            sb.append("<tr><td><b>${he(sid)}</b><br><span class='id'>${sv.id}</span>${parentLine(sv)}</td>")
            sb.append("<td>${sv.kind}</td><td>v${sv.version}</td><td>${he(sv.unit)}</td><td>${he(sv.provenance)}</td>")
            sb.append("<td><details><summary>原始值 / 规范化</summary>")
            sb.append("<pre>${he(sv.raw)}</pre>")
            if (sv.dataJson != null) sb.append("<pre>${he(sv.dataJson)}</pre>")
            if (sv.diagnostics.isNotEmpty()) sb.append("<div class='err'>${he(sv.diagnostics.joinToString("; "))}</div>")
            sb.append("</details></td></tr>")
        }
        sb.append("</table>")

        val measurements = latest.values.filter { it.kind == SourceKind.MEASUREMENT }
        val offsets = latest.values.filter { it.kind == SourceKind.OFFSET }
        if (measurements.isNotEmpty() && latest.values.any { it.kind == SourceKind.CURVE } && latest.values.any { it.kind == SourceKind.ERA_SYSTEM }) {
            sb.append("<h2>产生候选（测量 × 曲线 × 偏移假设）</h2><form method='post' action='/calibrate'>")
            sb.append("<select name='measurement'>")
            measurements.sortedBy { it.sourceId }.forEach { sb.append("<option value='${it.sourceId}'>${he(it.sourceId)}</option>") }
            sb.append("</select> <select name='offset'><option value=''>无偏移 dR=0</option>")
            offsets.sortedBy { it.sourceId }.forEach { sb.append("<option value='${it.sourceId}'>${he(it.sourceId)}</option>") }
            sb.append("</select> <button class='primary' type='submit'>校准并查看多峰候选</button></form>")
        }

        sb.append("<h2>派生结果（算法版本 / 参数指纹 / 直接来源 / 过期标记 / 人工裁定）</h2>")
        sb.append("<table><tr><th>稳定身份</th><th>类型</th><th>算法</th><th>对象</th><th>状态</th><th>裁定</th><th>直接来源</th><th>事件</th></tr>")
        for (v in views.sortedBy { it.record.createdSeq }) {
            val d = v.record
            val obj = when (d.kind) {
                DerivedKind.CALIBRATION -> jsonFmt.decodeFromString(CalibrationResult.serializer(), d.payload).labCode
                else -> jsonFmt.decodeFromString(SequenceCheckResult.serializer(), d.payload).constraintSourceId
            }
            sb.append("<tr><td><a href='/derived/${d.id}${atSuffix(at)}'>${d.id}</a><br><span class='id'>fp:${d.fingerprint.take(16)}</span></td>")
            sb.append("<td>${d.kind}</td><td>${d.algoVersion}</td><td>${he(obj)}</td>")
            if (v.status == DerivedState.CURRENT) sb.append("<td><span class='tag cur'>CURRENT</span></td>")
            else sb.append("<td><span class='tag stale'>STALE</span><br><span class='id'>${he(v.staleBecause.joinToString("; "))}</span></td>")
            val verdict = verdicts[d.id]
            sb.append("<td>${if (verdict == null) "—" else verdictBadge(verdict)}</td>")
            sb.append("<td><span class='id'>${d.deps.joinToString("<br>")}</span></td>")
            sb.append("<td><a href='/events/${d.createdSeq}'>#${d.createdSeq}</a></td></tr>")
        }
        sb.append("</table>")
        call.respondText(page("工作台", sb.toString()), ContentType.Text.Html)
    }

    // ---------------- curves ----------------

    private suspend fun curvesPage(call: ApplicationCall) {
        val at = asOf(call)
        val versions = eng.store.sourceVersions(at).filter { it.kind == SourceKind.CURVE && it.dataJson != null }
        val bySource = versions.groupBy { it.sourceId }
        val sb = StringBuilder()
        sb.append("<h1>校准曲线（固定版本下插值与归一化可重现）</h1>")
        for ((sid, svs) in bySource) {
            val curves = svs.sortedBy { it.version }.map {
                jsonFmt.decodeFromString(CurveData.serializer(), it.dataJson!!) to "${it.sourceId} v${it.version} (${it.id.take(12)}…)"
            }
            sb.append("<div class='card'>")
            sb.append(curveSvg(curves))
            sb.append("<table><tr><th>版本身份</th><th>曲线版本</th><th>点数</th><th>域 (cal BP)</th><th>来源</th></tr>")
            for (sv in svs.sortedBy { it.version }) {
                val c = jsonFmt.decodeFromString(CurveData.serializer(), sv.dataJson!!)
                sb.append("<tr><td class='id'>${sv.id}</td><td>${he(c.name)}@${he(c.curveVersion)}</td><td>${c.points.size}</td>")
                sb.append("<td>${c.points.first().calBp.toInt()} – ${c.points.last().calBp.toInt()}</td><td>${he(sv.provenance)}</td></tr>")
            }
            sb.append("</table></div>")
        }
        if (bySource.isEmpty()) sb.append("<div class='banner'>尚未导入曲线。先在<a href='/'>工作台</a>导入基线案例包。</div>")
        call.respondText(page("校准曲线", sb.toString()), ContentType.Text.Html)
    }

    // ---------------- derived detail ----------------

    private suspend fun derivedPage(call: ApplicationCall) {
        val id = call.parameters["id"]!!
        val at = asOf(call)
        val d = eng.store.derived(at).firstOrNull { it.id == id }
        if (d == null) {
            call.respondText(page("未找到", "<div class='err'>no derived record $id</div>"), ContentType.Text.Html)
            return
        }
        when (d.kind) {
            DerivedKind.CALIBRATION -> calibrationDetail(call, d, at)
            else -> sequenceDetail(call, d, at)
        }
    }

    private fun provenanceBlock(d: gsb.DerivedRecord, view: DerivedView, at: Long): String {
        val sb = StringBuilder()
        sb.append("<h2>溯源：源输入 → 计算步骤 → 人工决定</h2><div class='card'>")
        sb.append("<p>派生身份 <span class='id'>${d.id}</span> · 算法 <b>${d.algoVersion}</b> · 参数指纹 <span class='id'>${d.fingerprint}</span></p>")
        if (view.status == DerivedState.STALE) {
            sb.append("<div class='banner'>此结果已过期（${he(view.staleBecause.joinToString("; "))}）。仍可查看，但不代表当前结论。</div>")
        }
        sb.append("<table><tr><th>直接来源</th><th>类型</th><th>版本</th><th>单位/基准</th><th>来源</th></tr>")
        val byId = eng.store.sourceVersions(at).associateBy { it.id }
        for (dep in d.deps) {
            val sv = byId[dep] ?: continue
            sb.append("<tr><td class='id'>${sv.id}</td><td>${sv.kind} ${he(sv.sourceId)}</td><td>v${sv.version}</td><td>${he(sv.unit)}</td><td>${he(sv.provenance)}</td></tr>")
        }
        sb.append("</table>")
        val decisions = eng.store.events(at).filter { it.type == EventType.ADJUDICATE }
            .map { it to jsonFmt.decodeFromString(AdjudicatePayload.serializer(), it.payload) }
            .filter { (_, p) -> p.derivedId == d.id }
        if (decisions.isNotEmpty()) {
            sb.append("<p>人工决定：</p><ul>")
            for ((ev, p) in decisions) {
                sb.append("<li><a href='/events/${ev.seq}'>#${ev.seq}</a> ${he(ev.actor)}: <b>${p.verdict}</b> — ${he(p.reason)}</li>")
            }
            sb.append("</ul>")
        }
        sb.append("</div>")
        return sb.toString()
    }

    private suspend fun calibrationDetail(call: ApplicationCall, d: DerivedRecord, at: Long) {
        val r = jsonFmt.decodeFromString(CalibrationResult.serializer(), d.payload)
        val view = eng.derivedStatus(d, at)
        val verdicts = eng.verdicts(at)
        val sb = StringBuilder()
        sb.append("<h1>校准候选 ${he(r.labCode)} <span class='id'>${d.id}</span></h1>")
        sb.append("<p>曲线 ${he(r.curveRef)} · 偏移假设 ${he(r.offsetLabel ?: "无 (dR=0)")} · 有效 μ=${fmt(r.effectiveMu)} BP, σ=${fmt(r.effectiveSigma)} · 网格 ${fmt(r.gridStep)} calBP · 归一化常数 ${fmt(r.normalization)}</p>")
        if (r.diagnostics.isNotEmpty()) {
            sb.append("<div class='banner'>${r.diagnostics.joinToString("<br>") { he(it) }}</div>")
        }
        sb.append(densitySvg(listOf(r)))
        sb.append("<p><a href='/compare/${r.measurementSourceId}'>比较不同偏移假设 →</a></p>")

        sb.append("<h2>峰（多峰不压成平均年）</h2><table><tr><th>#</th><th>cal BP</th><th>年代</th><th>密度</th></tr>")
        r.peaks.forEachIndexed { i, p ->
            sb.append("<tr><td>${i + 1}</td><td>${fmt(p.calBp)}</td><td>${he(p.eraLabel)}</td><td>${fmt(p.density)}</td></tr>")
        }
        sb.append("</table>")

        sb.append(intervalTable("68.2% HDR", "68", r.intervals682, d, at, call))
        sb.append(intervalTable("95.4% HDR", "95", r.intervals954, d, at, call))

        // expanded integral evidence
        val expand = call.request.queryParameters["expand"]
        if (expand != null) {
            val (level, idx) = expand.split(":").let { it[0] to it[1].toInt() }
            val byId = eng.store.sourceVersions(at).associateBy { it.id }
            val curveSv = d.deps.mapNotNull { byId[it] }.firstOrNull { it.kind == SourceKind.CURVE }
            val offsetSv = d.deps.mapNotNull { byId[it] }.firstOrNull { it.kind == SourceKind.OFFSET }
            val curve = jsonFmt.decodeFromString(CurveData.serializer(), curveSv!!.dataJson!!)
            val offset = offsetSv?.let { jsonFmt.decodeFromString(OffsetData.serializer(), it.dataJson!!) }
            val ev = Calibration.integralEvidence(d.id, r, level, idx, curve, offset, d.fingerprint)
            sb.append("<h2>积分证据（区间 ${level}% #${idx + 1}）<span class='id'>${d.id}</span></h2><div class='card'><table>")
            sb.append("<tr><th>区间</th><td>${fmt(ev.interval.fromCalBp)} – ${fmt(ev.interval.toCalBp)} cal BP（${he(ev.interval.fromEra)} – ${he(ev.interval.toEra)}）</td></tr>")
            sb.append("<tr><th>概率</th><td>${fmt(ev.probability)} = 原始质量 ${fmt(ev.rawMass)} / 归一化常数 ${fmt(ev.normalization)}</td></tr>")
            sb.append("<tr><th>网格</th><td>步长 ${fmt(ev.gridStep)} calBP，${ev.nGridPoints} 个网格点</td></tr>")
            sb.append("<tr><th>插值</th><td>${he(ev.interpolation)}</td></tr>")
            sb.append("<tr><th>偏移假设</th><td>${he(ev.offsetAssumption)}</td></tr>")
            sb.append("<tr><th>曲线</th><td>${he(ev.curveRef)}（区间内 ${ev.curveSegment.size} 个曲线点）</td></tr>")
            sb.append("<tr><th>算法/指纹</th><td class='id'>${ev.algoVersion} · ${ev.fingerprint}</td></tr>")
            sb.append("</table><details><summary>曲线段原始点</summary><pre>")
            ev.curveSegment.forEach { sb.append("${fmt(it.calBp)} calBP -> ${fmt(it.c14Age)} ± ${fmt(it.sigma)} 14C BP\n") }
            sb.append("</pre></details></div>")
        }

        // adjudication form
        sb.append("<h2>裁定（写入事件历史，可撤销）</h2><div class='card'><form method='post' action='/adjudicate'>")
        sb.append("<input type='hidden' name='derivedId' value='${d.id}'><input type='hidden' name='back' value='/derived/${d.id}'>")
        sb.append("<select name='verdict'><option>ACCEPTED</option><option>REJECTED</option><option>PARAM_ADJUST</option></select> ")
        sb.append("<input type='text' name='reason' size='50' placeholder='理由（必填）'> ")
        sb.append("<button class='primary' type='submit'>记录裁定</button></form>")
        val v = verdicts[d.id]
        if (v != null) sb.append("<p>当前有效裁定：${verdictBadge(v)}</p>")
        sb.append("</div>")

        sb.append(provenanceBlock(d, view, at))
        call.respondText(page("校准 ${r.labCode}", sb.toString()), ContentType.Text.Html)
    }

    private fun intervalTable(title: String, level: String, intervals: List<CalInterval>, d: DerivedRecord, at: Long, call: ApplicationCall): String {
        val sb = StringBuilder()
        sb.append("<h2>$title 区间（并列日历区间保留全部合法候选）</h2><table><tr><th>#</th><th>cal BP</th><th>日历年代</th><th>概率</th><th>积分证据</th></tr>")
        intervals.forEachIndexed { i, iv ->
            sb.append("<tr><td>${i + 1}</td><td>${fmt(iv.fromCalBp)} – ${fmt(iv.toCalBp)}</td>")
            sb.append("<td>${he(iv.fromEra)} – ${he(iv.toEra)}</td><td>${fmt(iv.mass)}</td>")
            sb.append("<td><a href='/derived/${d.id}?expand=$level:$i${if (at == Long.MAX_VALUE) "" else "&asOf=$at"}'>展开积分证据</a></td></tr>")
        }
        sb.append("</table>")
        return sb.toString()
    }

    private suspend fun sequenceDetail(call: ApplicationCall, d: DerivedRecord, at: Long) {
        val r = jsonFmt.decodeFromString(SequenceCheckResult.serializer(), d.payload)
        val view = eng.derivedStatus(d, at)
        val sb = StringBuilder()
        sb.append("<h1>层序检查 ${he(r.constraintSourceId)} <span class='id'>${d.id}</span></h1>")
        sb.append("<div class='card'><p>P(${he(r.beforeSourceId)} 早于 ${he(r.afterSourceId)}) = <b>${fmt(r.pOrder)}</b> · 状态 <b>${r.status}</b></p>")
        sb.append("<p>${he(r.note)}</p>")
        if (r.candidates.isNotEmpty()) {
            sb.append("<p>候选调整（不改写测量值；无法唯一决定时保留全部合法候选）：</p><ul>")
            r.candidates.forEach { sb.append("<li><b>${it.kind}</b>: ${he(it.description)}</li>") }
            sb.append("</ul>")
        }
        sb.append("</div>")
        sb.append("<p><a href='/derived/${r.beforeCalibrationId}'>${he(r.beforeSourceId)} 校准</a> · <a href='/derived/${r.afterCalibrationId}'>${he(r.afterSourceId)} 校准</a></p>")
        sb.append(provenanceBlock(d, view, at))
        call.respondText(page("层序检查", sb.toString()), ContentType.Text.Html)
    }

    // ---------------- sequence ----------------

    private suspend fun sequencePage(call: ApplicationCall) {
        val at = asOf(call)
        val latest = eng.store.latestSources(at)
        val checks = eng.derivedViews(at).filter { it.record.kind == DerivedKind.SEQUENCE_CHECK }
        val results = checks.map { it.record to jsonFmt.decodeFromString(SequenceCheckResult.serializer(), it.record.payload) }
        val labs = latest.values.filter { it.kind == SourceKind.MEASUREMENT }
            .associate { it.sourceId to (it.dataJson?.let { dj -> jsonFmt.decodeFromString(MeasurementData.serializer(), dj).labCode } ?: it.sourceId) }
        val sb = StringBuilder()
        sb.append("<h1>层序关系</h1>")
        sb.append("<form method='post' action='/sequence/run'><button class='primary'>运行层序检查</button></form>")
        val constraints = latest.values.filter { it.kind == SourceKind.SEQUENCE }
        sb.append("<h2>约束（有来源）</h2><table><tr><th>ID</th><th>先(老)</th><th>后(新)</th><th>说明</th><th>来源</th></tr>")
        for (c in constraints.sortedBy { it.sourceId }) {
            val cd = c.dataJson?.let { jsonFmt.decodeFromString(SequenceData.serializer(), it) }
            sb.append("<tr><td>${he(c.sourceId)}</td><td>${he(cd?.beforeSourceId)}</td><td>${he(cd?.afterSourceId)}</td><td>${he(cd?.note)}</td><td>${he(c.provenance)}</td></tr>")
        }
        sb.append("</table>")
        if (results.isNotEmpty()) {
            sb.append(sequenceSvg(results.map { it.second }, labs))
            sb.append("<h2>检查结果与候选调整</h2>")
            for ((rec, r) in results.sortedBy { it.first.createdSeq }) {
                val view = eng.derivedStatus(rec, at)
                sb.append("<div class='card'><p><a href='/derived/${rec.id}'>${rec.id}</a> · ${he(r.constraintSourceId)} · ")
                sb.append("P=${fmt(r.pOrder)} · <b>${r.status}</b>")
                if (view.status == DerivedState.STALE) sb.append(" <span class='tag stale'>STALE</span>")
                sb.append("</p>")
                if (r.candidates.isNotEmpty()) {
                    sb.append("<ul>")
                    r.candidates.forEach { sb.append("<li><b>${it.kind}</b>: ${he(it.description)}</li>") }
                    sb.append("</ul>")
                }
                sb.append("</div>")
            }
        }
        call.respondText(page("层序关系", sb.toString()), ContentType.Text.Html)
    }

    // ---------------- offset comparison ----------------

    private suspend fun comparePage(call: ApplicationCall) {
        val ms = call.parameters["ms"]!!
        val latest = eng.store.latestSources()
        val curveId = latest.values.firstOrNull { it.kind == SourceKind.CURVE }?.sourceId
        val eraId = latest.values.firstOrNull { it.kind == SourceKind.ERA_SYSTEM }?.sourceId
        val sb = StringBuilder()
        sb.append("<h1>不同偏移假设下的 ${he(ms)}</h1>")
        if (curveId == null || eraId == null || !latest.containsKey(ms)) {
            sb.append("<div class='err'>缺少曲线/年代系统/测量输入</div>")
        } else {
            val combos = mutableListOf<String?>()
            combos.add(null)
            latest.values.filter { it.kind == SourceKind.OFFSET }.sortedBy { it.sourceId }.forEach { combos.add(it.sourceId) }
            val results = combos.mapNotNull { off ->
                runCatching { eng.calibrate(ms, curveId, off, eraId, "reviewer") }.getOrNull()
            }.map { jsonFmt.decodeFromString(CalibrationResult.serializer(), it.payload) }
            sb.append(densitySvg(results))
            sb.append("<table><tr><th>偏移假设</th><th>有效 μ/σ</th><th>峰数</th><th>95.4% 区间</th></tr>")
            results.forEachIndexed { i, r ->
                sb.append("<tr><td>${he(r.offsetLabel ?: "无 (dR=0)")}</td><td>${fmt(r.effectiveMu)} / ${fmt(r.effectiveSigma)}</td><td>${r.peaks.size}</td>")
                sb.append("<td>${r.intervals954.joinToString("; ") { "${it.fromEra} – ${it.toEra} (${fmt(it.mass)})" }}</td></tr>")
            }
            sb.append("</table>")
        }
        call.respondText(page("偏移假设比较", sb.toString()), ContentType.Text.Html)
    }

    // ---------------- events ----------------

    private suspend fun eventsPage(call: ApplicationCall, seq: Long?) {
        val sb = StringBuilder()
        sb.append(msg(call))
        if (seq != null) {
            val ev = eng.store.events().firstOrNull { it.seq == seq }
            if (ev == null) {
                sb.append("<div class='err'>no event #$seq</div>")
            } else {
                sb.append("<h1>事件 #${ev.seq} <span class='id'>${ev.id}</span></h1>")
                sb.append("<div class='card'><p>类型 <b>${ev.type}</b> · 操作者 ${he(ev.actor)} · 时间 ${he(ev.at)}</p><pre>${he(ev.payload)}</pre>")
                sb.append("<p><a href='/?asOf=${ev.seq}'>查看截止此事件的工作台状态（恢复当时的依赖版本与可见结论）</a></p></div>")
            }
        }
        sb.append("<h1>事件历史（机器可读；冲突解决与撤销同样入史）</h1>")
        sb.append("<table><tr><th>#</th><th>身份</th><th>类型</th><th>操作者</th><th>时间</th><th>负载</th><th>操作</th></tr>")
        val undone = eng.store.events().filter { it.type == EventType.UNDO }
            .map { jsonFmt.decodeFromString(UndoPayload.serializer(), it.payload).targetSeq }.toSet()
        for (ev in eng.store.events().sortedByDescending { it.seq }) {
            val struck = if (ev.seq in undone) " style='text-decoration:line-through'" else ""
            sb.append("<tr><td><a href='/events/${ev.seq}'>#${ev.seq}</a></td><td class='id'>${ev.id}</td>")
            sb.append("<td$struck>${ev.type}</td><td>${he(ev.actor)}</td><td class='id'>${he(ev.at)}</td>")
            sb.append("<td><details><summary>payload</summary><pre>${he(ev.payload)}</pre></details></td><td>")
            if (ev.type == EventType.ADJUDICATE && ev.seq !in undone) {
                sb.append("<form method='post' action='/undo'><input type='hidden' name='seq' value='${ev.seq}'>")
                sb.append("<input type='text' name='reason' placeholder='撤销理由' size='14'><button type='submit'>撤销</button></form>")
            }
            sb.append("</td></tr>")
        }
        sb.append("</table>")
        call.respondText(page("事件历史", sb.toString()), ContentType.Text.Html)
    }

    // ---------------- import / export ----------------

    private suspend fun ioPage(call: ApplicationCall) {
        val sb = StringBuilder()
        sb.append(msg(call))
        sb.append("<h1>导入 / 导出</h1>")
        sb.append("<div class='card'><h2>粘贴小型样例（每块：# 头 + JSON 原文；格式错误会明确报告）</h2>")
        sb.append("<form method='post' action='/import/text'><textarea name='payload' rows='10' cols='100'>")
        sb.append(he("# kind=MEASUREMENT id=LAB-1 unit=\"14C yr BP\" provenance=\"lab sheet 7\"\n{\"labCode\":\"LAB-1\",\"bpAge\":2450.0,\"sigma\":30.0,\"material\":\"charcoal\"}"))
        sb.append("</textarea><br><button class='primary' type='submit'>导入</button></form></div>")
        sb.append("<div class='card'><h2>重新导入导出的档案（身份、顺序、来源关系保持）</h2>")
        sb.append("<form method='post' action='/import/file'><textarea name='payload' rows='8' cols='100' placeholder='粘贴 /export 下载的 JSON'></textarea><br>")
        sb.append("<button class='primary' type='submit'>导入档案</button></form></div>")
        sb.append("<p><a href='/export'>下载当前档案 JSON</a></p>")
        call.respondText(page("导入/导出", sb.toString()), ContentType.Text.Html)
    }

    private suspend fun export(call: ApplicationCall) {
        val text = eng.exportToString("reviewer")
        call.respondText(text, ContentType.Application.Json)
    }

    private fun fmt(v: Double): String = "%.4f".format(v).trimEnd('0').trimEnd('.')
}
