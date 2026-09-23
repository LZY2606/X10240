package gsb

import kotlinx.serialization.json.jsonPrimitive

fun renderStratigraphy(wb: Workbench): String {
    val st = wb.state
    val sb = StringBuilder("<h1>层序关系与冲突候选</h1>")
    val constraints = st.sourcesOfKind("stratConstraint")
    val nodes = LinkedHashSet<String>()
    constraints.forEach { c ->
        nodes += c.raw["older"]!!.jsonPrimitive.content
        nodes += c.raw["younger"]!!.jsonPrimitive.content
    }
    if (constraints.isEmpty()) {
        sb.append("<p class='muted'>尚无层序约束。</p>")
        return page("层序关系", sb.toString())
    }
    // longest-path layering (older -> younger), measured streams only
    val succ = HashMap<String, MutableList<String>>()
    for (c in constraints.filter { !it.excluded }) {
        succ.getOrPut(c.raw["older"]!!.jsonPrimitive.content) { mutableListOf() }
            .add(c.raw["younger"]!!.jsonPrimitive.content)
    }
    val level = HashMap<String, Int>()
    fun lev(u: String, seen: HashSet<String> = hashSetOf()): Int {
        level[u]?.let { return it }
        if (!seen.add(u)) return 0 // cycle safety
        val v = 1 + (succ[u]?.maxOfOrNull { lev(it, seen) } ?: 0)
        level[u] = v
        return v
    }
    nodes.forEach { lev(it) }
    val order = nodes.sortedWith(compareByDescending<String> { level[it] ?: 0 }.thenBy { it })
    val pos = HashMap<String, Pair<Int, Int>>()
    val cols = order.size
    order.forEachIndexed { i, n ->
        val x = 90 + i * (700 / (cols - 1).coerceAtLeast(1))
        val y = 250 - ((level[n] ?: 0) - 1) * 70
        pos[n] = x to y
    }
    sb.append("<svg width='860' height='320' viewBox='0 0 860 320' role='img' aria-label='stratigraphic relations'>")
    sb.append("<text x='14' y='20' font-size='11' fill='#666'>箭头：older → younger（上=较年轻，下=较老）；红色虚线=被候选排除的约束</text>")
    for (c in constraints) {
        val o = c.raw["older"]!!.jsonPrimitive.content
        val y = c.raw["younger"]!!.jsonPrimitive.content
        val (x1, y1) = pos[o] ?: continue
        val (x2, y2) = pos[y] ?: continue
        val stroke = if (c.excluded) "#a23122" else "#1f4e79"
        val dash = if (c.excluded) "stroke-dasharray='6 4'" else ""
        sb.append("<line x1='$x1' y1='${y1 - 16}' x2='$x2' y2='${y2 + 16}' stroke='$stroke' stroke-width='2' $dash marker-end='url(#arr)' data-vid='${c.sourceId}/edge'/>")
        sb.append("<text x='${(x1 + x2) / 2}' y='${(y1 + y2) / 2 - 4}' font-size='9' text-anchor='middle' fill='#666'>${esc(c.stream)}</text>")
    }
    sb.append("<defs><marker id='arr' markerWidth='8' markerHeight='8' refX='6' refY='4' orient='auto'><path d='M0,0 L8,4 L0,8 Z' fill='#1f4e79'/></marker></defs>")
    for (n in order) {
        val (x, y) = pos[n] ?: continue
        sb.append("<rect x='${x - 42}' y='${y - 16}' width='84' height='32' rx='5' fill='#eef3fa' stroke='#4a6fa5'/>")
        sb.append("<text x='$x' y='${y + 4}' font-size='12' text-anchor='middle'>$n</text>")
    }
    sb.append("</svg>")

    sb.append("<h2>约束与出处</h2><table><tr><th>约束</th><th>关系</th><th>依据</th><th>出处</th><th>稳定身份</th><th>状态</th></tr>")
    for (c in constraints.sortedWith(compareBy { it.stream })) {
        val o = c.raw["older"]!!.jsonPrimitive.content
        val y = c.raw["younger"]!!.jsonPrimitive.content
        sb.append("<tr><td><b>${esc(c.stream)}</b> v${c.version}</td><td>$o older-than $y</td>")
        sb.append("<td>${esc(c.raw["basis"]?.jsonPrimitive?.content ?: "")}</td>")
        sb.append("<td>${esc(c.provenance.toString())}</td><td><code>${c.sourceId}</code></td>")
        sb.append("<td>${if (c.excluded) "<span class='bad'>已被候选排除（原始约束仍在档案中）</span>" else "<span class='ok'>生效中</span>"}</td></tr>")
    }
    sb.append("</table>")

    sb.append("<h2>冲突与全部合法候选调整</h2>")
    if (st.conflicts.isEmpty()) {
        sb.append("<p class='muted'>当前没有检测到层序冲突。</p>")
    }
    for (conf in st.conflicts.values) {
        sb.append("<div class='card'><b><code>${conf.conflictId}</code></b> 环路：")
        sb.append(conf.cycle.joinToString(" → "))
        sb.append(" · ${if (conf.resolved) "<span class='ok'>已接受某候选</span>" else "<span class='bad'>待决定</span>"}")
        sb.append("<table><tr><th>候选身份</th><th>动作</th><th>对象</th><th>理由</th><th>裁定</th></tr>")
        for (cand in st.candidates.values.filter { it.conflictId == conf.conflictId }) {
            sb.append("<tr><td><code data-vid='${cand.candidateId}/id'>${cand.candidateId}</code></td>")
            sb.append("<td>${esc(cand.action)}</td><td><code>${esc(cand.target)}</code></td>")
            sb.append("<td>${esc(cand.rationale)}</td><td>")
            sb.append(cand.decision?.let { "<span class='pill'>$it</span>" }
                ?: """<form method='post' action='/candidate/${urlenc(cand.candidateId)}'>
                  <select name='decision'><option value='accepted'>接受</option><option value='rejected'>拒绝</option></select>
                  <input type='text' name='reason' placeholder='理由' style='width:130px'>
                  <input type='text' name='reviewer' placeholder='reviewer:id' value='reviewer:lin'>
                  <button>决定</button></form>""")
            sb.append("</td></tr>")
        }
        sb.append("</table><p class='muted'>接受“排除约束”只会把该约束标记为排除，绝不改写任何原测量；无法唯一决定时全部候选保留。</p></div>")
    }
    return page("层序关系", sb.toString())
}

