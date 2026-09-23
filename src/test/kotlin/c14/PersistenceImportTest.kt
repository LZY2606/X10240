package c14

import c14.domain.*
import c14.store.EventStore
import c14.web.AppEngine
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.put
import java.io.File
import java.nio.file.Path
import kotlin.test.*

class PersistenceImportTest {
    @Test
    fun `delete sqlite and re-import preserves identities order and sources`(@org.junit.jupiter.api.io.TempDir temp: Path) {
        val clock = VirtualClock(1_600_000_000_000L, 30_000L)
        val app1 = AppEngine(clock, temp.resolve("bench.sqlite"))
        val e = app1.engine
        e.startProject("p", "Roundtrip", "BP/BCE-CE")
        e.command("p", Command("REGISTER_SOURCE", domainJson.encodeToString(SourceRegistered(Source("s1", "lab", "lab", "r"))), "s1"))
        val curve = CalibrationCurve("c1", "C", "v1", "s1", listOf(Knot(200, 240.0), Knot(1500, 1530.0)))
        e.command("p", Command("REGISTER_CURVE", domainJson.encodeToString(CurveRegistered(curve)), "s1"))
        e.command("p", Command("SUBMIT_MEASUREMENT", domainJson.encodeToString(
            MeasurementSubmitted(Measurement("m1", "S", "L", 900.0, 30.0, "s1", rawText = "900 ± 30 BP"))
        ), "s1"))
        val bundle = Importer.exportEvents(e, "p", "Roundtrip")

        // Stable identities within a result.
        val ivlId = e.snapshot("p").latestResult("S")!!.intervals95.first().id

        // Simulate local database deletion: delete file, recreate store from nothing.
        app1.store.let { }
        val dbFile = temp.resolve("bench.sqlite").toFile()
        assertTrue(dbFile.exists())
        dbFile.delete()
        assertFalse(dbFile.exists())

        val app2 = AppEngine(clock, temp.resolve("bench.sqlite"))
        val report = Importer.importEvents(app2.engine, bundle)
        assertTrue(report.accepted, report.diagnostics.toString())
        val snap = app2.engine.snapshot("p")
        assertEquals("Roundtrip", snap.title)
        assertEquals(bundle.events.map { it.id }, app2.engine.eventLog("p").map { it.id })
        assertEquals(bundle.events.map { it.hash }, app2.engine.eventLog("p").map { it.hash })
        assertEquals(ivlId, snap.latestResult("S")!!.intervals95.first().id)
        assertEquals("s1", snap.latestResult("S")!!.directSources.first())
        assertEquals("900 ± 30 BP", snap.measurements.first().rawText)
    }

    @Test
    fun `broken hash chain is rejected`(@org.junit.jupiter.api.io.TempDir temp: Path) {
        val engine = Engine(VirtualClock())
        engine.startProject("p", "T", "s")
        engine.command("p", Command("REGISTER_SOURCE", domainJson.encodeToString(SourceRegistered(Source("s1", "lab", "l", "r"))), "s1"))
        val bundle = Importer.exportEvents(engine, "p", "T")
        val tampered = bundle.copy(events = bundle.events.toMutableList().also {
            it[1] = it[1].copy(parentHash = "forged")
        })
        val fresh = Engine(VirtualClock())
        val report = Importer.importEvents(fresh, tampered)
        assertFalse(report.accepted)
        assertTrue(report.diagnostics.any { it.code == "CHAIN_BROKEN" })
    }

    @Test
    fun `malformed baseline yields explicit format diagnostics and stays empty`(@org.junit.jupiter.api.io.TempDir temp: Path) {
        val engine = Engine(VirtualClock())
        val bundle = BaselineBundle(
            projectId = "p",
            title = "bad",
            sources = listOf(
                kotlinx.serialization.json.buildJsonObject {
                    put("id", kotlinx.serialization.json.JsonPrimitive(""))
                    put("kind", kotlinx.serialization.json.JsonPrimitive("lab"))
                    put("title", kotlinx.serialization.json.JsonPrimitive("t"))
                    put("ref", kotlinx.serialization.json.JsonPrimitive("r"))
                }
            ),
            measurements = listOf(
                kotlinx.serialization.json.buildJsonObject {
                    put("id", kotlinx.serialization.json.JsonPrimitive("m1"))
                    put("sampleId", kotlinx.serialization.json.JsonPrimitive("S1"))
                    put("ageBp", kotlinx.serialization.json.JsonPrimitive(-5.0))
                    put("sigmaBp", kotlinx.serialization.json.JsonPrimitive(0.0))
                    put("sourceId", kotlinx.serialization.json.JsonPrimitive("missing"))
                }
            )
        )
        val report = Importer.importBaseline(engine, bundle)
        assertFalse(report.accepted)
        val codes = report.diagnostics.map { it.code }.toSet()
        assertTrue("BLANK_ID" in codes)
        assertTrue("UNKNOWN_SOURCE" in codes)
        assertFalse(engine.hasProject("p"))
    }

    @Test
    fun `partial input change only invalidates its dependents`(@org.junit.jupiter.api.io.TempDir temp: Path) {
        val engine = Engine(VirtualClock())
        engine.startProject("p", "T", "s")
        engine.command("p", Command("REGISTER_SOURCE", domainJson.encodeToString(SourceRegistered(Source("s1", "lab", "l", "r"))), "s1"))
        val curve = CalibrationCurve("c1", "C", "v1", "s1",
            (200..1500 step 50).map { Knot(it, (it + 20).toDouble()) })
        engine.command("p", Command("REGISTER_CURVE", domainJson.encodeToString(CurveRegistered(curve)), "s1"))
        engine.command("p", Command("SUBMIT_MEASUREMENT", domainJson.encodeToString(
            MeasurementSubmitted(Measurement("mA", "A", "L", 700.0, 30.0, "s1"))), "s1"))
        engine.command("p", Command("SUBMIT_MEASUREMENT", domainJson.encodeToString(
            MeasurementSubmitted(Measurement("mB", "B", "L", 800.0, 30.0, "s1"))), "s1"))
        engine.command("p", Command("SUBMIT_MEASUREMENT", domainJson.encodeToString(
            MeasurementSubmitted(Measurement("mA2", "A", "L2", 710.0, 30.0, "s1", parentMeasurementId = "mA"))), "s1"))
        val snap = engine.snapshot("p")
        val aOld = snap.results.first { it.result.measurementId == "mA" }
        val aNew = snap.results.first { it.result.measurementId == "mA2" }
        val b = snap.results.first { it.result.measurementId == "mB" }
        assertTrue(aOld.stale)
        assertFalse(aNew.stale)
        assertFalse(b.stale)
    }

    @Test
    fun `sqlite store roundtrips raw events via AppEngine hydration`(@org.junit.jupiter.api.io.TempDir temp: Path) {
        val clock = VirtualClock()
        val app = AppEngine(clock, temp.resolve("x.sqlite"))
        app.engine.startProject("p", "T", "s")
        app.engine.command("p", Command("REGISTER_SOURCE", domainJson.encodeToString(
            SourceRegistered(Source("s1", "lab", "l", "r"))), "s1"))
        assertEquals(2, app.store.countEvents("p"))
        val reloaded = EventStore(temp.resolve("x.sqlite"))
        val rows = reloaded.loadEvents("p")
        assertEquals(2, rows.size)
        assertEquals("genesis", rows.first().parentHash)
    }
}
