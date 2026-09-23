package gsb

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class CalibrationTest {
    private val curve2020 = Curves.get("INTCAL-SYN", "2020")!!
    private val curve2024 = Curves.get("INTCAL-SYN", "2024")!!
    private val noOffset = OffsetDistribution("normal", 0.0, 0.0)

    @Test
    fun `曲线多峰不被压成平均年`() {
        val r = Calib.calibrate(curve2020, 2450.0, 35.0, noOffset)
        assertTrue(r.peaks.size >= 2, "多峰：${r.peaks}")
        assertTrue(r.intervals95.size >= 2, "并列 95.4% 区间：${r.intervals95}")
        val total = r.density.sum() * r.step
        assertEquals(1.0, total, 1e-9, "归一化总概率")
        val covered = r.intervals95.sumOf { it.prob }
        assertTrue(covered in 0.90..0.96, "95.4% HPD 覆盖 $covered")
        // 并列区间不相交
        val sorted = r.intervals95.sortedBy { it.loBp }
        for (i in 1 until sorted.size) assertTrue(sorted[i].loBp > sorted[i - 1].hiBp, "区间不合并")
    }

    @Test
    fun `边界插值与归一化在固定版本下可重现`() {
        val (a0, s0) = curve2020.interp(0.0)!!
        val (a5, _) = curve2020.interp(5.0)!!
        val (a25, s25) = curve2020.interp(2.5)!!
        assertEquals((a0 + a5) / 2, a25, 1e-9, "节点间线性插值")
        assertEquals((s0 + curve2020.interp(5.0)!!.second) / 2, s25, 1e-9)
        assertNull(curve2020.interp(-1.0), "表外返回 null（边界截断）")
        assertNull(curve2020.interp(5001.0))
        val r1 = Calib.calibrate(curve2020, 4440.0, 25.0, noOffset)
        val r2 = Calib.calibrate(curve2020, 4440.0, 25.0, noOffset)
        assertEquals(r1.norm, r2.norm, 0.0, "归一化常数可重现")
        assertEquals(r1.density, r2.density, "密度逐格可重现")
        assertTrue(r1.truncatedAtMin, "现代端边界截断")
        assertFalse(r1.truncatedAtMax)
    }

    @Test
    fun `储库偏移分布改变校准结果且混合分布等于分量加权和`() {
        val normal = OffsetDistribution("normal", 180.0, 40.0)
        val mixture = OffsetDistribution("mixture", components = listOf(
            OffsetComponent(0.7, 160.0, 30.0), OffsetComponent(0.3, 260.0, 25.0)))
        val rNone = Calib.calibrate(curve2020, 2850.0, 40.0, noOffset)
        val rNorm = Calib.calibrate(curve2020, 2850.0, 40.0, normal)
        val rMix = Calib.calibrate(curve2020, 2850.0, 40.0, mixture)
        assertNotEquals(rNone.density, rNorm.density, "偏移改变分布")
        assertNotEquals(rNorm.density, rMix.density, "分布形状（正态 vs 混合）改变结果")
        // 两个完全相同分量的混合分布，等价于单个正态偏移
        val sameMix = OffsetDistribution("mixture", components = listOf(
            OffsetComponent(0.5, 100.0, 20.0), OffsetComponent(0.5, 100.0, 20.0)))
        val rSame = Calib.calibrate(curve2020, 2850.0, 40.0, sameMix)
        val rNorm2 = Calib.calibrate(curve2020, 2850.0, 40.0, OffsetDistribution("normal", 100.0, 20.0))
        assertEquals(rNorm2.norm, rSame.norm, 0.0, "归一化常数相同")
        var rel = 0.0
        for (i in rNorm2.density.indices) {
            if (rNorm2.density[i] > 1e-12) rel = maxOf(rel, kotlin.math.abs(rNorm2.density[i] - rSame.density[i]) / rNorm2.density[i])
        }
        assertTrue(rel < 1e-12, "等同分量混合≈正态（相对差 $rel）；混合权重被正确应用")
    }

    @Test
    fun `BCE CE 跨界按天文纪年标注`() {
        assertEquals("1 CE", Era.label(1949.0))
        assertEquals("1 BCE", Era.label(1950.0))
        assertEquals("2 BCE", Era.label(1951.0))
        assertEquals("100 BCE", Era.label(2049.0))
        val r = Calib.calibrate(curve2020, 2905.0, 30.0, noOffset)
        assertTrue(r.intervals95.any { Era.crossesEraBoundary(it.loBp, it.hiBp) },
            "M4 的 95.4% 区间跨越 BCE/CE 边界: ${r.intervals95}")
    }

    @Test
    fun `曲线升版改变结果且两版本各自可重现`() {
        val r20 = Calib.calibrate(curve2020, 2450.0, 35.0, noOffset)
        val r24 = Calib.calibrate(curve2024, 2450.0, 35.0, noOffset)
        assertNotEquals(r20.norm, r24.norm, "升版改变归一化")
        assertNotEquals(r20.intervals95, r24.intervals95, "升版改变区间")
        assertTrue(r24.intervals95.size >= 2, "升版后并列区间仍保留")
    }

    @Test
    fun `层序概率 P 对称性`() {
        val a = Calib.calibrate(curve2020, 2450.0, 35.0, noOffset)
        val b = Calib.calibrate(curve2020, 2360.0, 30.0, noOffset)
        val p = Calib.probOlder(a, b)
        assertTrue(p in 0.0..1.0)
        assertEquals(1.0, p + Calib.probOlder(b, a), 0.01, "P(A>B)+P(B>A)≈1（离散网格有并列格点）")
        assertTrue(p < 0.5, "M1(2450) 比 M2(2360) 更年轻的概率更大 → 层序矛盾")
    }

    @Test
    fun `积分证据累积到区间概率`() {
        val r = Calib.calibrate(curve2020, 2450.0, 35.0, noOffset)
        for (iv in r.intervals95) {
            val ev = r.evidence.first { it.intervalId == iv.id }
            assertEquals(iv.prob, ev.totalProb, 1e-9, "证据总概率=区间概率")
            assertEquals(ev.totalProb, ev.rows.last().cumulative, 1e-9, "末行累积=总概率")
        }
    }
}
