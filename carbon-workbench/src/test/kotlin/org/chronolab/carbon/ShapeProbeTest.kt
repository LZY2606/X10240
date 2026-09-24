package org.chronolab.carbon

import org.chronolab.carbon.calib.CalibrationConfig
import org.chronolab.carbon.calib.CalibrationEngine
import org.chronolab.carbon.calib.ReservoirComponent
import org.chronolab.carbon.calib.ReservoirModel
import org.chronolab.carbon.calib.SyntheticCurves
import org.junit.jupiter.api.Test

class ShapeProbeTest {
    @Test
    fun probe() {
        val v1 = SyntheticCurves.v1(); val v2 = SyntheticCurves.v2()
        val cfg = CalibrationConfig(1500.0, 2600.0, 1.0)
        println("=== multimodal ages under v1 (no reservoir) ===")
        val hits = mutableListOf<Int>()
        for (age in 1750..2350) {
            val out = CalibrationEngine.calibrate(age.toDouble(), 35.0, v1, ReservoirModel.NONE, cfg, "p")!!
            if (out.modes.size >= 2) hits += age
        }
        // 压缩成区间
        val groups = mutableListOf<IntRange>()
        hits.forEach { h -> if (groups.isEmpty() || h > groups.last.last + 1) groups += h..h else groups[groups.size-1] = groups.last.first..h }
        println("multimodal age ranges: $groups")
        // 在 1880-1935 局部窗口附近的年龄：找一个 v1 vs v2 结果不同的
        println("=== v2 localized bump effect ===")
        for (age in 1820..1960 step 4) {
            val a = CalibrationEngine.calibrate(age.toDouble(), 35.0, v1, ReservoirModel.NONE, cfg, "p")!!
            val b = CalibrationEngine.calibrate(age.toDouble(), 35.0, v2, ReservoirModel.NONE, cfg, "p")!!
            val diff = a.density.zip(b.density).maxOf { kotlin.math.abs(it.first - it.second) }
            val ivs = b.intervals95.size to a.intervals95.size
            if (diff > 1e-4) println("age=$age maxDensityDiff=${"%.5f".format(diff)} iv95(v1,v2)=${ivs.second},${ivs.first} cross=${b.crossesBceCe}")
        }
        println("=== reservoir mixture ages ===")
        val mix = ReservoirModel("m","marine",0.0,listOf(ReservoirComponent(140.0,25.0,0.55), ReservoirComponent(300.0,45.0,0.45)))
        for (age in 1900..2200 step 20) {
            val out = CalibrationEngine.calibrate(age.toDouble(), 30.0, v1, mix, cfg, "p")!!
            println("marine age=$age modes=${out.modes.size} iv95=${out.intervals95.size} cross=${out.crossesBceCe}")
        }
    }
}
