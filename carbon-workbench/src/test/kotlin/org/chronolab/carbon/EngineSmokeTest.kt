package org.chronolab.carbon

import org.chronolab.carbon.calib.CalibrationConfig
import org.chronolab.carbon.calib.CalibrationEngine
import org.chronolab.carbon.calib.ReservoirComponent
import org.chronolab.carbon.calib.ReservoirModel
import org.chronolab.carbon.calib.SyntheticCurves
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class EngineSmokeTest {
    @Test
    fun multimodalAndReproducible() {
        val curve = SyntheticCurves.v1()
        val cfg = CalibrationConfig(1500.0, 2600.0, 1.0)
        // 先找一个能产生双峰的年龄
        var found: Double? = null
        for (age in 1950..2250) {
            val out = CalibrationEngine.calibrate(age.toDouble(), 35.0, curve, ReservoirModel.NONE, cfg, "smoke")!!
            if (out.modes.size >= 2) { found = age.toDouble(); break }
        }
        assertNotNull(found, "合成曲线必须对至少一个年龄产生双峰")
        val a = CalibrationEngine.calibrate(found!!, 35.0, curve, ReservoirModel.NONE, cfg, "smoke")!!
        val b = CalibrationEngine.calibrate(found, 35.0, curve, ReservoirModel.NONE, cfg, "smoke")!!
        assertTrue(a.multimodal)
        assertEquals(a.density.toList(), b.density.toList())
        assertEquals(1.0, a.totalMass, 1e-12)
        println("multimodal at 14C age=$found modes=${a.modes.map { it.label }} iv95=${a.intervals95.size}")
    }

    @Test
    fun reservoirMixtureIsNotCollapsed() {
        val curve = SyntheticCurves.v1()
        val cfg = CalibrationConfig(1500.0, 2600.0, 2.0)
        val mixture = ReservoirModel(
            "r", "marine", 0.0,
            listOf(ReservoirComponent(120.0, 20.0, 0.6), ReservoirComponent(320.0, 40.0, 0.4))
        )
        val out = CalibrationEngine.calibrate(2050.0, 30.0, curve, mixture, cfg, "m")
        assertNotNull(out)
        assertTrue(out.totalMass == 1.0)
    }

    @Test
    fun bceCeBoundary() {
        assertEquals("1 BCE", org.chronolab.carbon.model.CalendarEra.label(1950.0))
        assertEquals("1 CE", org.chronolab.carbon.model.CalendarEra.label(1949.0))
        assertEquals("50 BCE", org.chronolab.carbon.model.CalendarEra.label(1999.0))
    }
}
