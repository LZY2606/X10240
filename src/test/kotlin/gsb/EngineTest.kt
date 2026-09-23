package gsb

import kotlinx.serialization.decodeFromString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class EngineTest {

    private fun baselineEngine(): Engine {
        val (e) = newEngine()
        CasePack.applyBaseline(e)
        CasePack.calibrateAll(e)
        return e
    }

    @Test
    fun `raw inputs are immutable and revisions create parent-linked versions`() {
        val e = baselineEngine()
        val m1 = e.store.latestSources()["KIA-55001"]!!
        assertEquals(1, m1.version)
        e.submitSource(SourceKind.MEASUREMENT, "KIA-55001", m1.unit, "erratum", m1.raw, "reviewer")
        val m1b = e.store.latestSources()["KIA-55001"]!!
        assertEquals(2, m1b.version)
        assertEquals(m1.id, m1b.parentId)
        // old version retained, raw unchanged
        assertEquals(m1.raw, e.store.sourceVersionById(m1.id)?.raw)
    }

    @Test
    fun `calibration is idempotent and carries deps algo and fingerprint`() {
        val e = baselineEngine()
        val d1 = e.calibrate("KIA-55001", "curve-labcal", null, "era-bce-ce")
        val d2 = e.calibrate("KIA-55001", "curve-labcal", null, "era-bce-ce")
        assertEquals(d1.id, d2.id)
        assertEquals(Calibration.ALGO_VERSION, d1.algoVersion)
        assertEquals(3, d1.deps.size)
        assertTrue(d1.fingerprint.all { it in "0123456789abcdef" })
        val r: CalibrationResult = jsonFmt.decodeFromString(d1.payload)
        assertEquals("LabCal@2020", r.curveRef)
    }

    @Test
    expires@`local measurement change only expires its own derived result`() {
        val e = baselineEngine()
        val m1 = e.calibrate("KIA-55001", "curve-labcal", null, "era-bce-ce")
        val m4 = e.calibrate("KIA-55004", "curve-labcal", null, "era-bce-ce")
        e.submitSource(
            SourceKind.MEASUREMENT, "KIA-55004", "14C yr BP", "lab erratum",
            """{"labCode":"KIA-55004","bpAge":1725.0,"sigma":25.0}""", "reviewer"
        )
        assertEquals(DerivedState.CURRENT, e.derivedStatus(m1).status)
        assertEquals(DerivedState.STALE, e.derivedStatus(m4).status)
        assertTrue(e.derivedStatus(m4).staleBecause.single().contains("KIA-55004"))
        // stale result is still viewable
        assertNotNull(e.store.derivedById(m4.id))
    }

    @Test
    fun `offset revision only expires the marine measurement`() {
        val e = baselineEngine()
        val marine = e.calibrate("KIA-55002", "curve-labcal", "offset-marine-local", "era-bce-ce")
        val land = e.calibrate("KIA-55001", "curve-labcal", null, "era-bce-ce")
        e.submitSource(
            SourceKind.OFFSET, "offset-marine-local", "14C yr (DeltaR)", "addendum",
            """{"label":"local","deltaR":-95.0,"deltaRSigma":40.0}""", "reviewer"
        )
        assertEquals(DerivedState.STALE, e.derivedStatus(marine).status)
        assertEquals(DerivedState.CURRENT, e.derivedStatus(land).status)
    }

    @Test
    fun `sequence conflict yields candidate adjustments without rewriting measurements`() {
        val e = baselineEngine()
        val check = e.sequenceCheck("seq-t2-m6-before-m5")
        val r = jsonFmt.decodeFromString<SequenceCheckResult>(check.payload)
        assertTrue(r.conflict)
        assertEquals("CONFLICT_INDETERMINATE", r.status)
        assertTrue(r.candidates.size >= 2)
        assertTrue(r.candidates.any { it.kind == "REQUEST_REMEASUREMENT" })
        // measurements untouched: stored raw still has the original 14C ages
        val m6 = e.store.latestSources()["KIA-55006"]!!
        assertTrue(m6.raw.contains("2750"))
    }

    @Test
    fun `consistent sequence constraint is accepted`() {
        val e = baselineEngine()
        val check = e.sequenceCheck("seq-t2-m5-before-m1")
        val r = jsonFmt.decodeFromString<SequenceCheckResult>(check.payload)
        assertFalse(r.conflict)
        assertEquals("OK", r.status)
        assertTrue(r.pOrder > 0.5)
    }

    @Test
    fun `accepted and rejected decisions are both retained and undo restores prior decision`() {
        val e = baselineEngine()
        val d = e.calibrate("KIA-55001", "curve-labcal", null, "era-bce-ce")
        e.adjudicate(d.id, Verdict.ACCEPTED, "first accept", "reviewer-a")
        e.adjudicate(d.id, Verdict.REJECTED, "later reject", "reviewer-b")
        assertEquals(Verdict.REJECTED, e.verdicts()[d.id]?.verdict)
        val rejectSeq = e.store.events().last().seq
        e.undo(rejectSeq, "mistaken rejection", "reviewer-b")
        assertEquals(Verdict.ACCEPTED, e.verdicts()[d.id]?.verdict)
        // the rejected decision remains in history
        assertTrue(e.store.events().count { it.type == EventType.ADJUDICATE } == 2)
    }

    @Test
    fun `history view restores dependency versions and conclusions of the time`() {
        val e = baselineEngine()
        val d1 = e.calibrate("KIA-55001", "curve-labcal", null, "era-bce-ce")
        val atFirst = e.store.events().maxOf { it.seq }
        e.adjudicate(d1.id, Verdict.ACCEPTED, "ok", "reviewer-a")
        e.submitSource(
            SourceKind.CURVE, "curve-labcal", "cal BP", "LabCal 2024",
            CasePack.baseline().sources.first { it.sourceId == "curve-labcal" }.raw, "reviewer"
        )
        // at the earlier point: record current, verdict absent
        assertEquals(DerivedState.CURRENT, e.derivedStatus(d1, atFirst).status)
        assertTrue(e.verdicts(atFirst).isEmpty())
        // now: record stale, verdict present
        assertEquals(DerivedState.STALE, e.derivedStatus(d1).status)
        assertEquals(Verdict.ACCEPTED, e.verdicts()[d1.id]?.verdict)
    }

    @Test
    fun `unconvertible value stays in place with diagnostic instead of being dropped`() {
        val (e) = newEngine()
        CasePack.applyBaseline(e)
        val sv = e.submitSource(
            SourceKind.MEASUREMENT, "BAD-1", "14C yr BP", "broken sheet",
            "two thousand four hundred and fifty plus minus thirty", "reviewer"
        )
        assertEquals(null, sv.dataJson)
        assertTrue(sv.diagnostics.isNotEmpty())
        assertTrue(e.store.latestSources().containsKey("BAD-1"))
    }

    @Test
    fun `unknown source kind is rejected as a clear format error`() {
        val (e) = newEngine()
        var threw = false
        try {
            e.submitSource("WAT", "x", "u", "p", "{}", "reviewer")
        } catch (_: Engine.ImportException) {
            threw = true
        }
        assertTrue(threw)
    }
}
