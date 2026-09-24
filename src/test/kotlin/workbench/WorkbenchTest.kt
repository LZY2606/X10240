package workbench

import java.nio.file.Files
import kotlin.test.*

class WorkbenchTest {
    private val clock = VirtualClock()
    private val dbPath get() = Files.createTempDirectory("workbench-test").resolve("state.db")

    @Test
    fun reproducesMultimodalCalibrationAtFixedVersion() {
        val svc = AppService(dbPath, clock)
        svc.importPackage(caseJson())
        val alpha = svc.snapshot().calibrations.single { it.measurementId == "m-alpha" }
        val modes = alpha.intervals.filter { it.level == "95%" }
        assertTrue(modes.size >= 2, "expected multiple legal calendar intervals, got ${modes.size}")
        assertTrue(alpha.intervals.none { it.label.contains("average", ignoreCase = true) })
        val again = AppService(dbPath, clock).snapshot().calibrations.single { it.measurementId == "m-alpha" }
        assertEquals(alpha.fingerprint, again.fingerprint)
        assertEquals(alpha.probabilities.map { it.calendarYear to it.p }, again.probabilities.map { it.calendarYear to it.p })
    }

    @Test
    fun boundaryInterpolationAndBceCeCrossingAreFirstClass() {
        val svc = AppService(dbPath, clock)
        svc.importPackage(caseJson())
        val edge = svc.snapshot().calibrations.single { it.measurementId == "m-edge" }
        assertEquals("truncated_at_curve_boundary", edge.status)
        assertEquals(400, edge.probabilities.first().calendarYear)
        assertEquals(420, edge.probabilities.last().calendarYear)
        assertTrue(edge.probabilities.sumOf { it.p } > 0.90)
        val alpha = svc.snapshot().calibrations.single { it.measurementId == "m-alpha" }
        assertTrue(alpha.intervals.flatMap { it.years }.any { it < 0 } && alpha.intervals.flatMap { it.years }.any { it > 0 })
        assertTrue(alpha.integralEvidence.any { it.method == "linear_curve_interpolation" })
    }

    @Test
    fun reservoirDistributionChangesOnlyDirectDependents() {
        val svc = AppService(dbPath, clock)
        svc.importPackage(caseJson())
        val oldAlpha = svc.currentCalibration("m-alpha")
        val oldEdge = svc.currentCalibration("m-edge")
        svc.reviseReservoir("res-marine-mixed", 70, 25, "tidal-note:9", "v2")
        val snap = svc.snapshot()
        val newAlpha = snap.calibrations.single { it.measurementId == "m-alpha" && it.status == "current" }
        val newEdge = snap.calibrations.single { it.measurementId == "m-edge" && it.status == "current" }
        assertNotEquals(oldAlpha.fingerprint, newAlpha.fingerprint)
        assertEquals(oldEdge.fingerprint, newEdge.fingerprint)
        assertTrue(newEdge.evidence.contains("res-zero"))
    }

    @Test
    fun localMeasurementRevisionOnlyInvalidatesItsDerivedResult() {
        val svc = AppService(dbPath, clock)
        svc.importPackage(caseJson())
        val beta = svc.currentCalibration("m-beta")
        val alpha = svc.currentCalibration("m-alpha")
        svc.reviseMeasurement("m-edge", "1595", "18", "boundary-book:B3", "v2")
        svc.activateCurve("curve-intcal-mini-2025", "curve-review:1")
        val snap = svc.snapshot()
        val staleBeta = snap.calibrations.single { it.measurementId == "m-beta" && it.status == "stale" }
        val currentAlpha = snap.calibrations.single { it.measurementId == "m-alpha" && it.status == "current" }
        val edgeV2 = snap.calibrations.single { it.measurementId == "m-edge" && it.status == "current" }
        assertEquals(beta.fingerprint, staleBeta.fingerprint)
        assertEquals(alpha.fingerprint, currentAlpha.fingerprint)
        assertNotEquals(beta.version, edgeV2.version)
        assertTrue(edgeV2.status == "current")
    }

