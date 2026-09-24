package workbench

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable
data class ArchiveEntry(val key: String, val kind: SourceKind, val provenance: String,
                        val unit: String, val payload: JsonObject)

@Serializable
data class ScenarioSpec(val id: String, val title: String, val expectation: String)

@Serializable
data class Archive(
    val archiveId: String,
    val title: String,
    val version: String,
    val description: String,
    val baseline: List<ArchiveEntry>,
    val revisions: Map<String, ArchiveEntry> = emptyMap(),
    val scenarios: List<ScenarioSpec>
)

data class StepLog(val step: Int, val label: String, val producedId: String?, val note: String)

data class ScenarioCheck(val id: String, val title: String, val ok: Boolean, val evidence: String, val expectation: String)

object CasePack {
    private val json = Json { ignoreUnknownKeys = true }

    fun load(): Archive {
        val text = javaClass.getResourceAsStream("/casepack/archive.json")?.bufferedReader()?.use { it.readText() }
            ?: error("离线案例包 /casepack/archive.json 不在 classpath")
        return json.decodeFromString(Archive.serializer(), text)
    }

    /** 仅导入基线（初始导入步骤）。返回 key -> sourceId。 */
    fun installBaseline(wb: Workbench): Map<String, String> {
        val archive = load()
        val keys = LinkedHashMap<String, String>()
        for (e in archive.baseline) {
            keys[e.key] = wb.submitSource(e.kind, e.payload, e.provenance, e.unit, family = e.key)
        }
        return keys
    }

    /**
     * 完整剧本：基线 -> 校准 -> 储库修订 -> 层序冲突 -> 曲线升版 -> 局部测量修订 -> 冲突决定与撤销。
     * 每一步都产生真实事件；返回逐步日志与每场景校验。
     */
    fun runScript(wb: Workbench): Pair<List<StepLog>, List<ScenarioCheck>> {
        val logs = mutableListOf<StepLog>()
        var n = 0
        fun step(label: String, id: String? = null, note: String = ""): String? {
            n += 1; logs += StepLog(n, label, id, note); return id
        }
        val keys = installBaseline(wb).toMutableMap()
        step("import-baseline:${keys.size} sources", null, "测量/曲线/偏移/年代系统/层序/排除 各带来源")

        fun src(key: String) = keys.getValue(key)

        // 1 多峰
        val cal1 = wb.recordCalibration(src("m1"), src("curve1"), null)
        step("calibrate LAB-0001@intcal-shim-1", cal1, "曲线多峰 / 并列日历区间")
        // 2 BCE/CE
        val cal3 = wb.recordCalibration(src("m3"), src("curve1"), null)
        step("calibrate LAB-0003@intcal-shim-1", cal3, "BCE/CE 跨界")
        // 3 边界插值：故意请求超出曲线端点的网格
        val cal4b = wb.recordCalibration(src("m4"), src("curve1"), null, gridLo = 5500.0, gridHi = 6500.0)
        step("calibrate LAB-0004 grid 5500..6500", cal4b, "边界钳制诊断")
        // 4 储库偏移（高斯）
        val cal2a = wb.recordCalibration(src("m2"), src("curve1"), src("off1"))
        step("calibrate LAB-0002 with off1", cal2a, "储库偏移高斯分布")
        // 5 层序矛盾
        val cal5 = wb.recordCalibration(src("m5"), src("curve1"), null)
        val cal6 = wb.recordCalibration(src("m6"), src("curve1"), null)
        step("calibrate LAB-0005", cal5); step("calibrate LAB-0006", cal6)
        val seq1 = wb.recordSequenceCheck(src("seq1"), cal6, cal5)
        step("sequence-check LAB-0006 older-than LAB-0005", seq1, "冲突 -> 候选调整，测量不改写")
        // 6 储库偏移修订（显式样本混合）
        val off2 = wb.reviseSource(src("off1"), load().revisions.getValue("off2").payload,
            load().revisions.getValue("off2").provenance)
        keys["off2"] = off2
        step("revise reservoir offset off1 -> off2 (mixture samples)", off2, "cal2a 局部过期")
        val cal2b = wb.recordCalibration(src("m2"), src("curve1"), off2)
        step("calibrate LAB-0002 with off2", cal2b, "混合分布")
        // 7 曲线升版
        val curve2 = wb.reviseSource(src("curve1"), load().revisions.getValue("curve2").payload,
            load().revisions.getValue("curve2").provenance)
        keys["curve2"] = curve2
        step("curve upgrade intcal-shim-1 -> intcal-shim-2", curve2, "所有 curve1 派生过期但保留")
        val cal1u = wb.recordCalibration(src("m1"), curve2, null)
        step("recalibrate LAB-0001@intcal-shim-2", cal1u, "新指纹与新区间")
        val cal2d = wb.recordCalibration(src("m2"), curve2, off2)
        step("calibrate LAB-0002+off2@intcal-shim-2", cal2d, "升版后的当前结果")
        // 8 局部改动：测量勘误
        val m2rev = wb.reviseSource(src("m2"), load().revisions.getValue("m2rev").payload,
            load().revisions.getValue("m2rev").provenance)
        keys["m2rev"] = m2rev
        step("revise LAB-0002 sigma 35->28", m2rev, "只使 LAB-0002 自己的派生过期")
        val cal2c = wb.recordCalibration(m2rev, curve2, off2)
        step("recalibrate LAB-0002(errata)+off2@intcal-shim-2", cal2c)
        // 9 互相冲突的人工决定，随后撤销其一
        val decAccept = wb.decide("derived", cal1u, "ACCEPT", "采用曲线升版后的年代候选", "reviewer-chen", labCode = "LAB-0001")
        step("decision ACCEPT $cal1u", decAccept)
        val decReject = wb.decide("derived", cal1u, "REJECT", "对升版曲线存疑，坚持旧区间", "reviewer-li", labCode = "LAB-0001")
        step("decision REJECT $cal1u (conflicting)", decReject, "状态 CONFLICTED")
        wb.voidDecision(decReject, "复核会议 2026-09-18 撤销该 REJECT")
        step("void REJECT decision", decReject, "冲突消解，接受决定保留")

        val stNow = wb.store.state()
        // 过期事件紧跟在源修订事件之后写入，检查点取修订事件之后一条
        val seqAtOffset = stNow.events.last { it.id == off2 }.seq + 1
        val seqAtCurve2 = stNow.events.last { it.id == curve2 }.seq + 1
        val seqAtM2rev = stNow.events.last { it.id == m2rev }.seq + 1
        val checks = evaluate(wb, keys, cal1, cal3, cal4b, cal2a, cal2b, seq1, cal5, cal6,
            curve2, cal1u, m2rev, cal2d, cal2c, decAccept, decReject,
            seqAtOffset, seqAtCurve2, seqAtM2rev)
        return logs to checks
    }

