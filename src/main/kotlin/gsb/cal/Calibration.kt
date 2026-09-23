package gsb.cal

import gsb.model.*
import java.security.MessageDigest
import kotlin.math.*

/** Piecewise-linear calibration curve over calBP knots.
 *  Interpolation is exact at knots and linear between them; outside the knot
 *  range the endpoint value is held constant and flagged as extrapolated, so
 *  boundary behaviour is deterministic and reproducible for a fixed curve
 *  version. */
class Curve(val def: CurveDef) {
    private val knots = def.knots.sortedBy { it.calBp }

    data class Eval(val c14: Double, val sigma: Double, val extrapolated: Boolean)

    val startBp: Double get() = knots.first().calBp
    val endBp: Double get() = knots.last().calBp

    fun eval(t: Double): Eval {
        if (t <= knots.first().calBp) {
            val k = knots.first()
            return Eval(k.c14Age, k.sigma, t < k.calBp)
        }
        if (t >= knots.last().calBp) {
            val k = knots.last()
            return Eval(k.c14Age, k.sigma, t > k.calBp)
        }
        var lo = 0
        var hi = knots.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) / 2
            if (knots[mid].calBp <= t) lo = mid else hi = mid
        }
        val a = knots[lo]
        val b = knots[hi]
        if (t == a.calBp) return Eval(a.c14Age, a.sigma, false)
        val f = (t - a.calBp) / (b.calBp - a.calBp)
        return Eval(
            a.c14Age + f * (b.c14Age - a.c14Age),
            a.sigma + f * (b.sigma - a.sigma),
            false,
        )
    }
}

object Era {
    /** calBP -> astronomical year label. No year zero: 1950 BP = 1 BCE,
     *  1949 BP = 1 CE. Intervals crossing the era boundary are labelled with
     *  mixed BCE/CE endpoints. */
    fun bpToEra(bp: Double): String {
        val astro = 1950 - bp.toInt()
        return if (astro > 0) "$astro CE" else "${1 - astro} BCE"
    }

    fun intervalLabel(olderBp: Double, youngerBp: Double): String =
        "${bpToEra(olderBp)}–${bpToEra(youngerBp)}"
}

object Calibrator {
    const val ALG_VERSION = "cal-1.0.0"

    fun fingerprint(vararg parts: Any?): String {
        val md = MessageDigest.getInstance("SHA-256")
        val canonical = parts.joinToString("|") { it.toString() }
        return md.digest(canonical.toByteArray()).joinToString("") { "%02x".format(it) }.substring(0, 16)
    }

    private fun normalPdf(x: Double, mu: Double, sigma: Double): Double {
        val z = (x - mu) / sigma
        return exp(-0.5 * z * z) / (sigma * sqrt(2.0 * PI))
    }

    /** Calibrate a 14C measurement against [curve]. The reservoir offset
     *  distribution N(deltaR, deltaRSigma) shifts the measured age and widens
     *  its uncertainty; the result is a normalised multimodal density over a
     *  fixed calBP grid. It is never collapsed to a single mean year. */
    fun calibrate(
        sampleId: String,
        age: Double,
        sigmaM: Double,
        deltaR: Double,
        deltaRSigma: Double,
        curve: Curve,
        eraSystem: String,
        offsetName: String,
        step: Double = 1.0,
    ): CalibrationPayload {
        val mu = age - deltaR
        val sigmaEff = sqrt(sigmaM * sigmaM + deltaRSigma * deltaRSigma)
        val n = ((curve.endBp - curve.startBp) / step).toInt() + 1
        val raw = DoubleArray(n)
        var extrapolated = false
        for (i in 0 until n) {
            val t = curve.startBp + i * step
            val ev = curve.eval(t)
            if (ev.extrapolated) extrapolated = true
            val s = sqrt(sigmaEff * sigmaEff + ev.sigma * ev.sigma)
            raw[i] = normalPdf(ev.c14, mu, s)
        }
        val sum = raw.sum()
        val densities = raw.map { it / sum }
        val modes = findModes(curve.startBp, step, densities)
        val hpd68 = hpd(curve.startBp, step, densities, 0.6827, eraSystem)
        val hpd95 = hpd(curve.startBp, step, densities, 0.9545, eraSystem)
        val diagnostics = mutableListOf<String>()
        if (extrapolated) diagnostics += "grid extends beyond curve knots; endpoint values held constant"
        return CalibrationPayload(
            sampleId = sampleId,
            offsetName = offsetName,
            eraSystem = eraSystem,
            gridStartBp = curve.startBp,
            gridStep = step,
            gridCount = n,
            densities = densities,
            modes = modes,
            hpd68 = hpd68,
            hpd95 = hpd95,
            extrapolated = extrapolated,
            diagnostics = diagnostics,
        )
    }

