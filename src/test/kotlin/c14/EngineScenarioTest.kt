package c14

import c14.domain.*
import kotlinx.serialization.encodeToString
import java.nio.file.Files
import kotlin.test.*

class EngineScenarioTest {
    private val clock = VirtualClock(1_700_000_000_000L)
    private val engine = Engine(clock)
    private val pid = "p1"

    private val wiggleCurve = CalibrationCurve(
        id = "c-v1", name = "Wiggle", version = "v1", sourceId = "src-curve",
        knots = (200..900 step 50).map { Knot(it, (it + 40).toDouble()) } +
            listOf(
                Knot(950, 980.0), Knot(1000, 1015.0), Knot(1030, 1050.0), Knot(1060, 1045.0),
                Knot(1090, 1000.0), Knot(1120, 960.0), Knot(1150, 980.0), Knot(1180, 1030.0),
                Knot(1210, 1070.0), Knot(1240, 1090.0), Knot(1270, 1075.0), Knot(1300, 1045.0),
                Knot(1330, 1015.0), Knot(1360, 1000.0), Knot(1390, 1015.0), Knot(1420, 1050.0),
                Knot(1450, 1095.0)
            ) +
            (1500..1980 step 40).map { Knot(it, (it + 30).toDouble()) }
    )

    private fun baseline(curves: List<CalibrationCurve> = listOf(wiggleCurve)) {
        engine.startProject(pid, "T", "BP/BCE-CE")
        engine.command(pid, Command("REGISTER_SOURCE", domainJson.encodeToString(SourceRegistered(Source("src-curve", "curve", "c", "ref"))), "src-curve"))
        engine.command(pid, Command("REGISTER_SOURCE", domainJson.encodeToString(SourceRegistered(Source("src-lab", "lab", "l", "ref"))), "src-lab"))
        engine.command(pid, Command("REGISTER_SOURCE", domainJson.encodeToString(SourceRegistered(Source("src-marine", "offset", "o", "ref"))), "src-marine"))
        engine.command(pid, Command("REGISTER_SOURCE", domainJson.encodeToString(SourceRegistered(Source("src-strat", "strat", "s", "ref"))), "src-strat"))
        for (curve in curves) {
            engine.command(pid, Command("REGISTER_CURVE", domainJson.encodeToString(CurveRegistered(curve)), "src-curve"))
        }
    }

    private fun measure(sample: String, id: String, age: Double, sigma: Double, parent: String? = null) {
        engine.command(pid, Command(
            "SUBMIT_MEASUREMENT",
            domainJson.encodeToString(MeasurementSubmitted(Measurement(id, sample, "L", age, sigma, "src-lab", parentMeasurementId = parent))),
            "src-lab"
        ))
    }

    @Test
    fun `curve multimodality is kept as multiple intervals, never averaged`() {
        baseline()
        measure("S1", "m1", 1000.0, 30.0)
        val snap = engine.snapshot(pid)
        val result = snap.latestResult("S1")!!
        assertTrue(result.intervals95.size >= 2, "expected multiple HPD95 intervals, got ${result.intervals95.size}")
        assertTrue(result.modes.size >= 2, "expected multiple modes")
        // Interval probabilities sum close to level and are individually reported.
        assertTrue(result.intervals95.sumOf { it.probability } > 0.9)
        // Stable interval ids.
        assertTrue(result.intervals95.all { it.id.startsWith("ivl:m1:95:") })
    }

    @Test
    fun `fixed version calibration is reproducible`() {
        baseline()
        measure("S1", "m1", 1000.0, 30.0)
        val first = engine.snapshot(pid).latestResult("S1")!!
        val rebuilt = Calibrator().calibrate(
            CalibrationInputs(Measurement("m1", "S1", "L", 1000.0, 30.0, "src-lab"), wiggleCurve, null)
        )
        assertEquals(first.paramFingerprint, rebuilt.paramFingerprint)
        assertEquals(first.cells, rebuilt.cells)
        assertEquals(first.intervals95.map { it.id to it.probability }, rebuilt.intervals95.map { it.id to it.probability })
    }

    @Test
    fun `boundary interpolation flags truncation at curve edge`() {
        baseline()
        measure("S4", "m4", 2075.0, 25.0)
        val result = engine.snapshot(pid).latestResult("S4")!!
        assertTrue(result.boundaryTruncated, "outside mass should flag truncation: ${result.outsideMass}")
        assertTrue(result.outsideMass > 0.5)
        assertEquals(wiggleCurve.maxBp, result.cells.last().calBp)
    }

    @Test
    fun `discrete reservoir offset mixture changes posterior and carries source`() {
        baseline()
        val gauss = OffsetModel("og", "G", "src-marine", 180.0, 60.0)
        val mix = OffsetModel(
            "om", "M", "src-marine", 180.0, 60.0,
            listOf(
                OffsetComponent(120.0, 40.0, 0.4),
                OffsetComponent(180.0, 50.0, 0.4),
                OffsetComponent(260.0, 45.0, 0.2)
            )
        )
        for (o in listOf(gauss, mix)) {
            engine.command(pid, Command("REGISTER_OFFSET", domainJson.encodeToString(OffsetModelRegistered(o)), "src-marine"))
        }
        measure("S2", "m2", 1050.0, 40.0)
        val plain = engine.snapshot(pid).latestResult("S2")!!
        engine.command(pid, Command("SET_ASSUMPTION", domainJson.encodeToString(AssumptionSet("S2", "om", "mix", "src-marine")), "src-marine"))
        val mixed = engine.snapshot(pid).latestResult("S2")!!
        assertNotEquals(plain.paramFingerprint, mixed.paramFingerprint)
        assertTrue(mixed.directSources.containsAll(listOf("src-lab", "src-curve", "src-marine")))
        // The old result is retained but marked stale, not overwritten.
        val views = engine.snapshot(pid).results.filter { it.result.sampleId == "S2" }
        assertEquals(2, views.size)
        assertTrue(views.first().stale)
    }

