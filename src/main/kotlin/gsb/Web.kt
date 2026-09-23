package gsb

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.*
import kotlin.math.roundToInt

object Web {
    fun configure(app: Application, store: Store) {
        app.routing {
        val wb = Workbench(store)
            get("/") { call.respondText(overviewPage(store), ContentType.Text.Html) }
            get("/samples/{stream}") {
                samplePage(store, wb, call.parameters["stream"]!!, call.request.queryParameters["at"]?.toLong())
                    ?.let { call.respondText(it, ContentType.Text.Html) }
                    ?: call.respondRedirect("/")
            }
            get("/strat") {
                call.respondText(stratPage(store, wb, call.request.queryParameters["at"]?.toLong()), ContentType.Text.Html)
            }
            get("/inputs/{id}") {
                val rec = store.inputById(call.parameters["id"]!!)
                if (rec == null) call.respondRedirect("/")
                else call.respondText(inputPage(store, rec), ContentType.Text.Html)
            }
            get("/derived/{id}") {
                val rec = store.derivedById(call.parameters["id"]!!)
                if (rec == null) call.respondRedirect("/")
                else call.respondText(derivedPage(store, rec, call.request.queryParameters["at"]?.toLong()), ContentType.Text.Html)
            }
            get("/events") {
                call.respondText(eventsPage(store, call.request.queryParameters["at"]?.toLong()), ContentType.Text.Html)
            }
            get("/events/{seq}") {
                val seq = call.parameters["seq"]!!.toLong()
                call.respondText(eventPage(store, seq), ContentType.Text.Html)
            }
            get("/export") {
                call.response.header(HttpHeaders.ContentDisposition, "attachment; filename=workbench-export.json")
                call.respondText(store.exportJson(), ContentType.Application.Json)
            }
            get("/casepack.json") {
                call.respondText(CasePack.loadBundled(), ContentType.Application.Json)
            }
            get("/import") { call.respondText(importPage(null, null), ContentType.Text.Html) }
            post("/casepack/load") {
                val report = store.importJson(CasePack.loadBundled())
                call.respondText(importPage(report, null), ContentType.Text.Html)
            }
            post("/import") {
                val params = call.receiveParameters()
                val text = params["data"].orEmpty()
                val report = if (text.isBlank()) ImportReport(false, listOf("导入内容为空")) else store.importJson(text)
                call.respondText(importPage(report, text.ifBlank { null }), ContentType.Text.Html)
            }
            post("/recalibrate") {
                val p = call.receiveParameters()
                wb.calibrate(p["measure"]!!, p["curve"]!!, p["offset"]!!, p["era"]!!)
                call.respondRedirect("/samples/${p["measure"]}")
            }
            post("/adjudicate") {
                val p = call.receiveParameters()
                wb.adjudicate(p["target"]!!, p["verdict"]!!, p["rationale"]!!)
                call.respondRedirect(p["back"] ?: "/")
            }
            post("/resolve") {
                val p = call.receiveParameters()
                wb.resolveConflict(p["target"]!!, p["chosen"]!!, p["note"]!!)
                call.respondRedirect(p["back"] ?: "/")
            }
            post("/undo") {
                val p = call.receiveParameters()
                wb.undoResolution(p["target"]!!)
                call.respondRedirect(p["back"] ?: "/")
            }
        }
    }

    private fun h(s: String?): String = (s ?: "").replace("&", "&amp;").replace("<", "&lt;")
        .replace(">", "&gt;").replace("\"", "&quot;")

    private fun f2(x: Double): String = (x * 100.0).roundToInt().let { "${it / 100.0}" }
    private fun p3(x: Double): String = "%.4f".format(x)

    private fun page(title: String, body: String, at: Long? = null, maxSeq: Long = Long.MAX_VALUE): String =
        """<!DOCTYPE html><html lang="zh"><head><meta charset="utf-8"><title>${h(title)}</title>
<style>
body{font-family:-apple-system,"PingFang SC",sans-serif;margin:0;background:#f7f6f2;color:#222}
nav{background:#243b32;color:#eee;padding:10px 18px;font-size:14px}
nav a{color:#cfe8d8;margin-right:14px;text-decoration:none}
.wrap{max-width:1080px;margin:18px auto;padding:0 16px}
.card{background:#fff;border:1px solid #d9d6cc;border-radius:8px;padding:14px 18px;margin:14px 0}
table{border-collapse:collapse;width:100%;font-size:13px}
th,td{border:1px solid #e3e0d6;padding:5px 8px;text-align:left;vertical-align:top}
th{background:#eef2ec}.id{font-family:ui-monospace,monospace;color:#666;font-size:11px}
.stale{background:#fdf3e3;border-color:#e0c48a}.stale .tag{color:#a46a00;font-weight:bold}
.cur{background:#eef8ef}
details{margin:4px 0}summary{cursor:pointer;font-weight:500}
svg{background:#fff;border:1px solid #e3e0d6;border-radius:6px}
.banner{background:#fff8e1;border:1px solid #e0c48a;padding:8px 12px;border-radius:6px;font-size:13px}
input[type=text],textarea,select{width:100%;box-sizing:border-box;font-family:inherit;font-size:13px;padding:5px}
button{background:#243b32;color:#eee;border:0;border-radius:5px;padding:7px 14px;cursor:pointer}
button.sec{background:#888}.bad{color:#a33}.ok{color:#2a7}
h1{font-size:20px}h2{font-size:16px;margin-top:0}.muted{color:#777;font-size:12px}
</style></head><body>
<nav><b>放射性碳校准工作台</b>
<a href="/">总览</a><a href="/strat">层序关系</a><a href="/events">事件历史</a>
<a href="/import">导入</a><a href="/export">导出</a><a href="/casepack.json">案例包原始文件</a></nav>
<div class="wrap">
${if (at != null) """<div class="banner">⏮ 回放视图：仅显示事件 #$at 及之前的依赖版本与可见结论，不使用最新计算结果覆盖历史。
<a href="/?at=$maxSeq">回到当前</a> · <a href="/">（当前状态）</a></div>""" else ""}
$body
</div></body></html>"""

