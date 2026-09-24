package workbench

import kotlinx.serialization.json.Json

object Web3 {

    fun evidencePage(ev: IntegrationEvidence, derivedId: String): String = """
    <div class='card' data-stable-id='evidence-$derivedId'>
    <p><b>区间：</b>${esc(ev.loLabel)} … ${esc(ev.hiLabel)}（calBP ${ev.loCalBp.toInt()}–${ev.hiCalBp.toInt()}）
    ${if (ev.bceCeCross) "<span class='tag t-conf'>BCE/CE 跨界</span>" else ""}</p>
    <table>
    <tr><td>区间概率 P</td><td><b data-stable-id='ev-p'>${"%.6f".format(ev.probability)}</b></td></tr>
    <tr><td>原始质量 trapz(raw)</td><td>${"%.6e".format(ev.rawMass)}</td></tr>
    <tr><td>归一化常数 Z</td><td>${"%.6e".format(ev.normalizationConstant)}</td></tr>
    <tr><td>网格步长 / 点数</td><td>${ev.stepYears} yr / ${ev.gridPointCount}</td></tr>
    <tr><td>公式</td><td><code>${esc(ev.formula)}</code></td></tr>
    <tr><td>算法版本</td><td><code>${esc(ev.algorithmVersion)}</code></td></tr>
    </table></div>"""

    fun sourceForm(): String = """
    <div class='card'><h2>手工提交源输入（原始表示一经提交不可覆盖）</h2>
    <form method='post' action='/sources/new'>
    <p>类型：<select name='kind'>
    <option>MEASUREMENT</option><option>CURVE</option><option>RESERVOIR_OFFSET</option>
    <option>ERA_SYSTEM</option><option>SEQUENCE_CONSTRAINT</option><option>EXCLUSION_DECISION</option></select>
    版本族（留空=新族）：<input name='family' size='14'>
    单位/基准：<input name='unit' size='16' value='BP ± 1 sigma'></p>
    <p>来源（必填）：<input name='provenance' size='80' placeholder='实验室报告 / 文献 / 会议纪要…'></p>
    <p>原始表示（JSON）：</p>
    <textarea name='payload'>{"labCode":"LAB-1001","ageBp":2450.0,"sigma":30.0,"material":"charcoal"}</textarea>
    <p><button>提交（格式错误会逐条报告并整体拒绝）</button></p></form></div>
    <p><a href='/'>← 返回</a></p>"""

    fun calibrateForm(st: State): String {
        fun opts(kind: SourceKind) = st.sources.values.filter { it.kind == kind }.joinToString("") {
            "<option value='${it.id}'>${it.id} v${it.version} ${esc(it.provenance.take(40))}</option>"
        }
        return """
        <div class='card'><h2>组合测量 × 曲线 × 储库偏移 → 校准候选</h2>
        <form method='post' action='/calibrate/new'>
        <p>测量：<select name='measurement'>${opts(SourceKind.MEASUREMENT)}</select></p>
        <p>曲线版本：<select name='curve'>${opts(SourceKind.CURVE)}</select></p>
        <p>储库偏移（可选）：<select name='offset'><option value=''>无</option>${opts(SourceKind.RESERVOIR_OFFSET)}</select></p>
        <p>网格覆盖（可选，触发边界插值）：lo <input name='gridLo' size='7'> hi <input name='gridHi' size='7'></p>
        <p><button>计算并记录派生事件</button></p></form></div>
        <p><a href='/'>← 返回</a></p>"""
    }

    fun sequenceForm(st: State): String {
        val cons = st.sources.values.filter { it.kind == SourceKind.SEQUENCE_CONSTRAINT }.joinToString("") {
            "<option value='${it.id}'>${it.id} ${esc(it.payload.take(60))}</option>"
        }
        val cals = st.derived.values.filter { it.kind == "calibration" }.joinToString("") {
            "<option value='${it.id}'>${it.id}（${st.derivedStatusNow(it.id)}）</option>"
        }
        return """
        <div class='card'><h2>层序一致性检查（冲突 → 候选调整，不改写测量）</h2>
        <form method='post' action='/sequence/new'>
        <p>层序约束：<select name='constraint'>$cons</select></p>
        <p>较老一方校准：<select name='beforeCal'>$cals</select></p>
        <p>较年轻一方校准：<select name='afterCal'>$cals</select></p>
        <p><button>检查</button></p></form></div>
        <p><a href='/'>← 返回</a></p>"""
    }

    fun casepackPage(): String {
        val a = CasePack.load()
        val entries = a.baseline.joinToString("") { e ->
            "<tr><td><code>${e.key}</code></td><td>${e.kind}</td><td>${esc(e.provenance)}</td><td>${esc(e.unit)}</td></tr>"
        }
        val scenarios = a.scenarios.joinToString("") { s ->
            "<tr><td><code>${s.id}</code></td><td>${esc(s.title)}</td><td>${esc(s.expectation)}</td></tr>"
        }
        return """
        <div class='card'><h2>${esc(a.title)}</h2>
        <p class='muted'>档案 <code>${esc(a.archiveId)}</code> v${esc(a.version)} · ${esc(a.description)}</p>
        <h3>基线输入（各自带来源）</h3>
        <table><tr><th>key</th><th>类型</th><th>来源</th><th>单位/基准</th></tr>$entries</table>
        <h3>反例场景（每次只改动一个直接来源）</h3>
        <table><tr><th>场景</th><th>标题</th><th>预期</th></tr>$scenarios</table>
        <div class='row'>
        <form method='post' action='/casepack/install-baseline'><button>① 导入基线</button></form>
        <form method='post' action='/casepack/run'><button>② 执行完整反例剧本</button></form></div>
        <p class='muted'>剧本会真实写入事件：基线导入 → 多峰/边界/储库/BCE-CE/层序校准 → 参数修订（储库偏移 v2）→
        曲线升版（intcal-shim-2）→ 局部测量勘误 → 互相冲突的人工决定与撤销。每一步的影响范围可在总览与历史中复核。</p></div>
        <p><a href='/'>← 返回</a></p>"""
    }

