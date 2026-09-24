package workbench

import kotlinx.serialization.json.*

object Web2 {
    private val prettyJson = Json { prettyPrint = true }

    fun sourceDetail(st: State, id: String): String {
        val s = st.sources[id]
            ?: return "<p class='bad'>来源 $id 不存在。</p>"
        val fam = st.sourceFamily[id] ?: id
        val isLatest = st.latestInFamily(fam)?.id == id
        val diags = st.sourceDiag[id]?.joinToString("") { "<span class='diag'>${esc(it)}</span>" } ?: ""
        val dependents = st.derived.values.filter { id in it.sourceIds }
        val depRows = dependents.joinToString("") {
            "<li><a href='/derived/${it.id}'><code>${it.id}</code></a> ${esc(it.kind)} — ${st.derivedStatusNow(it.id)}</li>"
        }
        val parent = if (s.parentId == null) "—（首版）" else "<a href='/sources/${s.parentId}'><code>${s.parentId}</code></a>"
        val reviseForm = """
        <details><summary>提交修订（生成 v${s.version + 1}，父版本 $id；原始版本保持不可覆盖）</summary>
        <form method='post' action='/sources/$id/revise'>
        <p>新的原始表示（JSON，保留单位 ${esc(s.unit)}）：</p>
        <textarea name='payload'>${esc(s.payload)}</textarea>
        <p>修订来源说明：<input name='provenance' size='60' value='${esc(s.provenance)}（修订）'></p>
        <button>提交修订</button></form></details>"""
        return """
        <div class='card'><h2>${esc(s.kind.name)} <code data-stable-id='source-$id'>$id</code> · v${s.version}
        ${if (isLatest) "<span class='tag t-cur'>latest</span>" else "<span class='tag t-stale'>superseded</span>"}</h2>
        <p><b>来源：</b>${esc(s.provenance)}<br><b>单位/坐标基准：</b>${esc(s.unit)}<br>
        <b>版本族：</b><code>$fam</code> · <b>父版本：</b>$parent</p>
        $diags
        <h3>原始表示（提交时原样保留，未规范化）</h3>
        <pre class='json' data-stable-id='raw-$id'>${esc(s.payload)}</pre>
        <h3>依赖此版本的派生结果</h3>
        <ul>${depRows.ifBlank { "<li class='muted'>无</li>" }}</ul>
        $reviseForm</div>
        <p><a href='/'>← 返回总览</a></p>"""
    }

    fun derivedDetail(st: State, id: String, atSeq: Long? = null): String {
        val d = st.derived[id] ?: return "<p class='bad'>派生结果 $id 不存在。</p>"
        val status = st.derivedStatusNow(id)
        val sourceLinks = d.sourceIds.joinToString(" ") { sid ->
            val href = if (st.sources.containsKey(sid)) "/sources/$sid" else "/derived/$sid"
            "<a href='$href'><code>$sid</code></a>"
        }
        val staleDiag = if (d.status == DerivedStatus.STALE)
            "<span class='diag'>${esc(d.staleReason ?: "")}</span>" else ""
        val head = """
        <div class='card'><h2>${esc(d.kind)} <code data-stable-id='derived-$id'>$id</code>
        ${if (atSeq != null) "<span class='tag t-unk'>历史视图 @ 事件 $atSeq</span>" else ""}</h2>
        <p>算法版本 <code>${esc(d.algorithmVersion)}</code> ·
        参数指纹 <code data-stable-id='fp-$id'>${esc(d.paramFingerprint)}</code><br>
        直接来源：$sourceLinks<br>
        状态：${statusTagOf(status)} $staleDiag</p>"""
        val body = when (d.kind) {
            "calibration" -> calibrationBody(st, id, d)
            "sequence-check" -> sequenceBody(st, id, d)
            else -> "<pre class='json'>${esc(d.payload)}</pre>"
        }
        val decisions = st.decisions.filter { it.p.targetId == id }
        val decList = decisions.joinToString("") { dc ->
            val voided = dc.voidedBy?.let { " <span class='tag t-stale'>已撤销 $it</span>" } ?: ""
            "<li><code>${dc.p.id}</code> ${dc.p.verdict} by ${esc(dc.p.author)} — ${esc(dc.p.reason)}$voided</li>"
        }
        val sourceOptions = d.sourceIds.filter { st.sources.containsKey(it) }.joinToString("") {
            "<option value='$it'>$it ${esc(st.sources[it]!!.kind.name)}</option>"
        }
        val decideForm = """
        <details><summary>作出裁定（接受 / 否定 / 参数调整，全部写入事件历史）</summary>
        <form method='post' action='/decisions'>
        <input type='hidden' name='targetType' value='derived'>
        <input type='hidden' name='targetId' value='$id'>
        <p>裁定：<select name='verdict'><option>ACCEPT</option><option>REJECT</option><option>ADJUST</option></select>
        操作者：<input name='author' value='reviewer'> 样品：<input name='labCode' size='10'></p>
        <p>理由：<input name='reason' size='70' required></p>
        <p>ADJUST 时修订直接来源：<select name='reviseSourceId'>$sourceOptions</select>
        新参数 JSON <input name='newPayload' size='44'>
        来源说明 <input name='adjustProvenance' size='30'></p>
        <button>写入裁定事件</button></form></details>"""
        return head + body + "</div>" +
            "<div class='card'><h3>针对此结果的人工决定</h3>" +
            "<ul>${decList.ifBlank { "<li class='muted'>尚无</li>" }}</ul>$decideForm</div>" +
            "<p><a href='/'>← 返回总览</a></p>"
    }

