package gsb

import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** 验收：曲线多峰、边界插值、储库偏移分布、BCE/CE 跨界、层序矛盾、曲线升版、并列日历区间。 */
class ScenarioAcceptanceTest {
    @TempDir lateinit var tmp: File

    private fun loadCasePack(): Pair<Store, Workbench> {
        val s = Store(File(tmp, "case.db").absolutePath, FixedClock())
        val report = s.importJson(CasePack.loadBundled())
        assertTrue(report.ok, "案例包载入失败: ${report.errors}")
        return s to Workbench(s)
    }

    private fun calibOf(s: Store, wb: Workbench, stream: String): CalibResult =
        CalibResult.fromJson(wb.latestCalibrationFor(stream)!!.result["calibration"]!!.jsonObject)

    @Test
    fun `场景1 曲线多峰不压成平均年`() {
        val (s, wb) = loadCasePack()
        val r = calibOf(s, wb, "M1")
        assertTrue(r.peaks.size >= 2, "多峰 ${r.peaks}")
        assertTrue(r.intervals95.size >= 2, "并列区间 ${r.intervals95.size}")
        s.close()
    }

    @Test
    fun `场景2 边界插值 现代端截断且可重现`() {
        val (s, wb) = loadCasePack()
        val r = calibOf(s, wb, "M5")
        assertTrue(r.truncatedAtMin, "M5 在 0 calBP 现代端截断")
        val again = wb.calibrate("M5", "curve-main", "offset-none", "era-default")
        assertEquals(wb.latestCalibrationFor("M5")!!.id, again.id, "同参数指纹去重 → 可重现")
        s.close()
    }

    @Test
    fun `场景3 储库偏移分布修订只影响海洋样品`() {
        val (s, wb) = loadCasePack()
        val m3cals = s.allDerived().filter { it.kind == "calibration" && it.deps.any { d -> d.streamId == "M3" } }
        assertTrue(m3cals.size >= 3, "M3 至少有 初始/偏移修订/曲线升版 三版校准")
        val stale = s.staleIds()
        // 偏移修订（normal→mixture）只使依赖 offset-marine 的 M3 校准过期：陆生样品校准都依赖 offset-none
        val m3Normal = m3cals.filter { it.result["offset"]!!.jsonObject["kind"]!!.jsonPrimitive.content == "normal" }
        assertTrue(m3Normal.isNotEmpty() && m3Normal.all { it.id in stale }, "正态偏移版 M3 校准已过期")
        val cur = wb.latestCalibrationFor("M3")!!
        assertEquals("mixture", cur.result["offset"]!!.jsonObject["kind"]!!.jsonPrimitive.content, "当前为混合分布")
        // 陆生样品的校准不受偏移修订影响
        val m4cals = s.allDerived().filter { it.kind == "calibration" && it.deps.any { d -> d.streamId == "M4" } }
        assertTrue(m4cals.none { d ->
            val becauseOffset = s.events().any { ev ->
                ev.type == Ev.DERIVED_STALED && ev.payload["derivedId"]?.jsonPrimitive?.content == d.id &&
                    ev.payload["streamId"]?.jsonPrimitive?.content == "offset-marine" }
            becauseOffset
        }, "M4 校准从未因 offset-marine 修订而过期")
        s.close()
    }

    @Test
    fun `场景4 BCE CE 跨界`() {
        val (s, wb) = loadCasePack()
        val r = calibOf(s, wb, "M4")
        assertTrue(r.intervals95.any { Era.crossesEraBoundary(it.loBp, it.hiBp) },
            "M4 区间跨界: ${r.intervals95}")
        s.close()
    }

    @Test
    fun `场景5 层序矛盾产生候选调整而不改写测量`() {
        val (s, wb) = loadCasePack()
        val ass = wb.latestStratAssessmentFor("strat-1")!!
        val a = StratAssessment.fromJson(ass.result)
        assertEquals("conflict", a.status, "M1/M2 层序矛盾")
        assertTrue(a.candidates.size >= 2, "全部合法候选保留: ${a.candidates.map { it.id }}")
        val m1 = s.headOf("M1")!!
        assertEquals(2450.0, m1.payload["c14AgeBP"]!!.jsonPrimitive.double, "测量值不被改写")
        assertEquals("2450 ± 35 BP", m1.payload["raw"]!!.jsonPrimitive.content, "原始表示保留")
        // 互相冲突的人工决定 + 无法唯一决定
        assertEquals("undecided", s.adjudicationState(ass.id).first)
        s.close()
    }

    @Test
    fun `场景6 曲线升版 旧结果过期但可查看`() {
        val (s, wb) = loadCasePack()
        val m1cals = s.allDerived().filter { it.kind == "calibration" && it.deps.any { d -> d.streamId == "M1" } }
        assertEquals(2, m1cals.size, "升版前后两版")
        val stale = s.staleIds()
        val old = m1cals.first { it.id in stale }
        assertEquals("2020", old.result["curveVersion"]!!.jsonPrimitive.content)
        val cur = wb.latestCalibrationFor("M1")!!
        assertEquals("2024", cur.result["curveVersion"]!!.jsonPrimitive.content)
        assertNotNull(s.derivedById(old.id), "旧结果仍可查看")
        s.close()
    }

    @Test
    fun `场景7 并列日历区间保留且不合并`() {
        val (s, wb) = loadCasePack()
        val r = calibOf(s, wb, "M1")
        val sorted = r.intervals95.sortedBy { it.loBp }
        assertTrue(sorted.size >= 2)
        for (i in 1 until sorted.size)
            assertTrue(sorted[i].loBp - sorted[i - 1].hiBp > 0, "区间间存在间隔，未合并")
        // 每个区间都有可展开的积分证据
        for (iv in r.intervals95)
            assertTrue(r.evidence.any { it.intervalId == iv.id && it.rows.isNotEmpty() }, "区间 ${iv.id} 有积分证据")
        s.close()
    }

    @Test
    fun `叙事完整性 从最终状态反查材料 规则版本与决定`() {
        val (s, wb) = loadCasePack()
        // 每个派生都能反查：直接来源输入、算法版本、参数指纹
        for (d in s.allDerived()) {
            assertTrue(d.algoVersion.isNotBlank())
            assertTrue(d.fingerprint.isNotBlank())
            assertTrue(d.deps.isNotEmpty())
            for (dep in d.deps) assertNotNull(s.inputById(dep.inputId), "来源可反查 ${dep.inputId}")
        }
        // 无法转换的值留在原位置并附诊断
        val m9 = s.headOf("M9")!!
        assertNotNull(m9.payload["diagnostic"], "M9 附诊断")
        assertEquals("not reported", m9.payload["raw"]!!.jsonPrimitive.content, "原始表示未丢")
        // 排除决定有来源
        val excl = s.headOf("excl-M9")!!
        assertTrue(excl.source.isNotBlank())
        // 历史回放：曲线升版前 M1 的可见结论是 2020 版
        val upgradeEvent = s.events().first { it.type == Ev.INPUT_SUBMITTED &&
            it.payload["streamId"]?.jsonPrimitive?.content == "curve-main" &&
            it.payload["version"]?.jsonPrimitive?.int == 2 }
        val before = s.stateAt(upgradeEvent.seq - 1)
        assertEquals("2020", before.heads["curve-main"]!!.payload["curveVersion"]!!.jsonPrimitive.content)
        val after = s.stateAt(upgradeEvent.seq)
        assertEquals("2024", after.heads["curve-main"]!!.payload["curveVersion"]!!.jsonPrimitive.content)
        s.close()
    }
}
