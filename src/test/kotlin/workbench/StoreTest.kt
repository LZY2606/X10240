package workbench

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import java.time.Instant

class StoreTest {

    private val clock = FixedClock(Instant.parse("2026-09-24T00:00:00Z"))
    private fun measurementPayload(lab: String, age: Double, sigma: Double) =
        kotlinx.serialization.json.buildJsonObject {
            put("labCode", kotlinx.serialization.json.JsonPrimitive(lab))
            put("ageBp", kotlinx.serialization.json.JsonPrimitive(age))
            put("sigma", kotlinx.serialization.json.JsonPrimitive(sigma))
            put("material", kotlinx.serialization.json.JsonPrimitive("charcoal"))
        }

    @TempDir
    lateinit var tmp: Path

    private fun newStore() = Store("${tmp.toFile()}/wb-${System.nanoTime()}.db", clock)

    @Test
    fun `submitted sources are immutable and revisions are new versions with parents`() {
        newStore().use { st ->
            val wb = Workbench(st)
            val v1 = wb.submitSource(SourceKind.MEASUREMENT, measurementPayload("L1", 2400.0, 30.0),
                "report-1", "BP", family = "labL1")
            val v2 = wb.reviseSource(v1, measurementPayload("L1", 2400.0, 28.0), "report-1 errata")
            val state = st.state()
            assertEquals(1, state.sources.getValue(v1).version)
            assertNull(state.sources.getValue(v1).parentId)
            assertEquals(2, state.sources.getValue(v2).version)
            assertEquals(v1, state.sources.getValue(v2).parentId)
            // 原始表示未被覆盖
            assertTrue(state.sources.getValue(v1).payload.contains("30.0"))
            assertEquals(v2, state.latestInFamily("labL1")?.id)
        }
    }

    @Test
    fun `local source change invalidates only dependent derived results`() {
        newStore().use { st ->
            val wb = Workbench(st)
            val archive = CasePack.load()
            val keys = mutableMapOf<String, String>()
            for (e in archive.baseline) keys[e.key] = wb.submitSource(e.kind, e.payload, e.provenance, e.unit, family = e.key)
            val calA = wb.recordCalibration(keys.getValue("m1"), keys.getValue("curve1"), null)
            val calB = wb.recordCalibration(keys.getValue("m2"), keys.getValue("curve1"), keys.getValue("off1"))
            // 改 m2（勘误）：只 calB 过期
            wb.reviseSource(keys.getValue("m2"), archive.revisions.getValue("m2rev").payload, "errata")
            val state = st.state()
            assertEquals(DerivedStatus.STALE, state.derived.getValue(calB).status)
            assertEquals(DerivedStatus.CURRENT, state.derived.getValue(calA).status)
            // 过期结果仍可查看且保留原指纹
            assertTrue(state.derived.getValue(calB).staleReason!!.contains(keys.getValue("m2")))
        }
    }

    @Test
    fun `conflicting decisions and undo are both recorded in history`() {
        newStore().use { st ->
            val wb = Workbench(st)
            val keys = mutableMapOf<String, String>()
            for (e in CasePack.load().baseline)
                keys[e.key] = wb.submitSource(e.kind, e.payload, e.provenance, e.unit, family = e.key)
            val cal = wb.recordCalibration(keys.getValue("m1"), keys.getValue("curve1"), null)
            val d1 = wb.decide("derived", cal, "ACCEPT", "ok", "chen")
            val d2 = wb.decide("derived", cal, "REJECT", "no", "li")
            assertEquals(2, st.state().conflicts().values.first().size)
            wb.voidDecision(d2, "meeting")
            val now = st.state()
            assertTrue(now.conflicts().isEmpty())
            assertTrue(now.activeDecisions().single().p.id == d1)
            // 历史前缀折叠：在 REJECT 时刻冲突存在
            val rejectSeq = now.events.first { it.id == d2 }.seq
            val then = Store.fold(now.events.filter { it.seq <= rejectSeq })
            assertEquals(2, then.conflicts().values.first().size)
        }
    }

    @Test
    fun `export delete import preserves identity order and provenance links`() {
        val dbPath = "${tmp.toFile()}/roundtrip.db"
        val dump: String = Store(dbPath, clock).use { st ->
            val wb = Workbench(st)
            val keys = mutableMapOf<String, String>()
            for (e in CasePack.load().baseline)
                keys[e.key] = wb.submitSource(e.kind, e.payload, e.provenance, e.unit, family = e.key)
            wb.recordCalibration(keys.getValue("m1"), keys.getValue("curve1"), null)
            st.snapshotJson()
        }
        File(dbPath).delete()
        Store(dbPath, clock).use { st2 ->
            val n = st2.restoreSnapshot(dump)
            val state = st2.state()
            assertTrue(n >= 12)
            assertEquals(n, state.events.size)
            assertEquals(state.events.sortedBy { it.seq }, state.events)
            // 稳定身份与版本父子关系保持
            val anyRev = state.sources.values.first()
            assertNotNull(anyRev.unit)
            assertTrue(state.derived.size == 1)
            assertTrue(state.derived.values.single().sourceIds.all { it in state.sources })
        }
    }

