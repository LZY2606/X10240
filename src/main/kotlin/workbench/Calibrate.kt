package workbench

import java.security.MessageDigest
import kotlin.math.*

object Engine {
    const val ALGO_VERSION = "calib-1.2.0"

    fun sha256(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    fun fingerprint(measurement: Measurement, curve: CurveVersion, offset: OffsetModel, era: EraSystem): String =
        sha256("$ALGO_VERSION|${measurement.id}@${measurement.version}:${measurement.ageBp}:${measurement.sigma}|" +
            "${curve.id}@${curve.version}|${offset.id}@${offset.version}:${offset.deltaR}:${offset.sigma}:${offset.distribution}|${era.system}")

    private fun normalPdf(x: Double, mu: Double, sigma: Double): Double {
        val z = (x - mu) / sigma
        return exp(-0.5 * z * z) / (sigma * sqrt(2.0 * PI))
    }

    /** Linear interpolation on the fixed curve grid; clamps at boundaries and reports it. */
    fun interpolate(curve: CurveVersion, calBp: Int): Pair<Pair<Double, Double>, Boolean> {
        val pts = curve.points.sortedBy { it.calBp }
        if (calBp <= pts.first().calBp) return (pts.first().c14Age to pts.first().sigma) to true
        if (calBp >= pts.last().calBp) return (pts.last().c14Age to pts.last().sigma) to true
        for (i in 0 until pts.size - 1) {
            val a = pts[i]; val b = pts[i + 1]
            if (calBp in a.calBp..b.calBp) {
                val t = (calBp - a.calBp).toDouble() / (b.calBp - a.calBp)
                return ((a.c14Age + t * (b.c14Age - a.c14Age)) to (a.sigma + t * (b.sigma - a.sigma))) to false
            }
        }
        error("unreachable")
    }

    fun calibrate(
        id: String, measurement: Measurement, curve: CurveVersion,
        offset: OffsetModel, era: EraSystem
    ): Calibration {
        // Reservoir offset distribution convolved into the measurement (normal assumption).
        val effAge = measurement.ageBp - offset.deltaR
        val effSigma = sqrt(measurement.sigma.pow(2) + offset.sigma.pow(2))
        val pts = curve.points.sortedBy { it.calBp }
        val gridFrom = pts.first().calBp
        val gridTo = pts.last().calBp
        var boundary = false
        val raw = DoubleArray(gridTo - gridFrom + 1)
        for (calBp in gridFrom..gridTo) {
            val (interp, clamped) = interpolate(curve, calBp)
            if (clamped) boundary = true
            val (mu, curveSigma) = interp
            val sigma = sqrt(curveSigma * curveSigma + effSigma * effSigma)
            raw[calBp - gridFrom] = normalPdf(effAge, mu, sigma)
        }
        // Trapezoid normalisation: reproducible for a fixed curve version.
        var norm = 0.0
        for (i in raw.indices) norm += if (i == 0 || i == raw.lastIndex) raw[i] / 2 else raw[i]
        val density = raw.map { it / norm }
        val peaks = findPeaks(density, gridFrom)
        val intervals = hpdIntervals(density, gridFrom, listOf(0.6827, 0.9545), boundary)
        return Calibration(
            id = id, measurementId = measurement.id,
            curveRef = "${curve.id}@${curve.version}", offsetRef = "${offset.id}@${offset.version}",
            eraRef = era.id, algoVersion = ALGO_VERSION,
            paramFingerprint = fingerprint(measurement, curve, offset, era),
            sources = listOf(
                "${measurement.id}@${measurement.version}", "${curve.id}@${curve.version}",
                "${offset.id}@${offset.version}", era.id
            ),
            gridFrom = gridFrom, gridTo = gridTo, density = density,
            peaks = peaks, intervals = intervals, boundary = boundary,
            effectiveAge = effAge, effectiveSigma = effSigma
        )
    }

    fun findPeaks(density: List<Double>, gridFrom: Int): List<Peak> {
        val max = density.max()
        val peaks = mutableListOf<Peak>()
        for (i in 1 until density.size - 1) {
            if (density[i] >= density[i - 1] && density[i] > density[i + 1] && density[i] > max * 0.05) {
                if (peaks.isNotEmpty() && i - (peaks.last().calBpCenter - gridFrom) < 15) {
                    if (density[i] > peaks.last().height) peaks[peaks.lastIndex] =
                        Peak(i + gridFrom, density[i], bceCeLabel(i + gridFrom))
                } else peaks.add(Peak(i + gridFrom, density[i], bceCeLabel(i + gridFrom)))
            }
        }
        return peaks
    }

    /** Highest-posterior-density intervals with full trapezoid integration evidence. */
    fun hpdIntervals(
        density: List<Double>, gridFrom: Int, levels: List<Double>, boundary: Boolean
    ): List<IntervalEvidence> {
        val result = mutableListOf<IntervalEvidence>()
        var index = 0
        for (level in levels) {
            val order = density.indices.sortedByDescending { density[it] }
            val selected = BooleanArray(density.size)
            var acc = 0.0
            for (i in order) {
                acc += density[i]
                selected[i] = true
                if (acc >= level) break
            }
            var i = 0
            while (i < density.size) {
                if (!selected[i]) { i++; continue }
                var j = i
                while (j + 1 < density.size && selected[j + 1]) j++
                val steps = mutableListOf<EvidenceStep>()
                var cumulative = 0.0
                for (k in i..j) {
                    val left = if (k == 0) density[k] / 2 else density[k]
                    val right = if (k == density.lastIndex) density[k] / 2 else density[k]
                    val w = when (k) { 0, density.lastIndex -> density[k] / 2; else -> density[k] }
                    cumulative += w
                    steps.add(EvidenceStep(k + gridFrom, density[k], w, cumulative))
                }
                val from = i + gridFrom
                val to = j + gridFrom
                val touchesBoundary = boundary && (i == 0 || j == density.lastIndex)
                result.add(
                    IntervalEvidence(
                        index = index++, fromCalBp = from, toCalBp = to, level = level,
                        probability = cumulative, fromLabel = bceCeLabel(from), toLabel = bceCeLabel(to),
                        steps = steps,
                        note = if (touchesBoundary) "interval reaches curve grid boundary; interpolation clamped at edge point"
                        else "trapezoid integration over fixed grid, normalised to 1.0"
                    )
                )
                i = j + 1
            }
        }
        return result
    }

    /** Probability that sample A is older (larger cal BP) than sample B, from two densities on the same grid. */
    fun probOlder(a: Calibration, b: Calibration): Double {
        val from = max(a.gridFrom, b.gridFrom)
        val to = min(a.gridTo, b.gridTo)
        var p = 0.0
        var cumB = 0.0
        val bCum = DoubleArray(to - from + 1)
        for (t in from..to) {
            cumB += b.density[t - b.gridFrom]
            bCum[t - from] = cumB
        }
        for (t in from..to) {
            val younger = if (t - 1 >= from) bCum[t - 1 - from] else 0.0
            p += a.density[t - a.gridFrom] * younger
        }
        return p
    }
}
