package org.chronobench.casepack

import org.chronobench.domain.CalibrationCurve
import org.chronobench.domain.CurveKnot
import org.chronobench.domain.SyntheticCurves
import java.io.BufferedReader

object BundledCurves {
    private val cache = linkedMapOf<String, CalibrationCurve>()

    init {
        register(SyntheticCurves.v1())
        register(SyntheticCurves.v2())
    }

    fun register(curve: CalibrationCurve) {
        cache["${curve.id}@${curve.version}"] = curve
    }

    fun all(): List<CalibrationCurve> = cache.values.toList()
    fun get(id: String, version: String): CalibrationCurve? = cache["$id@$version"]
    fun require(id: String, version: String): CalibrationCurve =
        get(id, version) ?: error("unknown curve $id@$version (offline bundle only)")

    /** Render the shipped CSV form (calBP,c14BP,sigma) for the offline case pack material. */
    fun toCsv(curve: CalibrationCurve): String = buildString {
        appendLine("# ${curve.displayName}")
        appendLine("# id=${curve.id} version=${curve.version} fingerprint=${curve.fingerprint}")
        appendLine("calBP,c14BP,sigma")
        for (k in curve.knots) appendLine("%s,%s,%s".format(num(k.calBP), num(k.c14BP), num(k.sigma)))
    }
    private fun num(v: Double): String =
        java.math.BigDecimal.valueOf(v).stripTrailingZeros().toPlainString()

    fun parseCsv(id: String, version: String, source: String, supersedes: String? = null): CalibrationCurve {
        val text = requireNotNull(readResource(source.substringAfter("casepack://"))) {
            "missing bundled curve resource $source"
        }
        val knots = text.lineSequence()
            .filter { it.isNotBlank() && !it.startsWith("#") && !it.startsWith("calBP") }
            .map { line ->
                val (a, b, c) = line.split(",")
                CurveKnot(a.trim().toDouble(), b.trim().toDouble(), c.trim().toDouble())
            }
            .toList()
        return CalibrationCurve(
            id = id,
            version = version,
            displayName = "Synthetic IntCal-like curve (offline case pack, $version)",
            knots = knots,
            source = source,
            fingerprint = CalibrationCurve.fingerprintOf(id, version, knots),
            supersedes = supersedes
        )
    }

    private fun readResource(path: String): String? =
        javaClass.getResourceAsStream("/$path")?.bufferedReader()?.use(BufferedReader::readText)
}
