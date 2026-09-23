package gsb

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

fun renderDerived(wb: Workbench, d: Derived, expandEvidence: String?): String {
    val st = wb.state
    val sb = StringBuilder("<h1>校准候选审阅 · <code>${d.derivedId}</code></h1>")
    if (d.status == "stale") {
        sb.append("<div class='banner'><b>此结果已过期（${esc(d.staleReason ?: "")}）。</b>" +
            "它仍然可以查看与追溯，但不能作为当前结论；请用新输入重新计算。</div>")
    }
    sb.append("<p class='muted'>算法 <code>${d.algorithmVersion}</code> · 参数指纹 <code>${d.paramFingerprint}</code> · 生成事件 <code>${d.eventId}</code></p>")

    sb.append("<h2>直接来源（每个输入可追溯到源输入与出处）</h2><table>")
    sb.append("<tr><th>角色</th><th>流</th><th>版本</th><th>稳定身份</th><th>原始表示</th><th>出处</th></tr>")
    for ((role, sid) in d.inputs.toList()) {
        val s = st.sources[sid]
        sb.append("<tr><td>$role</td><td>${esc(s?.stream ?: sid)}</td><td>v${s?.version}</td>")
        sb.append("<td><code data-vid='${d.derivedId}/input/$role'>$sid</code></td>")
        sb.append("<td>${esc(s?.raw?.toString() ?: "?")}</td><td>${esc(s?.provenance?.toString() ?: "?")}</td></tr>")
    }
    sb.append("</table>")

    sb.append("<h2>校准曲线（固定版本，分段线性插值）</h2>")
    sb.append(curveSvg(wb, d))
    sb.append("<h2>校准后日历概率密度（多峰保留，不压成平均年）</h2>")
    sb.append(densitySvg(d))

    val diags = d.payload["diagnostics"]!!.jsonArray
    if (diags.isNotEmpty()) {
        sb.append("<h2>诊断（边界等情形显式留痕）</h2><ul>")
        for (x in diags) sb.append("<li class='warn'>${esc(x.jsonPrimitive.content)}</li>")
        sb.append("</ul>")
    }

    val peaks = d.payload["peaks"]!!.jsonArray
    sb.append("<h2>峰（${peaks.size} 个并列峰）</h2><table><tr><th>峰顶点</th><th>支持范围</th><th>质量</th></tr>")
    peaks.forEach { el ->
        val pk = el.jsonObject
        sb.append("<tr><td data-vid='${d.derivedId}/peak/${pk["apexBp"]!!.jsonPrimitive.int}'>" +
            "${Era.calBpToLabel(pk["apexBp"]!!.jsonPrimitive.int.toDouble())} (${pk["apexBp"]!!.jsonPrimitive.int} calBP)</td>")
        sb.append("<td>${Era.calBpToLabel(pk["hiBp"]!!.jsonPrimitive.int.toDouble())} – ${Era.calBpToLabel(pk["loBp"]!!.jsonPrimitive.int.toDouble())}</td>")
        sb.append("<td>${"%.4f".format(pk["mass"]!!.jsonPrimitive.double)}</td></tr>")
    }
    sb.append("</table>")

    sb.append("<h2>并列日历区间与积分证据</h2>")
    val intervals = d.payload["intervals"]!!.jsonArray
    intervals.forEachIndexed { idx, el ->
        val iv = el.jsonObject
        val ev = iv["evidence"]!!.jsonObject
        val key = "${d.derivedId}#$idx"
        val current = st.adjudications[key]
        val openAttr = if (expandEvidence == key) " open" else ""
        sb.append("<div class='card'>")
        sb.append("<b data-vid='${d.derivedId}/interval$idx/label'>#$idx</b> · ")
        sb.append("HPD <span class='pill'>${"%.1f%%".format(iv["level"]!!.jsonPrimitive.double * 100)}</span> ")
        sb.append("<b data-vid='${d.derivedId}/interval$idx/range'>${esc(iv["hiLabel"]!!.jsonPrimitive.content)} – ${esc(iv["loLabel"]!!.jsonPrimitive.content)}</b> ")
        sb.append("（${iv["hiBp"]!!.jsonPrimitive.int}–${iv["loBp"]!!.jsonPrimitive.int} calBP） · ")
        sb.append("概率 <b data-vid='${d.derivedId}/interval$idx/prob'>${"%.4f".format(iv["prob"]!!.jsonPrimitive.double)}</b> ")
        sb.append(when (current?.decision) {
            "accepted" -> "<span class='ok'>已接受</span>"
            "rejected" -> "<span class='bad'>已否定</span>"
            else -> "<span class='muted'>未裁定</span>"
        })
        sb.append("<details$openAttr><summary>展开积分证据（读回事件档案）</summary><ul>")
        for ((k, v) in ev) {
            val txt = if (v is JsonObject) v.toString() else v.jsonPrimitive.content
            sb.append("<li><code>$k</code> = <span data-vid='${d.derivedId}/interval$idx/evidence/$k'>${esc(txt)}</span></li>")
        }
        sb.append("</ul><p class='muted'>概率 = 区间原始权重之和 rawSum / 全网格 totalSum；网格 ${esc(ev["gridStep"]!!.jsonPrimitive.content)} 年/点，" +
            "线性插值与归一化在固定曲线版本与算法版本下可重现。</p></details>")
        sb.append("""<form method='post' action='/adjudicate' style='margin-top:6px'>
          <input type='hidden' name='derivedId' value='${esc(d.derivedId)}'>
          <input type='hidden' name='intervalIndex' value='$idx'>
          <select name='decision'><option value='accepted'>接受</option><option value='rejected'>否定</option></select>
          <input type='text' name='reason' placeholder='裁定理由'>
          <input type='text' name='reviewer' placeholder='reviewer:id' value='reviewer:lin'>
          <button type='submit'>写入新裁定事件</button></form>""")
        if (current != null) {
            sb.append("<p class='muted'>当前裁定：${esc(current.decision)}，理由：${esc(current.reason)}，${esc(current.reviewer)}，事件 <code>${current.eventId}</code></p>")
        }
        sb.append("</div>")
    }

    sb.append("<h2>此候选的裁定历史（接受/否定/撤销全部留痕，不覆盖）</h2><table>")
    sb.append("<tr><th>事件</th><th>时间</th><th>区间</th><th>决定</th><th>理由</th><th>审阅者</th><th></th></tr>")
    val related = st.events.filter { ev ->
        (ev.type == "interval.adjudicated" && ev.payload["derivedId"]?.jsonPrimitive?.content == d.derivedId) ||
            (ev.type == "decision.undone" &&
                st.events.any { t -> t.id == ev.payload["targetEventId"]?.jsonPrimitive?.content &&
                    t.payload["derivedId"]?.jsonPrimitive?.content == d.derivedId })
    }
    for (ev in related) {
        if (ev.type == "interval.adjudicated") {
            val undone = ev.id in st.undoneEvents
            sb.append("<tr${if (undone) " class='stale'" else ""}><td><code>${ev.id}</code>${if (undone) " <span class='warn'>已撤销</span>" else ""}</td>")
            sb.append("<td>${ev.ts}</td><td>#${ev.payload["intervalIndex"]!!.jsonPrimitive.content}</td>")
            sb.append("<td>${esc(ev.payload["decision"]!!.jsonPrimitive.content)}</td>")
            sb.append("<td>${esc(ev.payload["reason"]!!.jsonPrimitive.content)}</td>")
            sb.append("<td>${esc(ev.payload["reviewer"]!!.jsonPrimitive.content)}</td>")
            sb.append("<td><form method='post' action='/undo'><input type='hidden' name='targetEventId' value='${ev.id}'>" +
                "<input type='hidden' name='back' value='/derived/${urlenc(d.derivedId)}'>" +
                "<input type='text' name='reason' placeholder='撤销原因' style='width:140px'><button type='submit'>撤销</button></form></td></tr>")
        } else {
            sb.append("<tr><td><code>${ev.id}</code></td><td>${ev.ts}</td><td>—</td><td class='warn'>decision.undone</td>")
            sb.append("<td>${esc(ev.payload["reason"]?.jsonPrimitive?.content ?: "")}（目标 ${esc(ev.payload["targetEventId"]!!.jsonPrimitive.content)}）</td><td>—</td><td></td></tr>")
        }
    }
    sb.append("</table>")
    sb.append("<p><a href='/'>← 返回总览</a></p>")
    return page("校准候选 · ${d.derivedId}", sb.toString())
}