    // ---------------- SVG：校准曲线 / 概率密度 / 层序关系 ----------------

    private fun curveSvg(curve: CalibrationCurve, highlight: ClosedFloatingPointRange<Double>? = null): String {
        val W = 1020; val H = 230; val padL = 56.0; val padB = 34.0
        fun sx(t: Double) = padL + (curve.maxBp - t) / (curve.maxBp - curve.minBp) * (W - padL - 10)
        var minY = Double.MAX_VALUE; var maxY = Double.MIN_VALUE
        for (p in curve.points) { minY = minOf(minY, p.c14Age); maxY = maxOf(maxY, p.c14Age) }
        fun sy(v: Double) = 6.0 + (maxY - v) / (maxY - minY) * (H - padB - 12)
        val pts = curve.points.joinToString(" ") { "${f2(sx(it.calBp))},${f2(sy(it.c14Age))}" }
        val band = curve.points.joinToString(" ") { "${f2(sx(it.calBp))},${f2(sy(it.c14Age - it.sigma))}" } +
            curve.points.reversed().joinToString(" ") { " ${f2(sx(it.calBp))},${f2(sy(c14Safe(it)))}" }
        val hl = if (highlight != null)
            """<rect x="${f2(sx(highlight.endInclusive))}" y="6" width="${f2(sx(highlight.start) - sx(highlight.endInclusive))}" height="${H - padB - 6}" fill="#243b32" opacity="0.07"/>""" else ""
        return """<svg width="$W" height="$H" viewBox="0 0 $W $H">$hl
<path d="M$band Z" fill="#b7cdbf" opacity="0.5"/>
<polyline points="$pts" fill="none" stroke="#243b32" stroke-width="1.6"/>
<text x="10" y="18" font-size="12">${h(curve.curveId)}:${h(curve.version)}（节点 5 cal yr，线性插值，坐标基准 cal BP）</text>
<line x1="$padL" y1="${H - padB}" x2="${W - 4}" y2="${H - padB}" stroke="#555"/>
<text x="${W / 2}" y="${H - 8}" font-size="11" text-anchor="middle">日历年 cal BP（左新右老；括号为 BCE/CE）</text>
<text x="14" y="${H / 2}" font-size="11" transform="rotate(-90 14 ${H / 2})" text-anchor="middle">${"$"}c14 age (14C yr BP)</text>
${axisTicksX({ sx(it) }, H - padB.toDouble())}
</svg>"""
    }

    private fun c14Safe(p: CurvePoint): Double = p.c14Age + p.sigma

    private fun axisTicksX(sx: (Double) -> Double, y: Double): String {
        val ticks = listOf(0.0, 1000.0, 1950.0, 2000.0, 3000.0, 4000.0, 5000.0)
        return ticks.joinToString("") { t ->
            val x = f2(sx(t))
            """<line x1="$x" y1="$y" x2="$x" y2="${y + 4}" stroke="#555"/>
<text x="$x" y="${y + 16}" font-size="10" text-anchor="middle">${t.toInt()}/${Era.label(t).replace(" ", "")}</text>""" }
    }

    private fun densitySvg(r: CalibResult, intervals: List<Interval>, title: String): String {
        val W = 1020; val H = 250; val padL = 56.0; val padB = 34.0
        val lo = r.gridStart + r.density.size * r.step
        fun sx(t: Double) = padL + (t - r.gridStart) / (lo - r.gridStart) * (W - padL - 10)
        val maxP = r.density.max()
        fun sy(v: Double) = 6.0 + (1.0 - v / maxP) * (H - padB - 16)
        val pts = r.density.indices.joinToString(" ") { "${f2(sx(r.gridStart + it * r.step))},${f2(sy(r.density[it]))}" }
        val shades = intervals.joinToString("") { iv ->
            """<rect x="${f2(sx(iv.hiBp))}" y="6" width="${f2(sx(iv.loBp) - sx(iv.hiBp))}" height="${H - padB - 6}" fill="#3d7a52" opacity="0.16"/>""" }
        val marks = r.peaks.joinToString("") { t ->
            """<line x1="${f2(sx(t))}" y1="${f2(sy(r.densityAt(t)))}" x2="${f2(sx(t))}" y2="${H - padB}" stroke="#a33" stroke-dasharray="4 3"/>
<text x="${f2(sx(t))}" y="${f2(sy(r.densityAt(t)) - 3)}" font-size="10" text-anchor="middle" fill="#a33">峰 ${t.toInt()} calBP</text>""" }
        return """<svg width="$W" height="$H" viewBox="0 0 $W $H">
$shades<polyline points="$pts" fill="none" stroke="#243b32" stroke-width="1.5"/>$marks
<text x="10" y="18" font-size="12">${h(title)}（红色虚线=多峰；绿底=并列 HPD 区间；不做平均年压缩）</text>
<line x1="$padL" y1="${H - padB}" x2="${W - 4}" y2="${H - padB}" stroke="#555"/>
<text x="${W / 2}" y="${H - 8}" font-size="11" text-anchor="middle">日历年 cal BP（左新右老；BCE/CE 跨界在 1950 处）</text>
<text x="14" y="${H / 2}" font-size="11" transform="rotate(-90 14 ${H / 2})" text-anchor="middle">归一化概率密度</text>
${axisTicksX({ sx(it) }, H - padB.toDouble())}
</svg>"""
    }