    private fun statusTagOf(status: DerivedStatus): String = when (status) {
        DerivedStatus.CURRENT -> "<span class='tag t-cur'>CURRENT</span>"
        DerivedStatus.STALE -> "<span class='tag t-stale'>STALE 过期（可查看）</span>"
        DerivedStatus.CONFLICTED -> "<span class='tag t-conf'>CONFLICTED 决定冲突</span>"
        DerivedStatus.UNKNOWN -> "<span class='tag t-unk'>UNKNOWN</span>"
    }

    private fun calibrationBody(st: State, id: String, d: DerivedRecord): String {
        val res = Json.decodeFromString(CalibrationResult.serializer(), d.payload)
        val measSrc = st.sources[res.measurementSourceId]
        val curveSrc = st.sources[res.curveSourceId]
        val offSrc = res.offsetSourceId?.let { st.sources[it] }
        val meas = measSrc?.let { src -> runCatching {
            val o = Json.parseToJsonElement(src.payload).jsonObject
            Measurement(o["labCode"]!!.jsonPrimitive.content,
                o["ageBp"]!!.jsonPrimitive.doubleOrNull ?: 0.0,
                o["sigma"]!!.jsonPrimitive.doubleOrNull ?: 0.0,
                o["material"]?.jsonPrimitive?.content ?: "?")
        }.getOrNull() }
        val curve = curveSrc?.let { src -> runCatching {
            val o = Json.parseToJsonElement(src.payload).jsonObject
            CurveData(o["name"]!!.jsonPrimitive.content,
                o["points"]!!.jsonArray.map { p ->
                    val po = p.jsonObject
                    CurvePoint(po["calBp"]!!.jsonPrimitive.double,
                        po["c14Age"]!!.jsonPrimitive.double,
                        po["sigma"]!!.jsonPrimitive.double)
                })
        }.getOrNull() }
        val offset = offSrc?.let { src -> runCatching {
            val o = Json.parseToJsonElement(src.payload).jsonObject
            ReservoirOffset(o["mean"]!!.jsonPrimitive.double,
                o["sigma"]!!.jsonPrimitive.double,
                o["samples"]?.jsonArray?.mapNotNull { it.jsonPrimitive.doubleOrNull } ?: emptyList())
        }.getOrNull() }
        val diags = res.diagnostics.joinToString("") { "<span class='diag'>${esc(it)}</span>" }
        val curveSvg = if (curve != null) Svg.curveView(curve, meas, "$id-curve", offset) else ""
        val densitySvg = Svg.densityView(res, "$id-density")
        fun intervalTable(ivs: List<CalInterval>, tag: String): String {
            if (ivs.isEmpty()) return "<p class='muted'>无 $tag 区间。</p>"
            val rows = ivs.mapIndexed { i, iv ->
                val crossTag = if (iv.bceCeCross) "<span class='tag t-conf'>BCE/CE 跨界</span>" else ""
                """
                <tr data-stable-id='$id-interval-$tag-$i'>
                <td>${esc(iv.level)}</td>
                <td><b>${esc(iv.loLabel)} … ${esc(iv.hiLabel)}</b><br>
                <span class='muted'>calBP ${iv.loCalBp.toInt()}–${iv.hiCalBp.toInt()}</span></td>
                <td>${"%.4f".format(iv.probability)}</td><td>$crossTag</td>
                <td><details><summary>展开积分证据</summary>
                <p class='muted'>区间概率 = 归一化密度在该区间的梯形积分；常数与算法版本如下，可逐步复核。</p>
                <iframe src='/derived/$id/evidence?lo=${iv.loCalBp}&hi=${iv.hiCalBp}'
                style='width:100%;height:230px;border:1px solid #ddd;border-radius:6px'></iframe>
                <p><a href='/derived/$id/evidence?lo=${iv.loCalBp}&hi=${iv.hiCalBp}&format=json'>机器可读 JSON</a></p>
                </details></td></tr>"""
            }.joinToString("")
            return "<table><tr><th>级别</th><th>日历区间</th><th>区间概率</th><th>标记</th><th>积分证据</th></tr>$rows</table>"
        }
        val peaks = res.peaks.joinToString("") {
            "<li data-stable-id='$id-peak-${it.calBp.toInt()}'>峰 @ <b>${esc(it.label)}</b>" +
            "（calBP ${it.calBp.toInt()}，密度 ${"%.5f".format(it.density)}）</li>"
        }
        val offsetNote = if (offSrc != null)
            "（<a href='/sources/${offSrc.id}'><code>${offSrc.id}</code></a> ${esc(offSrc.provenance)}）" else ""
        val unknown = if (res.unknown)
            "<p class='bad'>结果未知：${esc(res.unknownReason ?: "")}（候选、拒绝原因与未知状态均为一等数据）</p>" else ""
        val fullJson = prettyJson.encodeToString(CalibrationResult.serializer(), res)
        return """
        $unknown$diags
        <div class='grid2'>
        <div><h3>校准曲线（含测量带与偏移假设）</h3>$curveSvg</div>
        <div><h3>校准后概率密度（多峰保留，不压成平均年）</h3>$densitySvg</div></div>
        <h3>峰（${res.peaks.size}）</h3><ul>$peaks</ul>
        <h3>68.2% HDR 并列日历区间（${res.intervals68.size}）</h3>${intervalTable(res.intervals68, "i68")}
        <h3>95.4% HDR 并列日历区间（${res.intervals95.size}）</h3>${intervalTable(res.intervals95, "i95")}
        <h3>偏移假设</h3><p>${esc(res.offsetSummary)}$offsetNote</p>
        <details><summary>完整机器可读结果（JSON）</summary><pre class='json'>${esc(fullJson)}</pre></details>"""
    }

