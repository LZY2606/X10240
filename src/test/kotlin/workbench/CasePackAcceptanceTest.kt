package workbench

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant

class CasePackAcceptanceTest {

    @TempDir
    lateinit var tmp: Path

    private fun runScript(): Pair<Store, List<ScenarioCheck>> {
        val store = Store("${tmp.toFile()}/archive.db",
            FixedClock(Instant.parse("2026-09-24T08:00:00Z")))
        val wb = Workbench(store)
        val (_, checks) = CasePack.runScript(wb)
        return store to checks
    }

    @Test
    fun `all acceptance scenarios pass - multimodal boundary reservoir bce-ce sequence upgrade parallel`() {
        val (store, checks) = runScript()
        checks.forEach { c ->
            assertTrue(c.ok, "${c.id} (${c.title}) failed: ${c.evidence}")
        }
        val required = listOf(
            "scn-multimodal", "scn-parallel-intervals", "scn-boundary", "scn-reservoir",
            "scn-bce-ce", "scn-sequence", "scn-curve-upgrade",
            "scn-local-invalidation", "scn-conflict-decisions"
        )
        val ids = checks.map { it.id }.toSet()
        assertTrue(ids.containsAll(required), "缺少验收场景: ${required - ids}")
        store.close()
    }

    @Test
    fun `archive is fully offline and every baseline entry has provenance and unit`() {
        val a = CasePack.load()
        assertTrue(a.baseline.none { it.provenance.isBlank() || it.unit.isBlank() })
        // 六类来源齐备：测量、曲线、偏移、年代系统、层序、排除
        val kinds = a.baseline.map { it.kind }.toSet()
        assertTrue(kinds.containsAll(SourceKind.entries))
        // 测量都有 age 与 sigma
        a.baseline.filter { it.kind == SourceKind.MEASUREMENT }.forEach {
            assertNotNull(it.payload["ageBp"]?.let { _ -> true })
            assertNotNull(it.payload["sigma"])
        }
    }

    @Test
    fun `derived values carry algorithm version fingerprint and direct sources`() {
        val (store, _) = runScript()
        store.state().derived.values.forEach { d ->
            assertTrue(d.algorithmVersion.isNotBlank())
            assertTrue(d.paramFingerprint.length >= 8)
            assertTrue(d.sourceIds.isNotEmpty())
        }
        store.close()
    }

    @Test
    fun `accepted and rejected results both remain readable in the archive`() {
        val (store, _) = runScript()
        val st = store.state()
        val verdicts = st.decisions.map { it.p.verdict }.toSet()
        assertTrue(verdicts.containsAll(listOf("ACCEPT", "REJECT")))
        // 被拒绝的决定仍在，被撤销的决定也在
        assertTrue(st.decisions.any { it.voidedBy != null })
        store.close()
    }
}