    private fun CalibResult.densityAt(t: Double): Double {
        val i = ((t - gridStart) / step).roundToInt()
        return if (i in density.indices) density[i] else 0.0
    }

    private fun stratSvg(snaps: List<Triple<StratConstraint, StratAssessment?, Boolean>>): String {
        if (snaps.isEmpty()) return "<p class='muted'>暂无层序约束。</p>"
        val rows = snaps.flatMap { listOf(it.first.youngerStream, it.first.olderStream) }.distinct()
        val yOf = rows.withIndex().associate { (i, s) -> s to (30 + i * 46) }
        val W = 640; val H = 40 + rows.size * 46
        val boxes = yOf.entries.joinToString("") { (s, y) ->
            """<rect x="220" y="${y - 16}" width="120" height="32" rx="5" fill="#eef2ec" stroke="#243b32"/>
<text x="280" y="5" font-size="13" text-anchor="middle" transform="translate(0 $y)">${h(s)}</text>""" }
        val arrows = snaps.joinToString("") { (c, a, _) ->
            val yo = yOf.getValue(c.olderStream); val yy = yOf.getValue(c.youngerStream)
            val col = if (a?.status == "conflict") "#a33" else "#2a7"
            """<path d="M250,$yo L250,$yy" stroke="$col" stroke-width="2" fill="none" marker-end="url(#arrow)"/>
<text x="360" y="${(yo + yy) / 2 + 4}" font-size="11" fill="$col">${if (a?.status == "conflict") "冲突 P=" + p3(a.pOrdered) else "一致 P=" + p3(a?.pOrdered ?: 0.0)}（${h(c.basis)}）</text>""" }
        return """<svg width="$W" height="$H" viewBox="0 0 $W $H">
<defs><marker id="arrow" markerWidth="8" markerHeight="8" refX="6" refY="3" orient="auto">
<path d="M0,0 L6,3 L0,6 Z" fill="#555"/></marker></defs>
<text x="20" y="18" font-size="12">箭头：老 → 年轻（叠压关系）；红=层序矛盾，绿=一致</text>$boxes$arrows</svg>"""
    }

    // ---------------- 页面：总览 / 输入 / 派生 ----------------