    private fun sequenceBody(st: State, id: String, d: DerivedRecord): String {
        val r = Json.decodeFromString(SequenceCheckResult.serializer(), d.payload)
        val svg = Svg.sequenceView(r.beforeLabCode, r.afterLabCode, r.conflict,
            r.probBeforeOlderThanAfter, "$id-seq")
        val diags = r.diagnostics.joinToString("") { "<span class='diag'>${esc(it)}</span>" }
        val cands = r.candidates.mapIndexed { i, c ->
            "<li data-stable-id='$id-cand-$i'><b>${esc(c.kind)}</b> → <code>${esc(c.target)}</code>：" +
            "${esc(c.rationale)}</li>"
        }.joinToString("")
        val fullJson = prettyJson.encodeToString(SequenceCheckResult.serializer(), r)
        return """
        $diags<h3>层序关系</h3>$svg
        <p>${esc(r.note)}</p>
        <h3>候选调整（层序冲突不改写测量；无法唯一决定时保留全部合法候选）</h3>
        <ul>${cands.ifBlank { "<li class='muted'>无冲突，无需候选调整</li>" }}</ul>
        <p>输入校准：<a href='/derived/${r.beforeCalibrationId}'><code>${r.beforeCalibrationId}</code></a> 与
        <a href='/derived/${r.afterCalibrationId}'><code>${r.afterCalibrationId}</code></a>；
        约束来源 <a href='/sources/${r.constraintSourceId}'><code>${r.constraintSourceId}</code></a></p>
        <details><summary>完整机器可读结果（JSON）</summary><pre class='json'>${esc(fullJson)}</pre></details>"""
    }
}