    @Test
    fun `BCE CE crossing interval is detected`() {
        baseline()
        measure("S3", "m3", 1930.0, 70.0)
        val result = engine.snapshot(pid).latestResult("S3")!!
        assertTrue(result.eraCross, "posterior should cross year 0")
        assertTrue(result.intervals95.any { Era.crossesBceCe(it.fromBp, it.toBp) })
        assertEquals("1 BCE", Era.label(1950))
        assertEquals("100 BCE", Era.label(2049))
        assertEquals("1 CE", Era.label(1949))
    }

    @Test
    fun `sequence contradiction yields candidate adjustments without rewriting measurements`() {
        baseline()
        measure("S5", "m5", 1000.0, 30.0)
        measure("S6", "m6", 1280.0, 30.0)
        engine.command(pid, Command(
            "DECLARE_LINK",
            domainJson.encodeToString(LinkDeclared(SequenceLink("l1", "S5", "S6", "src-strat"))),
            "src-strat"
        ))
        val snap = engine.snapshot(pid)
        val analysis = snap.allAnalyses.last()
        assertFalse(analysis.consistent)
        assertTrue(analysis.candidates.isNotEmpty())
        assertTrue(analysis.candidates.any { it.kind == "REVISE_ORDER" })
        // Original measurements stay untouched.
        assertEquals(1280.0, snap.measurements.first { it.id == "m6" }.ageBp)

        // Resolving by a revised measurement (new revision) removes contradiction.
        measure("S6", "m6-r", 900.0, 35.0, parent = "m6")
        val after = engine.snapshot(pid)
        assertTrue(after.allAnalyses.last().consistent)
        // Parent measurement retained.
        assertEquals(setOf("m6", "m6-r"), after.measurements.filter { it.sampleId == "S6" }.map { it.id }.toSet())
        val stale = after.results.first { it.result.measurementId == "m6" }
        assertTrue(stale.stale)
    }

    @Test
    fun `curve bump only invalidates dependent results and keeps history viewable`() {
        baseline()
        measure("S1", "m1", 1000.0, 30.0)
        val old = engine.snapshot(pid).latestResult("S1")!!
        val v2Knots = wiggleCurve.knots.map { k ->
            when (k.calBp) {
                1090, 1120, 1150 -> k.copy(c14AgeBp = k.c14AgeBp + 18)
                1270, 1300, 1330 -> k.copy(c14AgeBp = k.c14AgeBp - 12)
                else -> k
            }
        }
        val v2 = wiggleCurve.copy(id = "c-v2", version = "v2", parentCurveId = "c-v1", knots = v2Knots)
        engine.command(pid, Command("REGISTER_CURVE", domainJson.encodeToString(CurveRegistered(v2)), "src-curve"))
        val snap = engine.snapshot(pid)
        val views = snap.results.filter { it.result.sampleId == "S1" }
        assertEquals(2, views.size)
        val oldView = views.first { it.result.id == old.id }
        assertTrue(oldView.stale)
        val fresh = views.first { !it.stale }.result
        assertEquals("c-v2", fresh.curveId)
        // History replay restores the v1 conclusion at the old event count.
        val oldSnap = engine.snapshotAt(pid, old.eventSeq)
        val atOld = oldSnap.results.single { it.result.sampleId == "S1" }
        assertFalse(atOld.stale)
        assertEquals("c-v1", atOld.result.curveId)
    }

    @Test
    fun `decisions and revoke stay in history with conflict detection`() {
        baseline()
        measure("S1", "m1", 1000.0, 30.0)
        engine.command(pid, Command("REGISTER_SOURCE", domainJson.encodeToString(SourceRegistered(Source("src-r", "rev", "r", "ref"))), "src-r"))
        fun decide(id: String, kind: String, rationale: String, subject: String = "S1", supersedes: String? = null) {
            engine.command(pid, Command(
                "DECIDE",
                domainJson.encodeToString(DecisionRecorded(DecisionView(id = id, kind = kind, subject = subject, rationale = rationale, sourceId = "src-r", supersedesDecisionId = supersedes, eventSeq = -1))),
                "src-r"
            ))
        }
        decide("dA", "ADJUST_PARAMETER", "apply -40 old-wood")
        decide("dB", "ADJUST_PARAMETER", "do not apply old-wood")
        assertTrue(engine.snapshot(pid).conflicts.isNotEmpty())
        decide("dR", "REVOKE", "revoke A", subject = "dA")
        val snap = engine.snapshot(pid)
        assertTrue(snap.conflicts.none { it.a.id == "dA" })
        assertEquals(3, snap.decisions.size)
    }
}
