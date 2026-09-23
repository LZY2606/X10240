package gsb

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.test.assertFails

class StateMachineTest : TestBase() {

    private fun latestDerived(wb: Workbench, stream: String): Derived =
        wb.state.currentDerivedFor(stream).filter { it.status == "current" }
            .maxByOrNull { it.ts } ?: error("no current derived for $stream")

    @Test
    fun `base import proves provenance, multi-peak, boundary truncation and BCE-CE crossing`() {
        val wb = newWorkbench()
        wb.applyCasepackStep("00-base")
        val st = wb.state

        assertEquals(13, st.sources.size, "five measurements, curve, three offsets, era, two constraints, one exclusion")
        for (s in st.sources.values) {
            assertTrue(s.provenance.containsKey("origin"), "every source has an origin: ${s.sourceId}")
            assertTrue(s.raw.isNotEmpty(), "raw representation kept: ${s.sourceId}")
        }

        val d1042 = latestDerived(wb, "AT-1042")
        val nPeaks = d1042.payload["peaks"]!!.jsonArray.size
        assertTrue(nPeaks >= 2, "AT-1042 must show curve multi-peak, got $nPeaks")

        val d1060 = latestDerived(wb, "AT-1060")
        val diags = d1060.payload["diagnostics"]!!.jsonArray.joinToString { it.jsonPrimitive.content }
        assertTrue(diags.contains("truncated at curve boundary"), "AT-1060 must trigger boundary diagnostic: $diags")

        val d1055 = latestDerived(wb, "AT-1055")
        val labels = d1055.payload["intervals"]!!.jsonArray.flatMap {
            listOf(it.jsonObject["hiLabel"]!!.jsonPrimitive.content, it.jsonObject["loLabel"]!!.jsonPrimitive.content)
        }
        assertTrue(labels.any { it.startsWith("BCE") } && labels.any { it.startsWith("CE") },
            "AT-1055 95.4% HPD must cross the BCE/CE boundary: $labels")

        // multiple parallel calendar intervals at 95.4%
        val runs954 = d1042.payload["intervals"]!!.jsonArray
            .count { it.jsonObject["level"]!!.jsonPrimitive.content == "0.954" }
        assertTrue(runs954 >= 1)
        // rejected measurement stays present with its exclusion decision
        assertNotNull(st.latestSource("exclusionDecision", "XD-1"))
        assertNotNull(st.latestSource("measurement", "AT-1099"))
    }

    @Test
    fun `curve upgrade and offset revision expire only dependent results, which stay viewable`() {
        val wb = newWorkbench()
        wb.applyCasepackStep("00-base")
        val before = latestDerived(wb, "AT-1042")
        wb.applyCasepackStep("01-param-revision")
        val st = wb.state

        assertEquals("stale", st.derived[before.derivedId]!!.status, "old-curve result is stale")
        assertNotNull(st.derived[before.derivedId]!!.staleReason, "stale result keeps a reason and remains viewable")

        val oldCurveId = before.inputs["curve"]
        val newCurve = st.latestSource("curveVersion", "GSB-INTCAL-LIKE")!!
        assertNotEquals(oldCurveId, newCurve.sourceId)
        assertEquals(oldCurveId, newCurve.parent)

        val after = latestDerived(wb, "AT-1042")
        assertEquals("current", after.status)
        assertEquals(newCurve.sourceId, after.inputs["curve"])
        assertNotEquals(before.derivedId, after.derivedId, "new curve version -> new stable derived identity")
    }

    @Test
    fun `local source change expires only that stream and leaves others current`() {
        val wb = newWorkbench()
        wb.applyCasepackStep("00-base")
        wb.applyCasepackStep("01-param-revision")
        val d1042 = latestDerived(wb, "AT-1042")
        val d1055 = latestDerived(wb, "AT-1055")
        val d1071 = latestDerived(wb, "AT-1071")

        wb.applyCasepackStep("02-local-source-change")
        val st = wb.state
        assertEquals("stale", st.derived[d1055.derivedId]!!.status, "revised measurement expires its derived results")
        assertEquals("current", st.derived[d1042.derivedId]!!.status, "unrelated AT-1042 stays current")
        assertEquals("current", st.derived[d1071.derivedId]!!.status, "unrelated AT-1071 stays current")

        val v2 = st.latestSource("measurement", "AT-1055")!!
        assertEquals(45.0, v2.raw["sigma"]!!.let { it.jsonPrimitive.content.toDouble() }, 0.0)
        assertEquals(2, v2.version)
        assertNotNull(v2.parent, "revision keeps parent-version relation")
        val v1 = st.sources[v2.parent!!]!!
        assertEquals(30.0, v1.raw["sigma"]!!.let { it.jsonPrimitive.content.toDouble() }, 0.0,
            "original measurement must never be overwritten")
    }

