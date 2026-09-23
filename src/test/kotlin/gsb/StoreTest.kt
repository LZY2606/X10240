package gsb

import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.io.TempDir
import java.io.File

class StoreTest {
    @TempDir lateinit var tmp: File

    private fun newStore(name: String = "t.db") = Store(File(tmp, name).absolutePath, FixedClock())

    private fun submitBasics(s: Store) {
        s.submitInput("M1", Kinds.MEASUREMENT, "实验室报告 A", JsonFmt.parseToJsonElement(
            """{"labCode":"L1","c14AgeBP":2450,"sigmaBP":35,"unit":"14C yr BP","raw":"2450 ± 35 BP"}""").jsonObject)
        s.submitInput("curve-main", Kinds.CURVE_REF, "内置曲线", JsonFmt.parseToJsonElement(
            """{"curveId":"INTCAL-SYN","curveVersion":"2020"}""").jsonObject)
        s.submitInput("offset-none", Kinds.OFFSET, "无偏移约定", JsonFmt.parseToJsonElement(
            """{"kind":"normal","mean":0,"sigma":0,"unit":"14C yr"}""").jsonObject)
        s.submitInput("era-default", Kinds.ERA, "年代系统约定", JsonFmt.parseToJsonElement(
            """{"system":"BCE/CE-astronomical","basis":"cal BP (1950)"}""").jsonObject)
    }

    @Test
    fun `原始输入不可覆盖 修订以新版本和父版本表达`() {
        val s = newStore(); submitBasics(s)
        val v1 = s.headOf("M1")!!
        val v2 = s.submitInput("M1", Kinds.MEASUREMENT, "更正报告 A2", v1.payload)
        assertEquals(1, v1.version); assertEquals(2, v2.version)
        assertEquals(v1.id, v2.parentId, "父版本关系")
        assertEquals(v1, s.inputById(v1.id), "旧版本仍在")
        assertEquals(v2.id, s.headOf("M1")!!.id)
        s.close()
    }

    @Test
    fun `局部输入变化只使依赖它的结果过期 过期结果仍可查看`() {
        val s = newStore(); submitBasics(s)
        val wb = Workbench(s)
        val d1 = wb.calibrate("M1", "curve-main", "offset-none", "era-default")
        s.submitInput("offset-none", Kinds.OFFSET, "偏移修订", JsonFmt.parseToJsonElement(
            """{"kind":"normal","mean":10,"sigma":5,"unit":"14C yr"}""").jsonObject)
        assertTrue(d1.id in s.staleIds(), "依赖 offset-none 的派生过期")
        assertNotNull(s.derivedById(d1.id), "过期结果仍可查看")
        val staleEvents = s.events().filter { it.type == Ev.DERIVED_STALED }
        assertEquals(1, staleEvents.size, "只产生一个过期事件")
        assertEquals(d1.id, staleEvents[0].payload["derivedId"]!!.jsonPrimitive.content)
        s.close()
    }

    @Test
    fun `冲突解决与撤销都写入历史`() {
        val s = newStore(); submitBasics(s)
        val wb = Workbench(s)
        val d = wb.calibrate("M1", "curve-main", "offset-none", "era-default")
        wb.adjudicate(d.id, "accept", "采纳")
        assertEquals("accept", s.adjudicationState(d.id).first)
        wb.adjudicate(d.id, "reject", "反对")
        assertEquals("conflicted", s.adjudicationState(d.id).first)
        wb.resolveConflict(d.id, "undecided", "保留全部候选")
        assertEquals("undecided", s.adjudicationState(d.id).first)
        wb.undoResolution(d.id)
        assertEquals("conflicted", s.adjudicationState(d.id).first, "撤销后回到冲突态")
        val types = s.events().map { it.type }
        assertTrue(Ev.CONFLICT_RESOLVED in types && Ev.CONFLICT_UNDONE in types, "解决与撤销都在历史中")
        s.close()
    }

    @Test
    fun `导出 删除数据库 再导入 身份顺序来源关系保持`() {
        val s = newStore("a.db"); submitBasics(s)
        val wb = Workbench(s)
        val d = wb.calibrate("M1", "curve-main", "offset-none", "era-default")
        wb.adjudicate(d.id, "accept", "采纳")
        val exported = s.exportJson()
        val beforeEvents = s.events(); val beforeInputs = s.allInputs(); val beforeDerived = s.allDerived()
        val midSeq = beforeEvents[beforeEvents.size / 2].seq
        val oldHeads = s.stateAt(midSeq).heads.keys
        s.close()

        val s2 = newStore("b.db")
        val report = s2.importJson(exported)
        assertTrue(report.ok, "导入失败: ${report.errors}")
        assertEquals(beforeInputs.map { it.id }, s2.allInputs().map { it.id }, "输入身份与顺序")
        assertEquals(beforeInputs.map { it.source }, s2.allInputs().map { it.source }, "来源关系")
        assertEquals(beforeEvents.map { it.seq to it.type }, s2.events().map { it.seq to it.type }, "事件顺序")
        assertEquals(beforeDerived.map { it.id }, s2.allDerived().map { it.id }, "派生身份")
        assertEquals(beforeDerived.map { it.fingerprint }, s2.allDerived().map { it.fingerprint }, "参数指纹")
        // 回放一致性：同一事件序号的可见结论相同
        assertEquals(oldHeads, s2.stateAt(midSeq).heads.keys)
        s2.close()
    }

    @Test
    fun `导入格式错误逐条报告且不写入`() {
        val s = newStore()
        val r1 = s.importJson("{not json")
        assertFalse(r1.ok); assertTrue(r1.errors.isNotEmpty())
        val r2 = s.importJson("""{"kind":"alien","stream":"X","source":"s","payload":{}}""")
        assertFalse(r2.ok); assertTrue(r2.errors[0].contains("未知输入类型"))
        val r3 = s.importJson("""{"kind":"measurement","stream":"X","payload":{}}""")
        assertFalse(r3.ok); assertTrue(r3.errors[0].contains("source"))
        assertTrue(s.isEmpty(), "格式错误不写入任何记录")
        s.close()
    }

    @Test
    fun `完整恢复只允许空数据库`() {
        val s = newStore("a.db"); submitBasics(s)
        val exported = s.exportJson()
        val r = s.importJson(exported)
        assertFalse(r.ok); assertTrue(r.errors[0].contains("空数据库"))
        s.close()
    }

    @Test
    fun `回到旧事件恢复当时的依赖版本和可见结论`() {
        val s = newStore(); submitBasics(s)
        val wb = Workbench(s)
        val d1 = wb.calibrate("M1", "curve-main", "offset-none", "era-default")
        val seqAfterFirst = s.events().last().seq
        s.submitInput("curve-main", Kinds.CURVE_REF, "升版 2024", JsonFmt.parseToJsonElement(
            """{"curveId":"INTCAL-SYN","curveVersion":"2024"}""").jsonObject)
        val d2 = wb.calibrate("M1", "curve-main", "offset-none", "era-default")
        assertNotEquals(d1.id, d2.id)
        val snapOld = s.stateAt(seqAfterFirst)
        assertEquals("2020", snapOld.heads["curve-main"]!!.payload["curveVersion"]!!.jsonPrimitive.content,
            "旧事件时刻曲线版本为 2020")
        assertFalse(d1.id in snapOld.staleIds, "旧时刻 d1 尚未过期")
        assertTrue(d1.id in s.staleIds(), "当前 d1 已过期但未被改写")
        assertEquals(d1, s.derivedById(d1.id), "历史结果原样保留")
        s.close()
    }
}
