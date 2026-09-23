package gsb

import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

fun renderDashboard(wb: Workbench, st: ProjectState, asof: Int?): String {
    val sb = StringBuilder()
    sb.append("<h1>审阅总览</h1>")
    if (asof != null) {
        sb.append("""<div class="banner">历史视图：仅回放前 <b>$asof</b> 个事件（只读，恢复当时的依赖版本与可见结论）。<a href="/">回到当前视图</a></div>""")
    }
    sb.append("""<div class="card"><b>审阅流程（一个完整叙事，不是互不相干的按钮）</b>
      <ol class="flow">
      <li><a href="/import">导入</a>案例包步骤，逐项核对六类来源（测量值与标准差 / 曲线版本 / 偏移分布 / 年代系统 / 层序约束 / 排除决定）</li>
      <li>在下方“校准结果”中用不同偏移假设产生候选，展开任一区间看积分证据</li>
      <li>对区间作出接受 / 否定；层序冲突到<a href="/stratigraphy">层序关系</a>页处理候选调整</li>
      <li>继续案例包后续步骤（曲线升版、局部修订、冲突决定），观察哪些结果过期</li>
      <li>从<a href="/events">事件历史</a>回到任意旧事件复核；最后<a href="/export">导出</a>、删除本地库再导入</li>
      </ol></div>""")

    sb.append("<h2>来源（原始表示保留，修订走新版本 + 父版本关系）</h2><table>")
    sb.append("<tr><th>类别</th><th>流</th><th>版本</th><th>原始值（原表示）</th><th>来源出处</th><th>稳定身份</th><th>状态</th></tr>")
    for (s in st.sources.values.sortedWith(compareBy({ it.kind }, { it.stream }, { it.version }))) {
        val status = when {
            s.excluded -> "<span class=\"bad\">已被候选排除（不改写测量）</span>"
            s.supersededBy != null -> "<span class=\"warn\">已被取代 → <code>${s.supersededBy}</code></span>"
            else -> "<span class=\"ok\">当前</span>"
        }
        sb.append("<tr><td>${esc(s.kind)}</td><td><b>${esc(s.stream)}</b></td><td>v${s.version}</td>")
        sb.append("<td><span data-vid=\"${s.sourceId}/raw\">${esc(s.raw.toString())}</span></td>")
        sb.append("<td><span data-vid=\"${s.sourceId}/provenance\">${esc(s.provenance.toString())}</span></td>")
        sb.append("<td><code>${s.sourceId}</code></td><td>$status</td></tr>")
    }
    sb.append("</table>")

    sb.append("<h2>校准结果（多峰不压成平均年；过期结果仍可查看但不冒充当前）</h2><table>")
    sb.append("<tr><th>稳定身份</th><th>测量</th><th>曲线</th><th>偏移假设</th><th>峰数</th><th>日历区间</th><th>算法/指纹</th><th>状态</th><th></th></tr>")
    for (d in st.derived.values) {
        val meas = st.sources[d.inputs["measurement"]]
        val curve = st.sources[d.inputs["curve"]]
        val off = st.sources[d.inputs["offset"]]
        val nPeaks = d.payload["peaks"]!!.jsonArray.size
        val nInt = d.payload["intervals"]!!.jsonArray.size
        val nDiag = d.payload["diagnostics"]!!.jsonArray.size
        val rowCls = if (d.status == "stale") " class=\"stale\"" else ""
        val mu = off?.raw?.get("mu")?.jsonPrimitive?.double ?: 0.0
        val sg = off?.raw?.get("sigma")?.jsonPrimitive?.double ?: 0.0
        sb.append("<tr$rowCls><td><code data-vid=\"${d.derivedId}/id\">${d.derivedId}</code></td>")
        sb.append("<td>${esc(meas?.stream ?: "?")} v${meas?.version}</td><td>${esc(curve?.stream ?: "?")} v${curve?.version}</td>")
        sb.append("<td>${esc(off?.stream ?: "?")} v${off?.version} <span class='muted'>(ΔR ${"%.0f".format(mu)}±${"%.0f".format(sg)})</span></td>")
        sb.append("<td data-vid=\"${d.derivedId}/peaks\">$nPeaks</td>")
        sb.append("<td>$nInt 个并列区间${if (nDiag > 0) " · <span class='warn'>$nDiag 条诊断</span>" else ""}</td>")
        sb.append("<td><code>${d.algorithmVersion}</code><br><code>${d.paramFingerprint}</code></td>")
        sb.append("<td>${if (d.status == "stale") "<span class='warn'>已过期</span><br><span class='muted'>${esc(d.staleReason ?: "")}</span>" else "<span class='ok'>当前</span>"}</td>")
        sb.append("<td><a href=\"/derived/${urlenc(d.derivedId)}\">审阅</a></td></tr>")
    }
    sb.append("</table>")

    if (st.conflicts.isNotEmpty()) {
        sb.append("<h2>层序冲突（产生候选调整，不改写测量）</h2><table>")
        sb.append("<tr><th>冲突身份</th><th>环路</th><th>状态</th><th>合法候选（全部保留）</th></tr>")
        for (c in st.conflicts.values) {
            sb.append("<tr><td><code>${c.conflictId}</code></td><td>${c.cycle.joinToString(" → ")}</td>")
            sb.append("<td>${if (c.resolved) "<span class='ok'>已有候选被接受</span>" else "<span class='bad'>未解决</span>"}</td><td>")
            sb.append(st.candidates.values.filter { it.conflictId == c.conflictId }.joinToString("<br>") { cand ->
                "<code>${cand.action}</code> → ${esc(cand.target)} <span class='pill'>${cand.decision ?: "open"}</span>"
            })
            sb.append("</td></tr>")
        }
        sb.append("</table><p><a href=\"/stratigraphy\">前往层序页处理 →</a></p>")
    }

    sb.append("<h2>产生新候选（选择测量 × 曲线版本 × 偏移假设）</h2><div class='card'>")
    sb.append(renderComputeForm(st))
    sb.append("</div>")

    sb.append("<h2>导出 / 导入（删除本地数据库后身份、顺序、来源关系仍保持）</h2><div class='card'>")
    sb.append("<p><a href=\"/export\"><b>下载导出文件</b></a>（gsb-export/1，哈希链事件日志，共 ${st.events.size} 个事件）</p>")
    sb.append("<form method='post' action='/import-log'><textarea name='log' placeholder='粘贴导出 JSON：演示删除本地数据库后再导入'></textarea><br>")
    sb.append("<button type='submit'>删除本地数据并按导出重建</button></form></div>")
    return page("审阅总览", sb.toString())
}