    private fun overviewPage(store: Store): String {
        val maxSeq = store.events().lastOrNull()?.seq ?: 0
        val heads = store.allInputs().groupBy { it.streamId }.mapValues { e -> e.value.maxBy { it.version } }
        val stale = store.staleIds()
        val wb = Workbench(store)
        val streams = heads.values.sortedWith(compareBy({ it.kind }, { it.streamId }))
        val inputsHtml = streams.joinToString("") { rec ->
            val versions = store.allInputs().filter { it.streamId == rec.streamId }
            val vchains = versions.joinToString("") { v ->
                val cls = if (v.id == rec.id) "cur" else "stale"
                """<a class="$cls" style="padding:1px 6px;border:1px solid #ccc;border-radius:4px;text-decoration:none;color:#333;margin-right:4px"
href="/inputs/${v.id}" title="${h(v.source)}">v${v.version} ${h(v.id)}</a>""" }
            "<tr><td><b>${h(rec.streamId)}</b></td><td>${h(rec.kind)}</td><td>${h(rec.source)}</td><td>$vchains</td></tr>"
        }
        val sampleRows = heads.values.filter { it.kind == Kinds.MEASUREMENT }.sortedBy { it.streamId }.joinToString("") { rec ->
            val m = runCatching { JsonFmt.decodeFromJsonElement<Measurement>(rec.payload) }.getOrNull()
            val cal = wb.latestCalibrationFor(rec.streamId)
            val calCell = cal?.let {
                val cr = CalibResult.fromJson(it.result["calibration"]!!.jsonObject)
                val peaks = cr.peaks.size
                val verdict = store.adjudicationState(it.id).first
                """<a href="/samples/${rec.streamId}">查看 ${peaks} 峰 / ${cr.intervals95.size} 个并列 95.4% 区间</a>
<br><span class="id">${h(it.id)} · ${h(ALGO_CALIB)} · 裁定=$verdict</span>"""
            } ?: "<span class='muted'>${if (m?.diagnostic != null) "诊断：" + h(m.diagnostic) else "尚未校准（" }${if (m?.diagnostic == null) "</span>" else ""}</span>"
            val excluded = heads.values.firstOrNull { it.kind == Kinds.EXCLUSION &&
                runCatching { JsonFmt.decodeFromJsonElement<ExclusionDecision>(it.payload).sampleStream }.getOrNull() == rec.streamId }
            """<tr><td><b>${h(rec.streamId)}</b> ${h(m?.labCode ?: "")}${if (excluded != null) " <span class='tag bad'>已排除</span>" else ""}</td>
<td>${h(m?.raw ?: "")} <span class='muted'>${h(m?.unit ?: "")}</span></td><td>$calCell</td></tr>"""
        }
        val derRows = store.allDerived().joinToString("") { d ->
            val isStale = d.id in stale
            val cls = if (isStale) "stale" else "cur"
            val deps = d.deps.joinToString(" ") { dep ->
                """<a href="/inputs/${dep.inputId}">${h(dep.streamId)}</a>""" }
            val summary = if (d.kind == "calibration") {
                val cr = CalibResult.fromJson(d.result["calibration"]!!.jsonObject)
                "${cr.peaks.size} 峰 / 68.2%×${cr.intervals68.size} / 95.4%×${cr.intervals95.size}" +
                (if (cr.truncatedAtMin) " · 现代边界截断" else "") +
                (if (cr.truncatedAtMax) " · 老端截断" else "")
            } else {
                val a = StratAssessment.fromJson(d.result)
                "P(${h(a.olderStream)}&gt;${h(a.youngerStream)})=${p3(a.pOrdered)} · ${h(a.status)} · 候选 ${a.candidates.size}"
            }
            val verdict = store.adjudicationState(d.id).first
            """<tr class="$cls"><td><a href="/derived/${d.id}">${h(d.id)}</a>${if (isStale) " <span class='tag'>已过期（可查看，非当前结果）</span>" else ""}</td>
<td>${h(d.kind)}</td><td class='id'>${h(d.algoVersion)}</td><td>$deps</td><td>$summary</td><td>$verdict</td></tr>"""
        }
        return page("总览", """
<h1>放射性碳 / 年代校准 / 多峰分布工作台</h1>
<p class="muted">当前共 ${heads.size} 个输入流、${store.allDerived().size} 条派生记录、${store.events().size} 个事件。
完整叙事：核对来源 → 校准多峰候选 → 层序冲突 → 参数修订 → 曲线升版 → 复核与导出。</p>
<div class="card"><h2>样品日历年代候选席</h2><table>
<tr><th>样品流</th><th>原测量值（原始表示 + 单位）</th><th>当前校准（稳定身份可展开积分证据）</th></tr>$sampleRows</table></div>
<div class="card"><h2>输入来源（原始输入不可覆盖；修订=新版本+父版本）</h2><table>
<tr><th>流</th><th>类型</th><th>来源</th><th>版本链</th></tr>$inputsHtml</table></div>
<div class="card"><h2>派生结果（算法版本 · 参数指纹 · 直接来源）</h2><table>
<tr><th>身份</th><th>类型</th><th>算法</th><th>直接来源</th><th>摘要</th><th>裁定状态</th></tr>$derRows</table></div>
<div class="card"><a href="/casepack/load"><button>（重新）载入离线案例包</button></a>
<a href="/import"><button class="sec">导入 / 恢复</button></a>
<a href="/export"><button class="sec">导出 JSON</button></a></div>
""", maxSeq = maxSeq)
    }

    private fun inputPage(store: Store, rec: InputRecord): String {
        val all = store.allInputs().filter { it.streamId == rec.streamId }
        val chain = all.sortedBy { it.version }.joinToString(" → ") { v ->
            if (v.id == rec.id) "<b>v${v.version} ${h(v.id)}</b>"
            else """<a href="/inputs/${v.id}">v${v.version}</a>""" }
        val relDerived = store.allDerived().filter { d -> d.deps.any { it.inputId == rec.id } }
        val body = """
<h1>输入 ${h(rec.streamId)} v${rec.version}</h1>
<div class="card"><table>
<tr><th>稳定身份</th><td class="id">${h(rec.id)}</td></tr>
<tr><th>类型 / 版本</th><td>${h(rec.kind)} · v${rec.version} · 父版本 ${h(rec.parentId ?: "无")}</td></tr>
<tr><th>来源</th><td>${h(rec.source)}</td></tr>
<tr><th>提交时间</th><td>${h(rec.createdAt)}</td></tr>
<tr><th>版本链</th><td>$chain</td></tr>
<tr><th>原始载荷（保留原始表示，未规范化）</th><td><pre>${h(JsonFmt.encodeToString(JsonObject.serializer(), rec.payload))}</pre></td></tr>
</table></div>
<div class="card"><h2>直接使用本输入的派生结果</h2><ul>
${if (relDerived.isEmpty()) "<li class='muted'>暂无</li>" else relDerived.joinToString("") { "<li><a href='/derived/${it.id}'>${h(it.id)}</a> · ${h(it.kind)}</li>" }}
</ul></div>"""
        return page("输入 ${rec.streamId}", body)
    }