    @Test
    fun `import reports explicit format errors and keeps database untouched`() {
        newStore().use { st ->
            val wb = Workbench(st)
            val bad = """[{"kind":"MEASUREMENT","unit":"BP","payload":{"labCode":"X"}}]"""
            val ex = assertThrows(ImportException::class.java) { wb.importEntries(bad) }
            assertTrue(ex.errors.any { it.contains("provenance") })
            assertEquals(0, st.eventCount())
        }
    }

    @Test
    fun `unconvertible payload is stored with diagnostics and derivation is marked unknown`() {
        newStore().use { st ->
            val wb = Workbench(st)
            val curve = CasePack.load().baseline.first { it.kind == SourceKind.CURVE }
            val cid = wb.submitSource(curve.kind, curve.payload, curve.provenance, curve.unit, family = "c")
            val bad = kotlinx.serialization.json.buildJsonObject {
                put("labCode", kotlinx.serialization.json.JsonPrimitive("BAD"))
                put("ageBp", kotlinx.serialization.json.JsonPrimitive("not-a-number"))
                put("sigma", kotlinx.serialization.json.JsonPrimitive(30.0))
            }
            val mid = wb.submitSource(SourceKind.MEASUREMENT, bad, "lab", "BP", family = "m")
            assertTrue(st.state().sourceDiag.getValue(mid).any { it.contains("unconvertible") })
            val derId = wb.recordCalibration(mid, cid, null)
            val rec = st.state().derived.getValue(derId)
            assertTrue(rec.payload.contains("\"unknown\":true"))
        }
    }

    @Test
    fun `history prefix restores dependency versions without overwriting with latest`() {
        newStore().use { st ->
            val wb = Workbench(st)
            val archive = CasePack.load()
            val keys = mutableMapOf<String, String>()
            for (e in archive.baseline) keys[e.key] = wb.submitSource(e.kind, e.payload, e.provenance, e.unit, family = e.key)
            val cal = wb.recordCalibration(keys.getValue("m1"), keys.getValue("curve1"), null)
            val before = st.state().events.size
            wb.reviseSource(keys.getValue("curve1"), archive.revisions.getValue("curve2").payload, "upgrade")
            val atBefore = Store.fold(st.loadEvents().filter { it.seq <= before })
            // 旧时刻：曲线只有 v1，cal 仍为 CURRENT
            assertEquals(1, atBefore.sources.values.filter { it.kind == SourceKind.CURVE }.size)
            assertEquals(DerivedStatus.CURRENT, atBefore.derived.getValue(cal).status)
            // 当前时刻：cal 已过期
            assertEquals(DerivedStatus.STALE, st.state().derived.getValue(cal).status)
        }
    }
}

class AdjustDecisionTest {
    @TempDir
    lateinit var tmp: Path

    @Test
    fun `ADJUST on derived result revises a direct source and never rewrites measurement`() {
        Store("${tmp.toFile()}/adj.db",
            FixedClock(Instant.parse("2026-09-24T08:00:00Z"))).use { st ->
            val wb = Workbench(st)
            val keys = mutableMapOf<String, String>()
            for (e in CasePack.load().baseline)
                keys[e.key] = wb.submitSource(e.kind, e.payload, e.provenance, e.unit, family = e.key)
            val cal = wb.recordCalibration(keys.getValue("m1"), keys.getValue("curve1"), null)
            val newOffset = kotlinx.serialization.json.buildJsonObject {
                put("mean", kotlinx.serialization.json.JsonPrimitive(400.0))
                put("sigma", kotlinx.serialization.json.JsonPrimitive(50.0))
            }
            // 对派生结果 ADJUST 时必须指定直接来源
            assertThrows(ImportException::class.java) {
                wb.decide("derived", cal, "ADJUST", "change offset", "reviewer", adjustPayload = newOffset)
            }
            // 对测量 ADJUST 但参数非法时不会静默写坏数据（由 validate 诊断 + 新版本记录）
            val decId = wb.decide("source", keys.getValue("m1"), "ADJUST", "refine sigma", "reviewer",
                labCode = "LAB-0001", adjustPayload = kotlinx.serialization.json.buildJsonObject {
                    put("labCode", kotlinx.serialization.json.JsonPrimitive("LAB-0001"))
                    put("ageBp", kotlinx.serialization.json.JsonPrimitive(2850.0))
                    put("sigma", kotlinx.serialization.json.JsonPrimitive(26.0))
                    put("material", kotlinx.serialization.json.JsonPrimitive("charcoal"))
                })
            val state = st.state()
            val dec = state.decisions.first { it.p.id == decId }
            assertNotNull(dec.p.newSourceId)
            // 原测量版本仍然存在且数值未变
            val v1 = state.sources.getValue(keys.getValue("m1"))
            assertTrue(v1.payload.contains("30.0"))
            assertEquals(2, state.sources.getValue(dec.p.newSourceId!!).version)
        }
    }
}