    @Suppress("LongParameterList")
    private fun evaluate(
        wb: Workbench, keys: Map<String, String>,
        cal1: String, cal3: String, cal4b: String, cal2a: String, cal2b: String,
        seq1: String, cal5: String, cal6: String,
        curve2Id: String, cal1u: String, m2revId: String, cal2d: String, cal2c: String,
        decAccept: String, decReject: String,
        seqAtOffset: Long, seqAtCurve2: Long, seqAtM2rev: Long
    ): List<ScenarioCheck> {
        val st = wb.store.state()
        fun cal(id: String) = wb.json.decodeFromString(CalibrationResult.serializer(), st.derived.getValue(id).payload)
        fun seq(id: String) = wb.json.decodeFromString(SequenceCheckResult.serializer(), st.derived.getValue(id).payload)
        fun ch(spec: ScenarioSpec, ok: Boolean, evidence: String) =
            ScenarioCheck(spec.id, spec.title, ok, evidence, spec.expectation)
        val specs = load().scenarios.associateBy { it.id }
        val out = mutableListOf<ScenarioCheck>()

        val r1 = cal(cal1)
        out += ch(specs.getValue("scn-multimodal"),
            r1.peaks.size >= 2 && r1.intervals68.size >= 2,
            "${r1.peaks.size} peaks, ${r1.intervals68.size} parallel 68% intervals: ${r1.intervals68.joinToString { "${it.loLabel}~${it.hiLabel}(${("%.3f".format(it.probability))})" }}; no mean-year collapse")

        out += ch(specs.getValue("scn-parallel-intervals"),
            r1.intervals68.size >= 2 && r1.intervals68.zipWithNext().all { (a, b) -> a.hiCalBp < b.loCalBp },
            r1.intervals68.joinToString(" | ") { "id-less interval [${it.loLabel}, ${it.hiLabel}] P=${"%.4f".format(it.probability)}" })

        val r4 = cal(cal4b)
        out += ch(specs.getValue("scn-boundary"),
            r4.diagnostics.any { it.startsWith("boundary-clamp") } && r4.gridHi == 6000.0,
            "diagnostics=${r4.diagnostics}; grid held at ${r4.gridLo}..${r4.gridHi}, interpolation clamps at curve endpoint")

        val r2a = cal(cal2a); val r2b = cal(cal2b)
        val stAtOffset = Store.fold(st.events.filter { it.seq <= seqAtOffset })
        out += ch(specs.getValue("scn-reservoir"),
            r2b.diagnostics.any { it.contains("mixture") } &&
                stAtOffset.derived.getValue(cal2a).status == DerivedStatus.STALE &&
                stAtOffset.derived.getValue(cal5).status == DerivedStatus.CURRENT,
            "off2 mixture on $cal2b; at offset checkpoint $cal2a stale=${stAtOffset.derived.getValue(cal2a).staleReason}; unrelated $cal5 stays current")

        val r3 = cal(cal3)
        val cross = r3.intervals68.any { it.bceCeCross } || r3.intervals95.any { it.bceCeCross }
        out += ch(specs.getValue("scn-bce-ce"), cross,
            "95% intervals=${r3.intervals95.joinToString { "${it.loLabel}~${it.hiLabel}(cross=${it.bceCeCross})" }}")

        val s1 = seq(seq1)
        val m5unchanged = wb.parseMeasurement(st.sources.getValue(keys.getValue("m5")))?.ageBp == 2400.0
        val m6unchanged = wb.parseMeasurement(st.sources.getValue(keys.getValue("m6")))?.ageBp == 2300.0
        out += ch(specs.getValue("scn-sequence"),
            s1.conflict && s1.candidates.size >= 3 && m5unchanged && m6unchanged,
            "P(before older)=${"%.4f".format(s1.probBeforeOlderThanAfter)}; ${s1.candidates.size} candidates kept; measurements unchanged=true")

        val stAtCurve2 = Store.fold(st.events.filter { it.seq <= seqAtCurve2 })
        val d1old = stAtCurve2.derived.getValue(cal1)
        val r1u = cal(cal1u)
        out += ch(specs.getValue("scn-curve-upgrade"),
            d1old.status == DerivedStatus.STALE && r1u.paramFingerprint != r1.paramFingerprint &&
                r1u.intervals68 != r1.intervals68,
            "old fp=${r1.paramFingerprint} STALE(${d1old.staleReason}) at curve2 checkpoint; new fp=${r1u.paramFingerprint} on $curve2Id; intervals changed")

        val stAtM2 = Store.fold(st.events.filter { it.seq <= seqAtM2rev })
        val r2dRec = stAtM2.derived.getValue(cal2d)
        val cal1uRec = stAtM2.derived.getValue(cal1u)
        val oldM2 = keys.getValue("m2")
        out += ch(specs.getValue("scn-local-invalidation"),
            r2dRec.status == DerivedStatus.STALE &&
                r2dRec.staleReason?.contains(oldM2) == true &&
                cal1uRec.status == DerivedStatus.CURRENT,
            "m2 errata $m2revId only invalidates its own dependents ($cal2d stale via $oldM2); $cal1u stays current")

        val conflict = st.conflicts()[cal1u]?.size == 2
        // 历史态回放：在 REJECT 事件后、VOID 事件前应为冲突；当前态已撤销
        val rejectSeq = st.events.first { it.id == decReject }.seq
        val stAtReject = Store.fold(st.events.filter { it.seq <= rejectSeq })
        val historyConflict = Store.fold(stAtReject.events).conflicts()[cal1u]?.size == 2
        val activeNow = st.activeDecisions().map { it.p.id }.toSet()
        out += ch(specs.getValue("scn-conflict-decisions"),
            conflict.not() && historyConflict && activeNow == setOf(decAccept),
            "at reject-event: 2 conflicting decisions (CONFLICTED); after void: only $decAccept active; history replay preserves conflict")

        return out
    }
}