    private fun derivedPage(store: Store, d: DerivedRecord, at: Long?): String {
        val maxSeq = store.events().lastOrNull()?.seq ?: 0
        val stale = d.id in store.staleIds(at ?: maxSeq)
        val depsHtml = d.deps.joinToString("") { dep ->
            val inp = store.inputById(dep.inputId)
            """<li><a href="/inputs/${dep.inputId}"><b>${h(dep.streamId)}</b> ${h(dep.inputId)}</a>
 — ${h(inp?.source ?: "")}</li>""" }
        val events = store.events(at ?: maxSeq).filter { ev ->
            ev.payload.toString().contains(d.id) }.joinToString("") { ev ->
                """<li><a href="/events/${ev.seq}">#${ev.seq} ${h(ev.type)}</a> <span class="id">${h(ev.id)}</span></li>""" }
        val (verdict, adjs) = store.adjudicationState(d.id, at ?: maxSeq)
        val body = """
<h1>派生结果 ${h(d.id)} ${if (stale) "<span class='tag bad'>已过期（可查看，不能冒充当前结果）</span>" else "<span class='ok'>当前</span>"}</h1>
<div class="card"><table>
<tr><th>类型</th><td>${h(d.kind)}</td></tr>
<tr><th>算法版本</th><td class="id">${h(d.algoVersion)}</td></tr>
<tr><th>参数指纹</th><td class="id">${h(d.fingerprint)}</td></tr>
<tr><th>创建时间</th><td>${h(d.createdAt)}</td></tr>
<tr><th>直接来源</th><td><ul>$depsHtml</ul></td></tr>
</table></div>
<div class="card"><h2>计算结果</h2><pre>${h(JsonFmt.encodeToString(JsonObject.serializer(), d.result))}</pre></div>
<div class="card"><h2>计算步骤与人工决定（机器可读事件）</h2><ul>$events</ul>
当前裁定状态：<b>$verdict</b>（${adjs.size} 次裁定）</div>"""
        return page("派生 ${d.id}", body, at = at, maxSeq = maxSeq)
    }

    // ---------------- 页面：样品复核 ----------------