    @Test
    fun sequenceConflictKeepsMeasurementAndReturnsCandidates() {
        val svc = AppService(dbPath, clock)
        svc.importPackage(caseJson())
        val conflict = svc.snapshot().sequenceStatuses.single { it.sequenceId == "seq-alpha-before-gamma" }
        assertEquals("conflict", conflict.state)
        assertTrue(conflict.legalAdjustmentCandidates.any { it.kind == "relax_constraint" })
        assertTrue(conflict.legalAdjustmentCandidates.any { it.kind == "revise_reservoir_assumption" })
        assertTrue(conflict.legalAdjustmentCandidates.any { it.kind == "exclude_sample" })
        svc.resolveConflict("seq-alpha-before-gamma", "accept_younger_gamma_override", listOf(listOf(-3,-2), listOf(7,8)), "chronologist note")
        val decision = svc.snapshot().decisions.last()
        assertEquals("accepted_with_conflict", decision.state)
        assertEquals("m-gamma", decision.targetId)
        val measurementEvents = svc.snapshot().events.count { it.type == "measurement_committed" }
        assertEquals(5, measurementEvents)
    }

    @Test
    fun rejectionAndUndoAreHistoricalImmutableEvents() {
        val svc = AppService(dbPath, clock)
        svc.importPackage(caseJson())
        svc.resolveConflict("seq-alpha-before-gamma", "reject_gamma_interval", emptyList(), "uncertain context")
        assertEquals("rejected", svc.snapshot().decisions.last().state)
        svc.undoLastDecision("undo note")
        val snap = svc.snapshot()
        assertEquals("conflict", snap.sequenceStatuses.single { it.sequenceId == "seq-alpha-before-gamma" }.state)
        assertEquals(listOf("rejected", "undone"), snap.decisions.map { it.state })
        val atFirst = svc.snapshotAt(snap.events.first { it.type == "decision" }.eventId)
        assertEquals("rejected", atFirst.decisions.last().state)
        assertEquals("conflict", svc.snapshot().sequenceStatuses.single { it.sequenceId == "seq-alpha-before-gamma" }.state)
    }

    @Test
    fun exportDeleteImportPreservesIdentityOrderAndSources() {
        val svc = AppService(dbPath, clock)
        svc.importPackage(caseJson())
        svc.resolveConflict("seq-alpha-before-gamma", "relax_constraint", emptyList(), "tentative")
        val export = svc.exportProject()
        val reopened = Files.createTempDirectory("workbench-import").resolve("state.db")
        val imported = AppService(reopened, VirtualClock()).importProject(export)
        val a = svc.snapshot()
        assertEquals(a.events.map { it.eventId to it.seq }, imported.events.map { it.eventId to it.seq })
        assertEquals(a.measurements.map { it.stableId }, imported.measurements.map { it.stableId })
        assertEquals(a.calibrations.map { it.stableId }, imported.calibrations.map { it.stableId })
        assertEquals(a.sequenceStatuses.map { it.evidenceRefs }, imported.sequenceStatuses.map { it.evidenceRefs })
        assertTrue(export.events.all { it.payload.contains("sourceRef") || it.type.startsWith("calibration") })
    }

    @Test
    fun rejectsMalformedImportWithExplicitDiagnostics() {
        val svc = AppService(dbPath, clock)
        val ex = assertFailsWith<ValidationException> {
            svc.importPackage("""{"measurements":[{"id":"x","radiocarbonAge":"oops"}]}""")
        }
        assertTrue(ex.diagnostics.any { it.path.startsWith("measurements[0]") })
        assertTrue(ex.diagnostics.any { it.path.contains("curveId") })
        val duplicate = AppService(dbPath.resolveSibling("dup.db"), clock)
        duplicate.importPackage(caseJson())
        assertFailsWith<ValidationException> { duplicate.importPackage(caseJson()) }
    }

    private fun caseJson(): String =
        javaClass.getResourceAsStream("/cases/carbon-candidate-bench.json")!!.bufferedReader().readText()
}
