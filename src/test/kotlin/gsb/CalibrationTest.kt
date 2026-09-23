package gsb

import kotlinx.serialization.decodeFromString
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CalibrationTest {

    private val curve: CurveData by lazy {
        val pack = CasePack.baseline()
        val raw = pack.sources.first { it.sourceId == "curve-labcal" }.raw
        jsonFmt.decodeFromString(raw)
    }
    private val era = EraSystemData("BCE_CE_NO_YEAR_ZERO", 1950)

    private fun measOf(id: String): MeasurementData {
        val raw = CasePack.baseline().sources.first { it.sourceId == id }.raw
        return jsonFmt.decodeFromString(raw)
    }

    @Test
    fun `multimodal distribution keeps several peaks and is not collapsed to a mean`() {
        val r = Calibration.calibrate(measOf("KIA-55001"), "KIA-55001", null, curve, era)
        assertTrue(r.peaks.size >= 2, "expected multimodal peaks, got ${r.peaks.size}")
        assertTrue(r.diagnostics.any { it.contains("multi-modal") })
        // distribution is a density, not a point estimate
        assertTrue(r.points.size > 100)
    }

    @Test
    fun `disjoint calendar intervals are kept side by side`() {
        val r = Calibration.calibrate(measOf("KIA-55001"), "KIA-55001", null, curve, era)
        val all = r.intervals682 + r.intervals954
        assertTrue(all.size >= 2, "expected并列 intervals, got $all")
        for (iv in all) {
            assertTrue(iv.fromCalBp < iv.toCalBp)
            assertTrue(iv.mass > 0.0)
        }
    }

    @Test
    fun `boundary interpolation clamps outside the curve domain and is linear inside`() {
        val pts = curve.points
        val (cLo, sLo) = Calibration.interpolate(pts, pts.first().calBp - 100)
        assertEquals(pts.first().c14Age, cLo)
        assertEquals(pts.first().sigma, sLo)
        val (cHi, _) = Calibration.interpolate(pts, pts.last().calBp + 100)
        assertEquals(pts.last().c14Age, cHi)
        val a = pts[10]; val b = pts[11]
        val mid = (a.calBp + b.calBp) / 2
        val (cMid, _) = Calibration.interpolate(pts, mid)
        assertEquals((a.c14Age + b.c14Age) / 2, cMid, 1e-9)
    }

    @Test
    fun `young measurement near curve edge reports boundary diagnostic`() {
        val r = Calibration.calibrate(measOf("KIA-55003"), "KIA-55003", null, curve, era)
        assertTrue(r.diagnostics.any { it.contains("boundary") || it.contains("outside curve domain") })
    }

    @Test
    fun `reservoir offset distribution is convolved not added as constant`() {
        val offset = OffsetData("local", -120.0, 45.0)
        val m = measOf("KIA-55002")
        val r = Calibration.calibrate(m, "KIA-55002", offset, curve, era)
        assertEquals(m.bpAge - (-120.0), r.effectiveMu, 1e-9)
        assertEquals(sqrt(m.sigma * m.sigma + 45.0 * 45.0), r.effectiveSigma, 1e-9)
        // offset widens the distribution relative to no-offset calibration
        val r0 = Calibration.calibrate(m, "KIA-55002", null, curve, era)
        assertTrue(r.effectiveSigma > r0.effectiveSigma)
        assertTrue(abs(r.effectiveMu - r0.effectiveMu) > 1.0)
    }

    @Test
    fun `BCE CE crossing interval carries era labels on both sides`() {
        assertEquals("CE 1", Calibration.eraLabel(1949.0))
        assertEquals("BCE 1", Calibration.eraLabel(1950.0))
        assertEquals("BCE 2", Calibration.eraLabel(1951.0))
        val r = Calibration.calibrate(measOf("KIA-55004"), "KIA-55004", null, curve, era)
        val crossing = r.intervals954.any { it.fromEra.startsWith("BCE") && it.toEra.startsWith("CE") }
        assertTrue(crossing, "expected a BCE/CE crossing interval, got ${r.intervals954}")
    }

    @Test
    fun `normalization is reproducible and density integrates to one`() {
        val m = measOf("KIA-55001")
        val r1 = Calibration.calibrate(m, "KIA-55001", null, curve, era)
        val r2 = Calibration.calibrate(m, "KIA-55001", null, curve, era)
        assertEquals(r1, r2)
        val total = r1.points.sumOf { it.density } * r1.gridStep
        assertEquals(1.0, total, 1e-9)
    }

    @Test
    fun `integral evidence accounts for interval probability`() {
        val m = measOf("KIA-55001")
        val r = Calibration.calibrate(m, "KIA-55001", null, curve, era)
        val ev = Calibration.integralEvidence("dr-test", r, "95", 0, curve, null, "fp")
        assertEquals(ev.probability, ev.rawMass / ev.normalization, 1e-9)
        assertTrue(ev.curveSegment.isNotEmpty())
        assertTrue(ev.nGridPoints > 0)
        assertEquals(Calibration.ALGO_VERSION, ev.algoVersion)
    }
}
