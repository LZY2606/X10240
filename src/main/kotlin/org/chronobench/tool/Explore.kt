package org.chronobench.tool

import org.chronobench.casepack.BundledCurves
import org.chronobench.domain.CalibrationEngine
import org.chronobench.domain.CalibrationRequest
import org.chronobench.domain.MeasurementRef
import org.chronobench.domain.ReservoirHypothesis

fun main() {
    val v1 = BundledCurves.require("synthetic-intcal", "v1")
    val v2 = BundledCurves.require("synthetic-intcal", "v2")
    val h0 = ReservoirHypothesis("h0", "none", 0.0, 0.0)
    data class Case(val id: String, val age: Double, val sig: Double, val dr: Double, val drs: Double)
    val cases = listOf(
        Case("m1", 1890.0, 30.0, 0.0, 0.0),
        Case("m2", 1940.0, 70.0, 0.0, 0.0),
        Case("m3", 2200.0, 40.0, 120.0, 50.0),
        Case("m3-h2", 2200.0, 40.0, 80.0, 40.0),
        Case("m3-h0", 2200.0, 40.0, 0.0, 0.0),
        Case("m4", 2780.0, 35.0, 0.0, 0.0),
        Case("m5", 1925.0, 25.0, 0.0, 0.0)
    )
    for (curve in listOf(v1, v2)) {
        println("=== ${curve.id}@${curve.version} fp=${curve.fingerprint} range ${curve.minCalBP}-${curve.maxCalBP}")
        for (c in cases) {
            val h = ReservoirHypothesis(c.id + "-h", c.id, c.dr, c.drs)
            val req = CalibrationRequest(MeasurementRef(c.id, c.age, c.sig), curve.id, curve.version, curve.fingerprint, h)
            val r = CalibrationEngine.calibrate(req, curve)
            val i68 = r.intervals68.joinToString("; ") { "%.1f-%.1f p=%.3f%s".format(it.startCalBP, it.endCalBP, it.probability, if (it.tieGroupId != null) "*" else "") }
            val i95 = r.intervals95.joinToString("; ") { "%.1f-%.1f p=%.3f".format(it.startCalBP, it.endCalBP, it.probability) }
            println("${c.id.padEnd(7)} modes=${r.modes.size} 68=[$i68] 95=[$i95] diag=${r.diagnostics.joinToString { it.code }}")
        }
    }
}