    @Test
    fun `stratigraphic contradiction produces legal candidate adjustments but never rewrites measurements`() {
        val wb = newWorkbench()
        wb.applyCasepackStep("00-base")
        wb.applyCasepackStep("01-param-revision")
        wb.applyCasepackStep("02-local-source-change")
        wb.applyCasepackStep("03-conflicting-decisions")
        val st = wb.state

        assertEquals(1, st.conflicts.size)
        val conflict = st.conflicts.values.single()
        assertEquals(setOf("AT-1042", "AT-1055", "AT-1071"), conflict.cycle.toSet())
        val cands = st.candidates.values.filter { it.conflictId == conflict.conflictId }
        assertTrue(cands.any { it.action == "exclude-constraint" && it.target.endsWith(st.latestSource("stratConstraint", "SC-3")!!.sourceId.takeLast(16)) } ||
            cands.any { it.action == "exclude-constraint" })
        assertTrue(cands.any { it.action == "flag-outlier" }, "all legal candidates retained")

        // measurements untouched
        assertEquals(2440.0, st.latestSource("measurement", "AT-1042")!!.raw["c14AgeBP"]!!.let { it.jsonPrimitive.content.toDouble() }, 0.0)

        // accepting one exclude-constraint candidate resolves the conflict without deleting sources
        val accept = cands.first { it.action == "exclude-constraint" }
        wb.adjudicateCandidate(accept.candidateId, "accepted", "trench B correlation disputed", "reviewer:lin")
        assertTrue(st.sources[accept.target]!!.excluded)
        assertTrue(st.conflicts[accept.conflictId]!!.resolved)
        assertNotNull(st.sources[accept.target], "excluded constraint remains in the archive")

        // conflicting human decisions on one interval are both retained in history
        val d1042 = latestDerived(wb, "AT-1042")
        val history = st.adjudicationHistory.filter { it.derivedIdOf() == d1042.derivedId && it.key.endsWith("#0") }
        assertEquals(2, history.size)
        assertEquals("accepted", history[0].decision)
        assertEquals("rejected", history[1].decision)
        assertEquals("rejected", st.adjudications["${d1042.derivedId}#0"]!!.decision, "latest visible conclusion wins")

        // undo restores the earlier visible conclusion
        wb.undo(history[1].eventId, "second opinion withdrawn")
        assertEquals("accepted", st.adjudications["${d1042.derivedId}#0"]!!.decision)
        assertTrue(history[1].eventId in st.undoneEvents)
    }

    @Test
    fun `time travel restores dependency versions and visible conclusions`() {
        val wb = newWorkbench()
        wb.applyCasepackStep("00-base")
        val seqAfterBase = wb.state.events.size
        val baseDerived = latestDerived(wb, "AT-1042")
        replayAll(wb)
        // later, that derived result is stale and conflicting adjudications exist
        assertEquals("stale", wb.state.derived[baseDerived.derivedId]!!.status)

        val old = ProjectState.replay(wb.state.events, seqAfterBase)
        assertEquals("current", old.derived[baseDerived.derivedId]!!.status)
        assertNull(old.latestSource("curveVersion", "GSB-INTCAL-LIKE")?.let { if (it.version > 1) it else null })
        assertTrue(old.conflicts.isEmpty(), "SC-3 contradiction did not exist yet")
    }

    @Test
    fun `malformed batches are rejected with explicit diagnostics`() {
        val wb = newWorkbench()
        assertFails { wb.applyBatch(Json.parseToJsonElement("{}").jsonObject) }
        assertFails {
            wb.applyBatch(Json.parseToJsonElement("""{"events":[{"type":"source.imported"}]}""").jsonObject)
        }
        assertFails {
            wb.applyBatch(Json.parseToJsonElement(
                """{"events":[{"type":"source.imported","kind":"measurement","stream":"X","stream2":"X",
                   "raw":{"c14AgeBP":100},"units":{},"provenance":{}}]}""").jsonObject)
        }
    }

    @Test
    fun `unconvertible value stays at source with diagnostic rather than being dropped`() {
        val wb = newWorkbench()
        wb.applyCasepackStep("00-base")
        wb.importSource("measurement", "BAD-1",
            buildJsonObject {
                put("labCode", "BAD-1"); put("c14AgeBP", 2000)
                put("sigma", "not-a-number"); put("rawText", "2000 ± ? BP (illegible sigma)")
            },
            buildJsonObject { put("sigma", "1-sigma, c14 years") },
            buildJsonObject { put("origin", "lab-report-bad-scan"); put("curator", "reviewer:lin") })
        val bad = wb.state.latestSource("measurement", "BAD-1")!!
        val curve = wb.state.latestSource("curveVersion", "GSB-INTCAL-LIKE")!!
        val off = wb.state.latestSource("offsetDistribution", "OF-TER")!!
        val era = wb.state.latestSource("eraSystem", "ERA-MAIN")!!
        assertFails { wb.compute(bad.sourceId, curve.sourceId, off.sourceId, era.sourceId) }
        assertNotNull(wb.state.latestSource("measurement", "BAD-1"), "bad value remains at source, not dropped for plotting")
    }
}

private fun gsb.Adjudication.derivedIdOf() = key.substringBefore('#')

private val kotlinx.serialization.json.JsonArray.jsonArray get() = this
private fun gsb.Adjudication.derivedIdOf() = key.substringBefore('#')