    fun samplePage(store: Store, wb: Workbench, stream: String, at: Long?): String? {
        val maxSeq = store.events().lastOrNull()?.seq ?: 0
        val effectiveAt = at ?: maxSeq
        val measure = store.headOf(stream, effectiveAt) ?: return null
        val m = runCatching { JsonFmt.decodeFromJsonElement<Measurement>(measure.payload) }.getOrNull() ?: return null
        val calRecs = store.allDerived().filter { it.kind == "calibration" &&
            it.deps.any { d -> d.streamId == stream } && store.derivedSeq(it.id) <= effectiveAt }
            .sortedBy { store.derivedSeq(it.id) }
        val current = calRecs.lastOrNull { it.id !in store.staleIds(effectiveAt) } ?: calRecs.lastOrNull()
        val readOnly = at != null
        val otherOffsets = store.allInputs().filter { it.kind == Kinds.OFFSET }
            .groupBy { it.streamId }.mapValues { e -> e.value.maxBy { it.version } }
        val curveHeads = store.allInputs().filter { it.kind == Kinds.CURVE_REF }
            .groupBy { it.streamId }.mapValues { e -> e.value.maxBy { it.version } }
        val eraHeads = store.allInputs().filter { it.kind == Kinds.ERA }
            .groupBy { it.streamId }.mapValues { e -> e.value.maxBy { it.version } }

        val historyHtml = calRecs.joinToString("") { d ->
            val stale = d.id in store.staleIds(effectiveAt)
            val cls = if (d == current) "cur" else if (stale) "stale" else ""
            val cr = CalibResult.fromJson(d.result["calibration"]!!.jsonObject)
            val cid = d.result["curveVersion"]!!.jsonPrimitive.content
            val off = d.result["offset"]!!.jsonObject
            val offDesc = off["kind"]?.jsonPrimitive?.content.let { k ->
                if (k == "mixture") "混合(" + (off["components"]?.jsonArray?.size ?: 0) + " 分量)"
                else "ΔR=${off["mean"]?.jsonPrimitive?.content}±${off["sigma"]?.jsonPrimitive?.content}" }
            """<tr class="$cls"><td><a href="/derived/${d.id}">${h(d.id)}</a>
${if (d == current) " <b>← 当前可见</b>" else if (stale) " <span class='tag'>已过期</span>" else ""}</td>
<td>${h(cid)}</td><td>$offDesc</td><td>${cr.peaks.size} 峰 / ${cr.intervals95.size} 并列区间</td>
<td>${h(store.adjudicationState(d.id, effectiveAt).first)}</td></tr>""" }

        var body = """
<h1>样品 $stream · ${h(m.labCode)} <span class='muted'>${h(m.material)}</span></h1>
<div class="card"><h2>1. 源输入核对</h2><table>
<tr><th>原测量值（原始表示）</th><td>${h(m.raw)} <span class='muted'>${h(m.unit)}</span></td></tr>
<tr><th>规范化数值</th><td>${if (m.c14AgeBP != null) "${m.c14AgeBP} ± ${m.sigmaBP} ${m.unit}" else "—"}</td></tr>
<tr><th>测量输入身份/版本</th><td><a href="/inputs/${measure.id}">${h(measure.id)} v${measure.version}</a> — ${h(measure.source)}</td></tr>
${if (m.diagnostic != null) "<tr><th>诊断</th><td class='bad'>${h(m.diagnostic)}</td></tr>" else ""}
</table></div>"""

        if (current != null) {
            val cr = CalibResult.fromJson(current.result["calibration"]!!.jsonObject)
            val cid = current.result["curveId"]!!.jsonPrimitive.content
            val cver = current.result["curveVersion"]!!.jsonPrimitive.content
            val curve = Curves.get(cid, cver)!!
            val span95 = (cr.intervals95.minOf { it.hiBp } - 20.0)..(cr.intervals95.maxOf { it.loBp } + 20.0)
            val eraSystem = current.result["eraSystem"]?.jsonPrimitive?.content
            val offsetJson = current.result["offset"]!!.jsonObject
            val off = JsonFmt.decodeFromJsonElement<OffsetDistribution>(offsetJson)
            val depsOf = current.deps.first { it.streamId != stream }
            val offInput = current.deps.firstOrNull { it.streamId.startsWith("offset") }?.let { store.inputById(it.inputId) }

            fun intervalTable(ivs: List<Interval>, tag: String): String = ivs.mapIndexed { idx, iv ->
                val ev = cr.evidence.firstOrNull { it.intervalId == iv.id }
                val rows = ev?.rows?.joinToString("") { row ->
                    "<tr><td>${row.calBp.toInt()}</td><td>${Era.label(row.calBp)}</td><td>${"%.6f".format(row.density)}</td><td>${"%.4f".format(row.cumulative)}</td></tr>" }
                val crosses = if (Era.crossesEraBoundary(iv.hiBp, iv.loBp)) " <span class='tag bad'>BCE/CE 跨界</span>" else ""
                """<tr><td><b>$tag #$idx</b> <span class="id">${h(iv.id)}</span>$crosses</td>
<td>${f2(iv.hiBp)}–${f2(iv.loBp)} cal BP<br><span class='muted'>${Era.label(iv.hiBp)} 至 ${Era.label(iv.loBp)}</span></td>
<td>${"%.3f".format(iv.prob)}</td>
<td><details><summary>展开积分证据（步长 ${ev?.step} cal yr，梯形/矩形积分，共 ${ev?.rows?.size} 个抽样点）</summary>
<table><tr><th>calBP</th><th>年代系统（$eraSystem）</th><th>密度</th><th>区间内累积概率</th></tr>$rows</table>
<div class="muted">该区间精确总概率：${"%.5f".format(ev?.totalProb ?: 0.0)}；证据身份随派生 ${h(current.id)} 固定。</div>
</details></td></tr>"""
            }.joinToString("")

            val (verdict, adjs) = store.adjudicationState(current.id, effectiveAt)
            val adjHtml = adjs.joinToString("") { ev ->
                """<li><a href="/events/${ev.seq}">#${ev.seq}</a>
<b>${h(ev.payload["verdict"]?.jsonPrimitive?.content)}</b>：${h(ev.payload["rationale"]?.jsonPrimitive?.content)}
<span class="muted">(${h(ev.createdAt)})</span></li>""" }
            val resEvents = store.events(effectiveAt).filter {
                (it.type == Ev.CONFLICT_RESOLVED || it.type == Ev.CONFLICT_UNDONE) &&
                    it.payload["target"]?.jsonPrimitive?.content == current.id }
            val resHtml = resEvents.joinToString("") { ev ->
                "<li><a href='/events/${ev.seq}'>#${ev.seq} ${h(ev.type)}</a></li>" }

            body += """
<div class="card"><h2>2. 校准曲线（固定版本 ${h(cver)}，节点间线性插值）</h2>
${curveSvg(curve, span95)}
<p class="muted">曲线身份：$cid:$cver · <a href="/inputs/${current.deps.first { it.streamId.startsWith("curve") }.inputId}">来源记录</a></p></div>
<div class="card"><h2>3. 校准后概率密度（多峰完整保留）</h2>
${densitySvg(cr, cr.intervals95, "M = ${current.result["c14AgeBP"]} ± ${current.result["sigmaBP"]} ${m.unit}；偏移 ${off.effectiveComponents().size} 分量")}
<table><tr><th>峰（cal BP / 年代）</th><td>${cr.peaks.joinToString { "${it.toInt()}（${Era.label(it)}）" }}</td></tr>
<tr><th>归一化常数</th><td class="id">1/rawMass，Z = ${"%.6g".format(cr.norm)}（固定版本下逐格求和可重现）</td></tr>
${if (cr.truncatedAtMin) "<tr><th>边界插值</th><td class='tag'>现代端 0 cal BP 处概率质量被截断，归一化仅覆盖表内范围</td></tr>" else ""}
${if (cr.truncatedAtMax) "<tr><th>边界插值</th><td class='tag'>老端 ${curve.maxBp.toInt()} cal BP 截断</td></tr>" else ""}</table></div>
<div class="card"><h2>4. 并列日历区间（不合并、不平均）</h2>
<table><tr><th>区间身份</th><th>范围</th><th>区间概率</th><th>积分证据</th></tr>
${intervalTable(cr.intervals68, "68.2%")}${intervalTable(cr.intervals95, "95.4%")}</table></div>
<div class="card"><h2>5. 不同偏移假设与参数调整</h2>
<p>当前偏移：${if (off.kind == "mixture") "混合分布 " + off.components.joinToString { "%.0f×(%.0f±%.0f)".format(it.weight, it.mean, it.sigma) } else "正态 ΔR ${off.mean}±${off.sigma} ${off.unit}"}
— <a href="/inputs/${offInput?.id}">来源：${h(offInput?.source)}</a></p>
${if (!readOnly) """<form method="post" action="/recalibrate" style="display:flex;gap:8px;align-items:center">
<input type="hidden" name="measure" value="$stream">
<label>曲线流 <select name="curve">${curveHeads.entries.joinToString("") { (k, v) -> "<option${if (v.streamId == current.deps.first { it.streamId.startsWith("curve") }.streamId) " selected" else ""}>$k</option>" }}</select></label>
<label>偏移流 <select name="offset">${otherOffsets.entries.joinToString("") { (k, v) -> "<option${if (offInput?.streamId == k) " selected" else ""}>$k</option>" }}</select></label>
<label>年代系统 <select name="era">${eraHeads.keys.joinToString("") { "<option>$it</option>" }}</select></label>
<button type="submit">用不同假设重新校准（生成 adjust 事件，不改写历史结果）</button></form>"""
else "<p class='muted'>回放视图中禁用操作。</p>"}</div>
<div class="card"><h2>6. 人工裁定（接受 / 否定 / 参数调整均写入历史）</h2>
<ul>$adjHtml</ul>${if (resHtml.isNotEmpty()) "<b>冲突解决/撤销：</b><ul>$resHtml</ul>" else ""}
<p>当前状态：<b>${h(verdict)}</b></p>
${if (!readOnly) """<form method="post" action="/adjudicate" style="display:flex;gap:8px">
<input type="hidden" name="target" value="${current.id}"><input type="hidden" name="back" value="/samples/$stream">
<select name="verdict"><option value="accept">接受</option><option value="reject">否定</option><option value="adjust">参数调整</option></select>
<input type="text" name="rationale" placeholder="裁定理由（必填，写入事件）" required>
<button type="submit">提交裁定</button></form>""" else ""}
${if (!readOnly && (verdict == "conflicted")) """<form method="post" action="/resolve" style="display:flex;gap:8px;margin-top:8px">
<input type="hidden" name="target" value="${current.id}"><input type="hidden" name="back" value="/samples/$stream">
<select name="chosen">${adjs.joinToString("") { "<option value='${it.seq}'>按 #${it.seq} (${it.payload["verdict"]?.jsonPrimitive?.content})</option>" }}<option value="undecided">无法唯一决定（保留全部候选）</option></select>
<input type="text" name="note" placeholder="解决说明"><button class="sec" type="submit">解决冲突</button></form>
<form method="post" action="/undo" style="margin-top:6px"><input type="hidden" name="target" value="${current.id}"><input type="hidden" name="back" value="/samples/$stream"><button class="sec" type="submit">撤销最近一次冲突解决</button></form>""" else ""}</div>
<div class="card"><h2>7. 同一测量的校准版本时间线（过期结果仍可查看）</h2>
<table><tr><th>派生身份</th><th>曲线版本</th><th>偏移假设</th><th>分布</th><th>裁定</th></tr>$historyHtml</table></div>"""
        } else {
            body += "<div class='card'><p class='muted'>该时刻（事件 #$effectiveAt）尚无校准结果。</p></div>"
        }
        return page("样品 $stream", body, at = at, maxSeq = maxSeq)
    }