    fun scriptResult(logs: List<StepLog>, checks: List<ScenarioCheck>): String {
        val logRows = logs.joinToString("") {
            "<tr><td>${it.step}</td><td>${esc(it.label)}</td><td><code>${it.producedId ?: "—"}</code></td><td>${esc(it.note)}</td></tr>"
        }
        val checkRows = checks.joinToString("") {
            "<tr><td><code>${it.id}</code></td><td>${esc(it.title)}</td>" +
            "<td class='${if (it.ok) "ok" else "bad"}'>${if (it.ok) "通过" else "失败"}</td>" +
            "<td>${esc(it.evidence)}</td></tr>"
        }
        return """
        <div class='card'><h2>剧本执行日志（每步都是真实事件）</h2>
        <table><tr><th>#</th><th>步骤</th><th>产生的稳定身份</th><th>说明</th></tr>$logRows</table></div>
        <div class='card'><h2>场景校验（机器可读判断依据）</h2>
        <table><tr><th>场景</th><th>标题</th><th>结果</th><th>证据</th></tr>$checkRows</table></div>
        <p><a href='/'>前往总览复核 →</a></p>"""
    }

    fun historyList(st: State): String {
        val rows = st.events.asReversed().joinToString("") { e ->
            "<tr><td><a href='/history/${e.seq}'>${e.seq}</a></td><td><code>${e.id}</code></td>" +
            "<td>${esc(e.type)}</td><td>${esc(e.createdAt)}</td>" +
            "<td class='muted'>${esc(e.payload.toString().take(90))}…</td></tr>"
        }
        return """
        <div class='card'><h2>事件历史（追加式；冲突解决与撤销同样入史）</h2>
        <table><tr><th>seq</th><th>事件 ID</th><th>类型</th><th>时间</th><th>载荷摘要</th></tr>$rows</table></div>
        <p><a href='/'>← 返回</a></p>"""
    }

    fun historyView(st: State, seq: Long): String {
        val evt = st.events.lastOrNull()
        val srcRows = st.sources.values.joinToString("") {
            "<tr><td><code>${it.id}</code></td><td>${it.kind}</td><td>v${it.version}</td><td>${esc(it.provenance)}</td></tr>"
        }
        val derRows = st.derived.values.joinToString("") {
            "<tr><td><code>${it.id}</code></td><td>${esc(it.kind)}</td><td><code>${it.paramFingerprint}</code></td>" +
            "<td>${st.derivedStatusNow(it.id)}</td><td><a href='/derived/${it.id}'>查看当时结论</a></td></tr>"
        }
        val decRows = st.decisions.joinToString("") {
            "<tr><td><code>${it.p.id}</code></td><td>${it.p.verdict}</td><td><code>${it.p.targetId}</code></td>" +
            "<td>${if (it.voidedBy != null) "已撤销" else "生效"}</td></tr>"
        }
        return """
        <div class='card'><h2>历史视图 @ 事件 $seq</h2>
        <p class='muted'>该视图由事件 1..$seq 折叠而成：恢复当时的依赖版本与可见结论，不用最新计算覆盖历史。</p>
        <p>事件：<code>${evt?.id}</code> ${esc(evt?.type ?: "")} @ ${esc(evt?.createdAt ?: "")}</p>
        <pre class='json'>${esc(evt?.payload?.toString() ?: "")}</pre></div>
        <div class='card'><h3>当时可见的来源（${st.sources.size}）</h3>
        <table><tr><th>ID</th><th>类型</th><th>版本</th><th>来源</th></tr>$srcRows</table></div>
        <div class='card'><h3>当时可见的派生结果（${st.derived.size}）</h3>
        <table><tr><th>ID</th><th>类型</th><th>指纹</th><th>当时状态</th><th></th></tr>$derRows</table></div>
        <div class='card'><h3>当时的人工决定（${st.decisions.size}）</h3>
        <table><tr><th>ID</th><th>裁定</th><th>对象</th><th>状态</th></tr>$decRows</table></div>
        <p><a href='/history'>← 全部事件</a></p>"""
    }

    fun importPage(): String = """
    <div class='card'><h2>导入快照（删除本地库后恢复；身份、顺序、来源关系保持）</h2>
    <form method='post' action='/import-snapshot'>
    <p>粘贴由「导出档案」得到的 JSON：</p>
    <textarea name='snapshot' placeholder='snapshot json'></textarea>
    <p><button>校验并导入（失败则整体回滚）</button></p></form></div>
    <p><a href='/'>← 返回</a></p>"""

    fun message(ok: Boolean, text: String): String =
        "<div class='card'><p class='${if (ok) "ok" else "bad"}'>${esc(text)}</p><p><a href='/'>前往总览</a></p></div>"
}