fun renderComputeForm(st: ProjectState): String {
    fun latest(kind: String) = st.sourcesOfKind(kind).groupBy { it.stream }
        .mapValues { it.value.maxBy { s -> s.version } }.values.toList()
    val excludedStreams = st.sourcesOfKind("exclusionDecision")
        .mapNotNull { it.raw["targetStream"]?.jsonPrimitive?.content }.toSet()
    val measurements = latest("measurement").filter { it.stream !in excludedStreams }
    val curves = latest("curveVersion")
    val offsets = latest("offsetDistribution")
    if (measurements.isEmpty() || curves.isEmpty() || offsets.isEmpty()) {
        return "<p class='muted'>先导入测量、曲线版本与偏移分布。</p>"
    }
    val sb = StringBuilder("<form method='post' action='/compute'>")
    fun select(name: String, items: List<Source>, label: (Source) -> String) {
        sb.append(" <select name='$name'>")
        for (s in items) sb.append("<option value='${s.sourceId}'>${esc(label(s))}</option>")
        sb.append("</select>")
    }
    sb.append("<label>测量 </label>"); select("measurement", measurements) { "${it.stream} v${it.version} (${it.raw["c14AgeBP"]!!.jsonPrimitive.content}±${it.raw["sigma"]!!.jsonPrimitive.content} BP)" }
    sb.append(" <label>曲线 </label>"); select("curve", curves) { "${it.stream} v${it.version}" }
    sb.append(" <label>偏移假设 </label>"); select("offset", offsets) { "${it.stream} v${it.version} (ΔR ${it.raw["mu"]!!.jsonPrimitive.content}±${it.raw["sigma"]!!.jsonPrimitive.content})" }
    sb.append(" <button type='submit'>校准并产生候选</button></form>")
    return sb.toString()
}

fun renderImportPage(wb: Workbench): String {
    val sb = StringBuilder("<h1>导入</h1>")
    sb.append("<h2>离线案例包：碳年候选席（按顺序重放）</h2><div class='card'><table>")
    sb.append("<tr><th>步骤</th><th>说明</th><th></th></tr>")
    for (name in wb.casepackSteps()) {
        sb.append("<tr><td><code>$name</code></td><td>${esc(casepackDescription(wb, name))}</td>")
        sb.append("<td><form method='post' action='/casepack/$name'><button type='submit'>应用此步骤</button></form></td></tr>")
    }
    sb.append("</table></div>")
    sb.append("""<h2>粘贴批量 JSON（同样结构；格式错误会被明确拒绝）</h2><div class='card'>
      <form method='post' action='/import'><textarea name='batch' placeholder='{"batch":"...","events":[...]}'></textarea><br>
      <button type='submit'>导入</button></form></div>""")
    return page("导入", sb.toString())
}

fun casepackDescription(wb: Workbench, name: String): String = try {
    val f = java.io.File(wb.casepackDir, "$name.json")
    kotlinx.serialization.json.Json.parseToJsonElement(f.readText()).jsonObject["description"]?.jsonPrimitive?.content ?: ""
} catch (e: Exception) { "" }
