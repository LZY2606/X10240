package workbench

/** HTML 渲染：所有显示值都带 data-stable-id，可由稳定身份读回档案。 */
object Web {
    val CSS = """
    body{font-family:-apple-system,'PingFang SC',sans-serif;margin:0;background:#f7f7f5;color:#1f2937}
    header{background:#0f3d3e;color:#e6fffa;padding:14px 22px;display:flex;gap:18px;align-items:center;flex-wrap:wrap}
    header a{color:#a7f3d0;text-decoration:none;font-size:14px}
    main{max-width:1100px;margin:18px auto;padding:0 16px 60px}
    .card{background:#fff;border:1px solid #e2e5e1;border-radius:10px;padding:14px 18px;margin:14px 0}
    h1{font-size:20px;margin:0 0 4px} h2{font-size:16px;margin:18px 0 8px} h3{font-size:14px;margin:12px 0 6px}
    table{border-collapse:collapse;width:100%;font-size:13px}
    th,td{border-bottom:1px solid #eceeea;padding:6px 8px;text-align:left;vertical-align:top}
    th{background:#f3f5f2} code{background:#f0f1ee;padding:1px 5px;border-radius:4px;font-size:12px}
    .svg-chart{width:100%;height:auto;border:1px solid #e2e5e1;border-radius:8px;margin:8px 0;background:#fff}
    .svg-axis{font-size:10px;fill:#555}.svg-title{font-size:12px;fill:#333;font-weight:600}
    .svg-box{font-size:13px;fill:#1f2937;font-weight:600}.svg-sub{font-size:11px;fill:#555}
    .tag{display:inline-block;padding:1px 8px;border-radius:999px;font-size:11px;font-weight:600}
    .t-cur{background:#dcfce7;color:#166534}.t-stale{background:#fef3c7;color:#92400e}
    .t-conf{background:#fee2e2;color:#991b1b}.t-unk{background:#e5e7eb;color:#374151}
    .t-acc{background:#dbeafe;color:#1e40af}.t-rej{background:#fee2e2;color:#991b1b}.t-adj{background:#ede9fe;color:#5b21b6}
    .diag{font-family:ui-monospace,monospace;font-size:11px;color:#92400e;background:#fffbeb;border-left:3px solid #f59e0b;padding:4px 8px;margin:4px 0;display:block}
    details{margin:6px 0}summary{cursor:pointer;font-weight:600;font-size:13px}
    form{display:inline} button,.btn{background:#0f766e;color:#fff;border:0;border-radius:6px;padding:6px 12px;font-size:13px;cursor:pointer;text-decoration:none;display:inline-block}
    button.danger{background:#b91c1c}button.ghost{background:#e5e7eb;color:#1f2937}
    textarea{width:100%;font-family:ui-monospace,monospace;font-size:12px;height:260px}
    input,select{font-size:13px;padding:3px 6px}.muted{color:#6b7280;font-size:12px}
    .grid2{display:grid;grid-template-columns:1fr 1fr;gap:14px}.row{display:flex;gap:10px;flex-wrap:wrap;align-items:center}
    pre.json{background:#0b1f1e;color:#d1fae5;padding:10px;border-radius:8px;font-size:11px;overflow:auto;max-height:340px}
    .ok{color:#166534;font-weight:700}.bad{color:#991b1b;font-weight:700}
    """

    fun page(title: String, body: String): String = """<!doctype html><html lang="zh"><head><meta charset="utf-8">
    <title>${esc(title)}</title><style>$CSS</style></head><body>
    <header><strong>放射性碳多峰分布工作台</strong>
    <a href="/">项目总览</a><a href="/casepack">离线案例包</a><a href="/sources/new">手工导入</a>
    <a href="/decisions">裁定历史</a><a href="/history">事件历史</a>
    <a href="/export">导出档案</a><a href="/reset">删除本地库</a></header><main>
    <h1>${esc(title)}</h1>$body</main></body></html>"""

    private fun statusTag(status: DerivedStatus): String {
        val (cls, text) = when (status) {
            DerivedStatus.CURRENT -> "t-cur" to "CURRENT 当前"
            DerivedStatus.STALE -> "t-stale" to "STALE 过期（可查看，不可冒充当前）"
            DerivedStatus.CONFLICTED -> "t-conf" to "CONFLICTED 决定冲突"
            DerivedStatus.UNKNOWN -> "t-unk" to "UNKNOWN"
        }
        return "<span class='tag $cls'>$text</span>"
    }

