package gsb.cal

import gsb.model.CalibrationPayload
import gsb.model.StratCandidate
import kotlin.random.Random

object Stratigraphy {
    const val ALG_VERSION = "strat-1.0.0"
    const val CONFLICT_THRESHOLD = 0.90

    /** P(older.calBp > younger.calBp) from the two normalised posterior
     *  grids, by deterministic double summation (no sampling noise). */
    fun orderProbability(older: CalibrationPayload, younger: CalibrationPayload): Double {
        // cumulative mass of younger below each older grid point
        val yc = DoubleArray(younger.gridCount)
        var acc = 0.0
        for (i in 0 until younger.gridCount) {
            acc += younger.densities[i]
            yc[i] = acc
        }
        fun youngerCdfBelow(t: Double): Double {
            if (t <= younger.gridStartBp) return 0.0
            val idx = ((t - younger.gridStartBp) / younger.gridStep).toInt()
            if (idx >= younger.gridCount - 1) return 1.0
            return yc[idx]
        }
        var p = 0.0
        for (i in 0 until older.gridCount) {
            p += older.densities[i] * youngerCdfBelow(older.calBpAt(i))
        }
        return p
    }

    private fun truncatedMass(payload: CalibrationPayload, loBp: Double, hiBp: Double): Double {
        var m = 0.0
        for (i in 0 until payload.gridCount) {
            val t = payload.calBpAt(i)
            if (t >= loBp - 1e-9 && t <= hiBp + 1e-9) m += payload.densities[i]
        }
        return m
    }

    /** A stratigraphic conflict never rewrites measurements: it produces
     *  candidate adjustments (truncation windows applied to posteriors).
     *  When the data do not decide uniquely, every legal candidate is kept. */
    fun candidates(
        constraintId: String,
        older: CalibrationPayload,
        younger: CalibrationPayload,
        seed: Int = 42,
    ): List<StratCandidate> {
        val out = mutableListOf<StratCandidate>()
        // Candidate 1: truncate the older sample's posterior below the
        // younger sample's 95% youngest bound.
        val yLo = younger.hpd95.minOf { it.loBp }
        val w1 = mapOf(
            older.sampleId to (yLo to older.gridStartBp + (older.gridCount - 1) * older.gridStep),
        )
        val m1 = truncatedMass(older, yLo, Double.MAX_VALUE)
        if (m1 > 0.001) out += StratCandidate(
            candidateId = "$constraintId-C1",
            method = "truncate-older",
            description = "Restrict ${older.sampleId} posterior to calBP >= ${yLo.toInt()} (younger bound of ${younger.sampleId}); measurement untouched",
            windows = w1,
            orderProbability = 1.0,
        )
        // Candidate 2: truncate the younger sample's posterior above the
        // older sample's 95% oldest bound.
        val oHi = older.hpd95.maxOf { it.hiBp }
        val w2 = mapOf(
            younger.sampleId to (younger.gridStartBp to oHi),
        )
        val m2 = truncatedMass(younger, Double.NEGATIVE_INFINITY, oHi)
        if (m2 > 0.001) out += StratCandidate(
            candidateId = "$constraintId-C2",
            method = "truncate-younger",
            description = "Restrict ${younger.sampleId} posterior to calBP <= ${oHi.toInt()} (older bound of ${older.sampleId}); measurement untouched",
            windows = w2,
            orderProbability = 1.0,
        )
        // Candidate 3: fixed-seed rejection sampling of ordered pairs.
        val rng = Random(seed)
        val oCdf = cdf(older)
        val yCdf = cdf(younger)
        var accepted = 0
        val draws = 20000
        var oMin = Double.MAX_VALUE; var oMax = Double.MIN_VALUE
        var yMin = Double.MAX_VALUE; var yMax = Double.MIN_VALUE
        repeat(draws) {
            val o = sample(oCdf, older, rng)
            val y = sample(yCdf, younger, rng)
            if (o > y) {
                accepted++
                if (o < oMin) oMin = o; if (o > oMax) oMax = o
                if (y < yMin) yMin = y; if (y > yMax) yMax = y
            }
        }
        if (accepted > 0) out += StratCandidate(
            candidateId = "$constraintId-C3",
            method = "joint-rejection",
            description = "Fixed-seed($seed) rejection sampling: $accepted/$draws ordered pairs kept; joint posterior windows reported",
            windows = mapOf(
                older.sampleId to (oMin to oMax),
                younger.sampleId to (yMin to yMax),
            ),
            orderProbability = 1.0,
        )
        return out
    }

    private fun cdf(p: CalibrationPayload): DoubleArray {
        val c = DoubleArray(p.gridCount)
        var acc = 0.0
        for (i in 0 until p.gridCount) { acc += p.densities[i]; c[i] = acc }
        return c
    }

    private fun sample(cdf: DoubleArray, p: CalibrationPayload, rng: Random): Double {
        val u = rng.nextDouble()
        val idx = cdf.binarySearch(u)
        val i = if (idx >= 0) idx else -idx - 1
        return p.calBpAt(i.coerceIn(0, p.gridCount - 1))
    }
}
