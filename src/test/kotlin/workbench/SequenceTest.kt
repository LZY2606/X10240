package workbench

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SequenceTest {
    private fun curve() = CurveData("c1", (0..6000 step 50).map { t ->
        CurvePoint(t.toDouble(),
            t - 150.0 + 340.0 * Math.exp(-Math.pow((t - 2600.0) / 450.0, 2.0)) *
                Math.sin((t - 2600.0) / 110.0), 25.0)
    })

    private fun cal(lab: String, age: Double, sig: Double, id: String): Pair<String, CalibrationResult> {
        val res = Calibrator.calibrate("src-$lab", Measurement(lab, age, sig, "charcoal"),
            "src-curve", curve(), null)
        return id to res
    }

    @Test
    fun `sequence conflict yields candidate adjustments and keeps all legal candidates`() {
        val s1 = cal("LAB-0005", 2400.0, 25.0, "der-s1") // I 层，较年轻
        val s2 = cal("LAB-0006", 2300.0, 25.0, "der-s2") // II 层，应较老但实测年轻
        val c = SequenceConstraint("LAB-0006", "LAB-0005", "stratum II below I")
        val r = SequenceChecker.check("src-seq", c, "LAB-0006", s2, "LAB-0005", s1)
        assertTrue(r.conflict)
        assertTrue(r.probBeforeOlderThanAfter < 0.5)
        assertTrue(r.candidates.size >= 3)
        // 候选是调整建议而非改写：不含任何新测量值
        assertTrue(r.candidates.all { it.kind in listOf("flag-outlier", "relax-constraint") })
        assertFalse(r.candidates.first().adopted)
    }

    @Test
    fun `consistent sequence is accepted`() {
        val old = cal("LAB-OLD", 2800.0, 25.0, "der-old")
        val young = cal("LAB-YNG", 2000.0, 60.0, "der-yng")
        val c = SequenceConstraint("LAB-OLD", "LAB-YNG", "normal order")
        val r = SequenceChecker.check("src-seq2", c, "LAB-OLD", old, "LAB-YNG", young)
        assertFalse(r.conflict)
        assertTrue(r.probBeforeOlderThanAfter > 0.5)
        assertTrue(r.candidates.isEmpty())
    }

    @Test
    fun `unknown calibration input keeps sequence unknown rather than forced verdict`() {
        val unknown = "der-x" to CalibrationResult(CALIB_ALGORITHM_VERSION, "fp", "m", "c", null,
            "n", 0.0, 0.0, 1.0, emptyList(), emptyList(), emptyList(), emptyList(),
            listOf("zero"), true, "no overlap")
        val ok = cal("LAB-YNG", 2000.0, 60.0, "der-y")
        val r = SequenceChecker.check("src-seq3", SequenceConstraint("X", "Y", ""), "X", unknown, "Y", ok)
        assertTrue(r.probBeforeOlderThanAfter.isNaN())
        assertFalse(r.conflict)
        assertTrue(r.diagnostics.any { it.startsWith("unknown-input") })
    }
}