    fun dashboard(st: State): String {
        val fams = st.families().entries.sortedBy { e -> e.value.minOf { it.id } }
        val srcRows = fams.joinToString("\n") { (fam, recs) ->
            val latest = recs.maxBy { it.version }
            val chain = recs.sortedByDescending { it.version }.joinToString(" → ") {
                "<a href='/sources/${it.id}'><code>${it.id}</code> v${it.version}</a>"
            }
            val diags = st.sourceDiag[latest.id]?.joinToString("") {
                "<span class='diag'>${esc(it)}</span>"
            } ?: ""
            "<tr><td><code data-stable-id='family-$fam'>$fam</code><br>" +
            "<span class='muted'>${esc(latest.kind.name)}</span></td>" +
            "<td>$chain</td><td>${esc(latest.provenance)}</td>" +
            "<td>${esc(latest.unit)}$diags</td></tr>"
        }
        val derRows = st.derived.values.joinToString("\n") { d ->
            val status = st.derivedStatusNow(d.id)
            val stale = if (d.status == DerivedStatus.STALE)
                "<br><span class='muted'>${esc(d.staleReason ?: "")}</span>" else ""
            "<tr><td><a href='/derived/${d.id}'><code data-stable-id='derived-link-${d.id}'>${d.id}</code></a></td>" +
            "<td>${esc(d.kind)}</td><td><code>${esc(d.algorithmVersion)}</code></td>" +
            "<td><code>${esc(d.paramFingerprint)}</code></td>" +
            "<td>${d.sourceIds.joinToString(", ") { "<code>${esc(it)}</code>" }}</td>" +
            "<td>${statusTag(status)} $stale</td></tr>"
        }
        val decRows = st.decisions.joinToString("\n") { dc ->
            val href = if (dc.p.targetType == "derived") "derived" else "sources"
            val tail = if (dc.voidedBy == null)
                "<form method='post' action='/decisions/${dc.p.id}/void'><button class='ghost'>撤销</button></form>"
            else "<span class='muted'>撤销事件 ${dc.voidedBy}</span>"
            "<tr><td><code>${dc.p.id}</code></td>" +
            "<td><span class='tag ${if (dc.p.verdict == "ACCEPT") "t-acc" else if (dc.p.verdict == "REJECT") "t-rej" else "t-adj"}'>" +
            "${if (dc.voidedBy != null) "<s>${dc.p.verdict}</s> 已撤销" else dc.p.verdict}</span></td>" +
            "<td>${esc(dc.p.targetType)} <a href='/$href/${dc.p.targetId}'><code>${dc.p.targetId}</code></a></td>" +
            "<td>${esc(dc.p.author)}</td><td>${esc(dc.p.reason)}</td><td>$tail</td></tr>"
        }
        val conflicts = st.conflicts().entries.joinToString("") { (target, ds) ->
            "<div class='card' style='border-color:#b91c1c'><strong class='bad'>决定冲突：$target</strong> " +
            ds.joinToString(" ") { "<code>${it.p.id}</code> ${it.p.verdict}" } + "</div>"
        }
        val checks = """
        <div class='card'><h2>快捷操作</h2><div class='row'>
        <a class='btn' href='/calibrate/new'>为测量生成校准候选</a>
        <a class='btn' href='/sequence/new'>层序一致性检查</a>
        <a class='btn' href='/casepack'>打开离线案例包剧本</a>
        <a class='btn' href='/import-snapshot'>导入快照</a></div></div>"""
        return """
        <p class='muted'>项目 ${esc(st.projectId)} · 事件 ${st.events.size} 条 · 最新序号 ${st.events.lastOrNull()?.seq ?: 0}</p>
        $conflicts$checks
        <div class='card'><h2>源输入（按版本族；原始表示不可覆盖，修订以新版本表达）</h2>
        <table><tr><th>版本族</th><th>版本链</th><th>来源</th><th>单位 / 诊断</th></tr>$srcRows</table></div>
        <div class='card'><h2>派生结果（算法版本 + 参数指纹 + 直接来源）</h2>
        <table><tr><th>ID</th><th>类型</th><th>算法</th><th>指纹</th><th>直接来源</th><th>状态</th></tr>$derRows</table></div>
        <div class='card'><h2>人工决定与撤销（全部入历史）</h2>
        <table><tr><th>ID</th><th>裁定</th><th>对象</th><th>操作者</th><th>理由</th><th></th></tr>$decRows</table></div>
        """
    }
}
