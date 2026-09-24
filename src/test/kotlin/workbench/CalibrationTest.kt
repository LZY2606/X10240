package workbench

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CalibrationTest {

    private fun syntheticCurve(amp: Double = 340.0, base: Double = -150.0, sig: Double = 25.0) =
        CurveData("intcal-shim-1", (0..6000 step 50).map { t ->
            CurvePoint(t.toDouble(),
                t + base + amp * Math.exp(-((t - 2600.0) / 450.0).let { it * it }) *
                    Math.sin((t - 2600.0) / 110.0), sig)
        })

    private val m = Measurement("LAB-0001", 2850.0, 30.0, "charcoal")

    @Test
    fun `multi-peak curve yields parallel HDR intervals and is not averaged to one year`() {
        val r = Calibrator.calibrate("src-m", m, "src-c", syntheticCurve(), null)
        assertFalse(r.unknown)
        assertTrue(r.peaks.size >= 2, "expected >=2 peaks, got ${r.peaks.size}")
        assertTrue(r.intervals68.size >= 2, "expected parallel intervals, got ${r.intervals68.size}")
        // 并列区间互不重叠，各自作为一等候选
        r.intervals68.zipWithNext().forEach { (a, b) -> assertTrue(a.hiCalBp < b.loCalBp) }
        assertTrue(r.diagnostics.any { it.startsWith("parallel-calendar-intervals") })
        // 结果中不存在被压成的单一“平均年”
        assertTrue(r.points.size > 100)
    }

    @Test
    fun `normalization is normalized and reproducible under fixed curve version`() {
        val c = syntheticCurve()
        val r1 = Calibrator.calibrate("src-m", m, "src-c", c, null)
        val r2 = Calibrator.calibrate("src-m", m, "src-c", c, null)
        assertEquals(r1.paramFingerprint, r2.paramFingerprint)
        assertEquals(r1.points.map { it.norm }, r2.points.map { it.norm })
        val total = run {
            var s = 0.0
            for (i in 0 until r1.points.size - 1)
                s += (r1.points[i].norm + r1.points[i + 1].norm) * 0.5
            s
        }
        assertEquals(1.0, total, 1e-9)
        // HDR 各区间概率之和约等于级别（并列区间合计）
        assertEquals(0.682, r1.intervals68.sumOf { it.probability }, 0.01)
        assertEquals(0.954, r1.intervals95.sumOf { it.probability }, 0.01)
    }

    @Test
    fun `interval evidence integrates consistently with machine readable constants`() {
        val r = Calibrator.calibrate("src-m", m, "src-c", syntheticCurve(), null)
        val iv = r.intervals68.first()
        val ev = r.evidenceFor(iv.loCalBp, iv.hiCalBp)
        assertEquals(iv.probability, ev.probability, 1e-9)
        assertTrue(ev.rawMass > 0 && ev.normalizationConstant > ev.rawMass)
        assertEquals(CALIB_ALGORITHM_VERSION, ev.algorithmVersion)
        // 全网格积分证据 = 1
        val full = r.evidenceFor(r.gridLo, r.gridHi)
        assertEquals(1.0, full.probability, 1e-9)
    }

    @Test
    fun `boundary interpolation clamps at curve edge with diagnostics`() {
        val r = Calibrator.calibrate("src-m",
            Measurement("LAB-0004", 5850.0, 50.0, "peat"),
            "src-c", syntheticCurve(), null, requestedLo = 5500.0, requestedHi = 6500.0)
        assertEquals(6000.0, r.gridHi, 1e-9)
        assertTrue(r.diagnostics.any { it.startsWith("boundary-clamp:high") })
    }

    @Test
    fun `reservoir offset explicit samples use deterministic mixture`() {
        val off = ReservoirOffset(345.0, 34.0, listOf(300.0, 330.0, 360.0, 390.0))
        val r = Calibrator.calibrate("src-m2",
            Measurement("LAB-0002", 3100.0, 35.0, "shell"),
            "src-c", syntheticCurve(), "src-off", off)
        assertTrue(r.diagnostics.any { it.contains("reservoir-offset-mixture") })
        // 固定输入完全可重放
        val r2 = Calibrator.calibrate("src-m2",
            Measurement("LAB-0002", 3100.0, 35.0, "shell"),
            "src-c", syntheticCurve(), "src-off", off)
        assertEquals(r.paramFingerprint, r2.paramFingerprint)
    }

    @Test
    fun `BCE-CE crossing is detected with no year zero`() {
        val r = Calibrator.calibrate("src-m3",
            Measurement("LAB-0003", 1900.0, 60.0, "bone"),
            "src-c", syntheticCurve(), null)
        assertTrue((r.intervals68 + r.intervals95).any { it.bceCeCross })
        assertEquals("BCE 1", Era.calBpToLabel(1950.0))
        assertEquals("CE 1", Era.calBpToLabel(1949.0))
        assertEquals("CE 1950", Era.calBpToLabel(0.0))
        assertTrue(Era.crossesBceCe(2000.0, 1900.0))
        assertFalse(Era.crossesBceCe(2100.0, 2000.0))
    }

    @Test
    fun `unconvertible or empty overlap yields unknown status not a fake number`() {
        val r = Calibrator.calibrate("src-x", m, "src-c", syntheticCurve(), null, requestedLo = 3000.0, requestedHi = 1000.0)
        assertTrue(r.unknown)
        assertEquals("grid range inverted", r.unknownReason)
    }
}
