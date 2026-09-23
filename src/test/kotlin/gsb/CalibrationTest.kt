package gsb

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

class CalibrationTest : TestBase() {

    /** Synthetic strongly-wiggly curve: c14 axis backtracks, so a narrow measurement yields multiple peaks. */
    private fun wigglyCurve(): Curve {
        val pts = (2000..3000 step 5).map { t ->
            val c14 = t - 40.0 + 90.0 * kotlin.math.sin(2.0 * Math.PI * (t - 2000) / 160)
            CurvePoint(t, c14, 18.0)
        }
        return Curve("TEST-CURVE", "1.0", pts)
    }

    @Test
    fun `multi-peak distribution is not collapsed to a single mean`() {
        val out = Calibrator.calibrate(2360.0, 20.0, 0.0, 0.0, wigglyCurve())
        assertTrue(out.peaks.size >= 2, "expected multiple peaks, got ${out.peaks.size}")
        val total = out.density.sum()
        assertEquals(1.0, total, 1e-9, "density must normalise to 1")
        val peakMass = out.peaks.sumOf { it.mass }
        assertEquals(1.0, peakMass, 1e-6, "peaks should partition essentially all mass")
        // The mode of a multi-peak result must not be mistaken for an average year.
        val meanBp = out.density.indices.sumOf { (out.loBp + it) * out.density[it] }
        assertTrue(out.peaks.any { abs(it.apexBp - meanBp) > 20 },
            "kept peaks must differ from a single averaged year")
    }

    @Test
    fun `piecewise-linear interpolation is exact at midpoint and clamped at boundaries`() {
        val c = Curve("T", "1", listOf(CurvePoint(100, 200.0, 10.0), CurvePoint(110, 230.0, 20.0), CurvePoint(120, 260.0, 30.0)))
        val (y, s) = c.interp(105.0)
        assertEquals(215.0, y, 1e-12)
        assertEquals(15.0, s, 1e-12)
        assertEquals(200.0, c.interp(0.0).first, 1e-12, "below range clamps at first point")
        assertEquals(260.0, c.interp(500.0).first, 1e-12, "above range clamps at last point")
    }

    @Test
    fun `same parameters and fixed curve version reproduce identical fingerprint and density`() {
        val a = Calibrator.calibrate(2360.0, 20.0, 0.0, 0.0, wigglyCurve())
        val b = Calibrator.calibrate(2360.0, 20.0, 0.0, 0.0, wigglyCurve())
        assertEquals(a.paramFingerprint, b.paramFingerprint)
        assertEquals(a.density, b.density)
        val changed = Calibrator.calibrate(2360.0, 21.0, 0.0, 0.0, wigglyCurve())
        assertNotEquals(a.paramFingerprint, changed.paramFingerprint)
    }

    @Test
    fun `reservoir offset distribution shifts effective mean and changes fingerprint`() {
        val noOffset = Calibrator.calibrate(2360.0, 25.0, 0.0, 0.0, wigglyCurve())
        val marine = Calibrator.calibrate(2360.0, 25.0, 120.0, 40.0, wigglyCurve())
        assertNotEquals(noOffset.paramFingerprint, marine.paramFingerprint)
        val supportNo = noOffset.peaks.minOf { it.apexBp }..noOffset.peaks.maxOf { it.apexBp }
        val supportMarine = marine.peaks.minOf { it.apexBp }..marine.peaks.maxOf { it.apexBp }
        assertNotEquals(supportNo, supportMarine, "a positive Delta-R must move the calibrated calendar support")
    }

    @Test
    fun `HPD interval evidence probabilities match their integrals`() {
        val out = Calibrator.calibrate(2360.0, 20.0, 0.0, 0.0, wigglyCurve())
        for (iv in out.intervals) {
            val ev = iv.evidence
            val prob = ev["prob"]!!.jsonPrimitiveDouble()
            val rawSum = ev["rawSum"]!!.jsonPrimitiveDouble()
            val total = ev["totalSum"]!!.jsonPrimitiveDouble()
            assertEquals(rawSum / total, prob, 1e-12)
            assertEquals(iv.prob, prob, 1e-12)
        }
        val mass954 = out.intervals.filter { it.level == 0.954 }.sumOf { it.prob }
        assertTrue(mass954 >= 0.954 - 1e-9, "95.4% runs must cover the level, was $mass954")
    }

    @Test
    fun `BCE-CE labels use astronomical numbering without year zero`() {
        assertEquals("BCE 1", Era.astroYearToLabel(0.0))
        assertEquals("CE 1", Era.astroYearToLabel(1.0))
        assertEquals("BCE 2", Era.astroYearToLabel(-1.0))
        assertEquals("BCE 1", Era.calBpToLabel(1950.0))
        assertEquals("CE 1", Era.calBpToLabel(1949.0))
    }

    private fun kotlinx.serialization.json.JsonElement.jsonPrimitiveDouble() =
        (this as kotlinx.serialization.json.JsonPrimitive).content.toDouble()
}