    // ---------------- 页面：层序 / 事件 / 导入 ----------------

    fun stratPage(store: Store, wb: Workbench, at: Long?): String {
        val maxSeq = store.events().lastOrNull()?.seq ?: 0
        val eff = at ?: maxSeq
        val constraints = store.allInputs().filter { it.kind == Kinds.STRAT && store.inputSeq(it.id) <= eff }
            .sortedBy { it.streamId }
        val triples = constraints.map { rec ->
            val sc = JsonFmt.decodeFromJsonElement<StratConstraint>(rec.payload)
            val ass = wb.latestStratAssessmentFor(rec.streamId, eff)?.let {
                StratAssessment.fromJson(it.result) }
            Triple(sc, ass, false)
        }
        val cards = constraints.map { rec ->
            val sc = JsonFmt.decodeFromJsonElement<StratConstraint>(rec.payload)
            val ass = wb.latestStratAssessmentFor(rec.streamId, eff)
            val stale = ass != null && ass.id in store.staleIds(eff)
            val assessHtml = if (ass == null) "<p class='muted'>该时刻尚无评估</p>" else {
                val a = StratAssessment.fromJson(ass.result)
                val candRows = a.candidates.joinToString("") { c ->
                    "<tr><td class='id'>${h(c.id)}</td><td>${h(c.action)}</td><td>${h(c.detail)}</td>" +
                    "<td>${c.resultingP?.let { p3(it) } ?: "—"}</td>" +
                    "<td>${if (c.resolves) "<span class='ok'>可消除冲突</span>" else "<span class='bad'>不足</span>"}</td></tr>" }
                """<p>评估身份 <a href="/derived/${ass.id}">${h(ass.id)}</a>（${h(ALGO_STRAT)}）${if (stale) " <span class='tag bad'>已过期</span>" else ""}</p>
<table><tr><th>候选身份</th><th>动作</th><th>说明（候选调整，不改写测量）</th><th>调整后 P</th><th>是否消除冲突</th></tr>$candRows</table>
<p>P(${h(a.olderStream)} 比 ${h(a.youngerStream)} 老)=${p3(a.pOrdered)} → <b>${h(a.status)}</b>
（层序矛盾时全部合法候选保留，无法唯一决定时不替审阅者选）</p>"""
            }
            val (verdict, adjs) = if (ass != null) store.adjudicationState(ass.id, eff) else "none" to emptyList()
            val adjForms = if (ass == null || at != null) "" else """
<form method="post" action="/adjudicate" style="display:flex;gap:8px;margin-top:6px">
<input type="hidden" name="target" value="${ass.id}"><input type="hidden" name="back" value="/strat">
<select name="verdict"><option value="accept">接受某候选（在理由中注明候选 id）</option><option value="reject">拒绝</option><option value="adjust">参数调整</option></select>
<input type="text" name="rationale" placeholder="选择的候选 id 与理由" required style="flex:1">
<button type="submit">记录人工决定</button></form>
${if (verdict == "conflicted") """<form method="post" action="/resolve" style="display:flex;gap:8px;margin-top:6px">
<input type="hidden" name="target" value="${ass.id}"><input type="hidden" name="back" value="/strat">
<select name="chosen">${adjs.joinToString("") { "<option value='${it.seq}'>按 #${it.seq}</option>" }}<option value="undecided">无法唯一决定</option></select>
<input type="text" name="note" placeholder="解决说明"><button class="sec" type="submit">解决冲突</button></form>
<form method="post" action="/undo" style="margin-top:6px"><input type="hidden" name="target" value="${ass.id}"><input type="hidden" name="back" value="/strat"><button class="sec" type="submit">撤销最近解决</button></form>""" else ""}"""
            """<div class="card ${if (stale) "stale" else ""}"><h2>约束 ${h(rec.streamId)} v${rec.version}</h2>
<p>来源：${h(rec.source)}<br>方向：<b>${h(sc.olderStream)} 应比 ${h(sc.youngerStream)} 老</b>
（${h(sc.basis)}） · <a href="/inputs/${rec.id}">输入记录</a></p>
$assessHtml<p>裁定状态：<b>$verdict</b>（${adjs.size} 次）</p>$adjForms</div>"""
        }.joinToString("")
        return page("层序关系", """
<h1>层序关系与冲突候选席</h1>
<div class="card">${stratSvg(triples)}</div>$cards""", at = at, maxSeq = maxSeq)
    }