fun renderEventList(wb: Workbench): String {
    val st = wb.state
    val sb = StringBuilder("<h1>事件历史（哈希链；点击序号回到当时的可见结论）</h1>")
    sb.append("<table><tr><th>#</th><th>事件身份</th><th>类型</th><th>时间</th><th>前一事件</th><th>摘要</th></tr>")
    for (e in st.events) {
        val summary = when (e.type) {
            "source.imported", "source.revised" -> "${e.payload["kind"]!!.jsonPrimitive.content}/${e.payload["stream"]!!.jsonPrimitive.content} v${e.payload["version"]!!.jsonPrimitive.content}"
            "calibration.computed" -> "cal ${e.payload["derivedId"]!!.jsonPrimitive.content}"
            "interval.adjudicated" -> "${e.payload["decision"]!!.jsonPrimitive.content} ${e.payload["derivedId"]!!.jsonPrimitive.content}#${e.payload["intervalIndex"]!!.jsonPrimitive.content}"
            "result.expired" -> "${e.payload["derivedIds"]!!.toString()} 过期"
            "strat.conflict.detected" -> "conflict ${e.payload["conflictId"]!!.jsonPrimitive.content}"
            "candidate.adjudicated" -> "${e.payload["decision"]!!.jsonPrimitive.content} ${e.payload["candidateId"]!!.jsonPrimitive.content}"
            "decision.undone" -> "undo ${e.payload["targetEventId"]!!.jsonPrimitive.content}"
            else -> e.payload.toString().take(80)
        }
        val undone = if (e.id in st.undoneEvents) " <span class='warn'>已撤销</span>" else ""
        sb.append("<tr><td><a href='/?asof=${e.seq}'>${e.seq}</a></td>")
        sb.append("<td><code data-vid='event/${e.seq}/id'>${e.id}</code>$undone</td>")
        sb.append("<td>${esc(e.type)}</td><td>${e.ts}</td>")
        sb.append("<td><code>${e.prevId}</code></td><td>${esc(summary)}</td></tr>")
    }
    sb.append("</table>")
    return page("事件历史", sb.toString())
}