    /** Local maxima with prominence >= 5% of the global peak; mass is the
     *  probability of the mode's basin (region between adjacent valleys). */
    fun findModes(start: Double, step: Double, p: List<Double>): List<ModePeak> {
        if (p.size < 3) return emptyList()
        val maxP = p.max()
        val peaks = mutableListOf<Int>()
        var i = 1
        while (i < p.size - 1) {
            if (p[i] > p[i - 1] && p[i] >= p[i + 1]) {
                var j = i
                while (j + 1 < p.size - 1 && p[j + 1] == p[i]) j++
                peaks += (i + j) / 2
                i = j + 1
            } else i++
        }
        val kept = peaks.filter { p[it] >= 0.05 * maxP }
        // valley boundaries between consecutive peaks
        val bounds = IntArray(kept.size + 1)
        bounds[0] = 0
        bounds[kept.size] = p.size - 1
        for (k in 0 until kept.size - 1) {
            var minIdx = kept[k]
            for (q in kept[k]..kept[k + 1]) if (p[q] < p[minIdx]) minIdx = q
            bounds[k + 1] = minIdx
        }
        return kept.mapIndexed { k, idx ->
            var mass = 0.0
            for (i in bounds[k]..bounds[k + 1]) mass += p[i]
            ModePeak(calBp = start + idx * step, density = p[idx], mass = mass)
        }.filter { it.mass > 0.005 }.sortedByDescending { it.density }
    }

    /** Highest-posterior-density interval set at [level]: possibly several
     *  disjoint calendar intervals listed side by side, masses included. */
    fun hpd(start: Double, step: Double, p: List<Double>, level: Double, eraSystem: String): List<HpdInterval> {
        val order = p.indices.sortedByDescending { p[it] }
        var acc = 0.0
        var threshold = 0.0
        for (i in order) {
            acc += p[i]
            threshold = p[i]
            if (acc >= level) break
        }
        val intervals = mutableListOf<HpdInterval>()
        var i = 0
        while (i < p.size) {
            if (p[i] >= threshold - 1e-15) {
                var j = i
                var mass = 0.0
                while (j < p.size && p[j] >= threshold - 1e-15) {
                    mass += p[j]
                    j++
                }
                val loBp = start + i * step        // younger end
                val hiBp = start + (j - 1) * step  // older end
                val label = if (eraSystem == "BCE_CE") Era.intervalLabel(hiBp, loBp)
                            else "${hiBp.toInt()}–${loBp.toInt()} cal BP"
                intervals += HpdInterval(loBp = loBp, hiBp = hiBp, mass = mass, level = level, label = label)
                i = j
            } else i++
        }
        return intervals
    }

    /** Expandable integration evidence for one interval: per-segment
     *  trapezoid contributions and running cumulative mass. */
    fun intervalEvidence(payload: CalibrationPayload, loBp: Double, hiBp: Double): List<Triple<Double, Double, Double>> {
        val rows = mutableListOf<Triple<Double, Double, Double>>()
        var cum = 0.0
        for (i in 0 until payload.gridCount) {
            val t = payload.calBpAt(i)
            if (t < loBp - 1e-9 || t > hiBp + 1e-9) continue
            val contrib = if (i == 0) payload.densities[i] * payload.gridStep
            else (payload.densities[i - 1] + payload.densities[i]) / 2.0 * payload.gridStep
            cum += contrib
            rows += Triple(t, payload.densities[i], cum)
        }
        return rows
    }
}