    fun eventsPage(store: Store, at: Long?): String {
        val maxSeq = store.events().lastOrNull()?.seq ?: 0
        val rows = store.events().joinToString("") { ev ->
            """<tr><td><a href="/events/${ev.seq}">#${ev.seq}</a></td>
<td>${h(ev.type)}</td><td class="id">${h(ev.id)}</td>
<td><code>${h(ev.payload.toString())}</code></td><td class='muted'>${h(ev.createdAt)}</td>
<td><a href="/?at=${ev.seq}">在该时刻回放总览</a> · <a href="/events?at=${ev.seq}">截止于此</a></td></tr>""" }
        return page("事件历史", """
<h1>机器可读事件（仅追加）</h1>
<div class="card"><table><tr><th>序号</th><th>类型</th><th>身份</th><th>载荷</th><th>时间</th><th>回放</th></tr>$rows</table></div>""",
            at = at, maxSeq = maxSeq)
    }

    fun eventPage(store: Store, seq: Long): String {
        val ev = store.events().firstOrNull { it.seq == seq }
            ?: return page("事件 #$seq", "<p>无此事件。</p>")
        val body = """
<h1>事件 #${ev.seq} ${h(ev.type)}</h1>
<div class="card"><table>
<tr><th>事件身份</th><td class="id">${h(ev.id)}</td></tr>
<tr><th>时间</th><td>${h(ev.createdAt)}</td></tr>
<tr><th>载荷（机器可读）</th><td><pre>${h(JsonFmt.encodeToString(JsonObject.serializer(), ev.payload))}</pre></td></tr>
</table></div>
<div class="card"><a href="/?at=${ev.seq}"><button>回到此事件：按当时依赖版本复核全部可见结论</button></a></div>"""
        return page("事件 #$seq", body)
    }

    fun importPage(report: ImportReport?, previous: String?): String {
        val msg = if (report == null) "" else """<div class="card ${if (report.ok) "cur" else "stale"}">
<b>${if (report.ok) "导入成功" else "导入失败 / 存在格式错误"}</b><br>
输入/步骤执行：${report.inputs}，事件：${report.events}，派生：${report.derived}
${report.errors.joinToString("") { "<br><span class='bad'>• ${h(it)}</span>" }}</div>"""
        val body = """
<h1>导入 / 恢复</h1>
<div class="card"><h2>载入仓库自带离线案例包</h2>
<p>「碳年候选席：校准曲线、储库偏移与多峰年代证据」——覆盖曲线多峰、边界插值、储库偏移分布、
BCE/CE 跨界、层序矛盾、曲线升版与并列日历区间；含参数修订、局部源数据改动与互相冲突的人工决定。</p>
<form method="post" action="/casepack/load"><button type="submit">载入案例包（建议空库；输入不可覆盖，重复载入会产生新版本）</button></form>
<p><a href="/casepack.json">查看案例包 JSON 原文</a></p></div>
<div class="card"><h2>粘贴导入</h2><p>接受：导出文件（formatVersion）、案例包（steps）、单条输入（kind+stream+source+payload）。
格式错误会逐条列出且不写入。完整恢复仅允许空数据库。</p>
<form method="post" action="/import"><textarea name="data" rows="14" placeholder='{"kind":"measurement","stream":"M7","source":"...","payload":{...}}'>${h(previous)}</textarea>
<p><button type="submit">导入</button></p></form></div>
$msg
<div class="card"><h2>导出 → 删除本地数据库 → 导入</h2><ol>
<li><a href="/export">下载导出 JSON</a></li>
<li>停止服务后删除数据库文件（默认 data/workbench.db，含 -wal/-shm）</li>
<li>重新启动服务，回到本页粘贴导出 JSON；身份、顺序与来源关系保持。</li></ol></div>"""
        return page("导入", body)
    }
}
