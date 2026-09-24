package org.chronology.workbench

import kotlinx.serialization.json.decodeFromJsonElement
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WorkbenchAcceptanceTest {
    private val fixedClock = FixedClock()

    private fun service(file: File): WorkService {
        val store = SqliteEventStore(file.toPath())
        val service = WorkService(store, fixedClock)
        service.resetAndImport(CaseFactory(fixedClock).initialBundle())
        return service
    }

    private fun calibration(state: ProjectState, sample: String): CalibrationComputation {
        val view = state.derived.values.last { it.kind == "calibration" && it.subjectId == sample && it.status !in setOf("UNKNOWN_UNCONVERTIBLE_INPUT", "REJECTED_EMPTY_SUPPORT") }
        return workbenchJson.decodeFromJsonElement(CalibrationComputation.serializer(), view.payload)
    }

    private fun allCalibrations(state: ProjectState, sample: String) =
        state.derived.values.filter { it.kind == "calibration" && it.subjectId == sample }

    @Test
    fun `curve wiggle produces multiple legal calendar modes without an averaged verdict`(@TempDir dir: File) {
        val state = service(File(dir, "a.sqlite")).state()
        val multi = calibration(state, "sample-multi")
        assertTrue(multi.modes.size >= 3, "expected synthetic wiggle curve to expose multiple modes, got ${multi.modes.size}")
        assertTrue(multi.credibleIntervals.size >= 2)
        assertTrue(multi.credibleIntervals.sumOf { it.probability } in 0.90..1.001)
        assertEquals(null, multi.expectedBP)
    }

    @Test
    fun `boundary interpolation and normalization are reproducible under fixed curve`(@TempDir dir: File) {
        val firstService = service(File(dir, "first.sqlite"))
        val first = firstService.state().derived.values.first { it.kind == "calibration" && it.subjectId == "sample-boundary" }
        val exported = firstService.exportBundle()
        val secondService = service(File(dir, "second.sqlite"))
        secondService.resetAndImport(exported)
        val second = secondService.state().derived.values.first { it.kind == "calibration" && it.subjectId == "sample-boundary" }
        assertEquals(first.eventId, second.eventId)
        assertEquals(first.parameterFingerprint, second.parameterFingerprint)
        assertEquals(first.payload, second.payload)
    }

    @Test
    fun `reservoir offset distribution is retained as an explicit mixture`(@TempDir dir: File) {
        val state = service(File(dir, "a.sqlite")).state()
        val reservoir = state.inputs.getValue("reservoir-mixture-v1").payload
        val scenarios = reservoir["scenarios"]!!
        assertTrue(scenarios.toString().contains("reservoir-marine-120"))
        assertTrue(scenarios.toString().contains("reservoir-upwelling-240"))
        val marine = calibration(state, "sample-marine")
        assertTrue(marine.directSources.contains("reservoir-mixture-v1"))
    }

    @Test
    fun `credible interval records BCE CE crossing with integral evidence`(@TempDir dir: File) {
        val state = service(File(dir, "a.sqlite")).state()
        val boundary = calibration(state, "sample-boundary")
        val crossing = boundary.credibleIntervals.firstOrNull { it.crossesBCE_CE }
        assertNotNull(crossing)
        assertEquals("trapezoidal-grid-normalized-v1", crossing.integralEvidence.method)
        assertTrue(crossing.integralEvidence.pointCount > 1)
        assertTrue(crossing.integralEvidence.boundaryStates.any { it.boundaryBP == 1950.0 && it.inside })
    }

    @Test
    fun `sequence contradiction proposes candidate adjustments without rewriting measurements`(@TempDir dir: File) {
        val service = service(File(dir, "a.sqlite"))
        val initialMeasurement = service.state().inputs.getValue("measurement-sample-younger-v1").payload.toString()
        val sequence = service.state().derived.values.first { it.kind == "sequence" }
        assertEquals("SEQUENCE_CONFLICT", sequence.status)
        val payload = workbenchJson.decodeFromJsonElement(SequenceComputation.serializer(), sequence.payload)
        assertTrue(payload.candidates.any { it.kind == "FORCE_RELATION" && it.legal })
        assertTrue(payload.candidates.any { it.kind == "RELAX_RELATION" && it.legal })
        assertTrue(payload.candidates.count { it.legal } >= 3)
        assertEquals(initialMeasurement, service.state().inputs.getValue("measurement-sample-younger-v1").payload.toString())
    }

    @Test
    fun `local curve revision only expires overlapping dependents and leaves parallel result current`(@TempDir dir: File) {
        val service = service(File(dir, "a.sqlite"))
        val scenarios = CaseScenarioService(service, fixedClock)
        scenarios.applyStage("stage-2-local-curve-revision")
        val state = service.state()
        val multiViews = allCalibrations(state, "sample-multi")
        val boundaryViews = allCalibrations(state, "sample-boundary")
        assertTrue(multiViews.any { it.status == "STALE_DEPENDENCY_CHANGED" })
        assertTrue(boundaryViews.any { it.status == "PARALLEL_CURRENT_LOCAL_SEGMENTS_UNCHANGED" })
        val curve = state.inputs.getValue("curve-synthetic-intcal-like-v1")
        assertEquals("curve-synthetic-intcal-like-v2", curve.supersededBy)
    }

    @Test
    fun `curve upgrade preserves all parallel calendar intervals in historical events`(@TempDir dir: File) {
        val service = service(File(dir, "a.sqlite"))
        val before = calibration(service.state(), "sample-multi").credibleIntervals.map { it.intervalId }.toSet()
        CaseScenarioService(service, fixedClock).applyStage("stage-2-local-curve-revision")
        val state = service.state()
        val old = state.derived.values.first { it.kind == "calibration" && it.subjectId == "sample-multi" && it.status == "STALE_DEPENDENCY_CHANGED" }
        val oldPayload = workbenchJson.decodeFromJsonElement(CalibrationComputation.serializer(), old.payload)
        assertEquals(before, oldPayload.credibleIntervals.map { it.intervalId }.toSet())
        val latest = allCalibrations(state, "sample-multi").last()
        assertTrue(latest.directSources.contains("curve-synthetic-intcal-like-v2"))
    }

    @Test
    fun `mutually exclusive human decisions are stored as conflict and undo appends history`(@TempDir dir: File) {
        val service = service(File(dir, "a.sqlite"))
        CaseScenarioService(service, fixedClock).applyStage("stage-3-conflicting-decisions")
        val state = service.state()
        assertTrue(state.derived.values.any { it.kind == "decision-conflict" && it.status == "CONFLICT" })
        val decisions = state.decisions.values.filter { !it.reversed }
        assertEquals(2, decisions.size)
        service.reverseDecision(decisions.first().eventId, "test undo")
        val after = service.state()
        assertTrue(after.events.any { it.type == "DECISION_REVERSED" })
        assertEquals(1, after.decisions.values.count { !it.reversed })
    }

    @Test
    fun `delete database and import keeps identity order provenance and unconvertible diagnostics`(@TempDir dir: File) {
        val firstDb = File(dir, "first.sqlite")
        val first = service(firstDb)
        val bundle = first.exportBundle()
        firstDb.delete()
        val reopenedStore = SqliteEventStore(firstDb.toPath())
        val reopened = WorkService(reopenedStore, fixedClock)
        val report = reopened.resetAndImport(bundle)
        val state = reopened.state()
        assertEquals(bundle.events.map { it.id }, state.events.map { it.id })
        assertEquals(bundle.events.map { it.type }, state.events.map { it.type })
        assertEquals(bundle.events.map { it.directSources }, state.events.map { it.directSources })
        assertContains(report.diagnostics.map { it.code }, "UNCONVERTIBLE_QUANTITY")
        val bad = state.inputs.getValue("measurement-bad-v1")
        assertEquals("两千零四十上下", bad.payload["age14CBP"]?.let { (it as kotlinx.serialization.json.JsonObject)["rawRepresentation"] }?.toString()?.trim('"'))
    }
}
