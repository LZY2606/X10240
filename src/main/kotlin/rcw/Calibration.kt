package rcw

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

object Calibration {

    const val GRID_STEP = 1.0
    const val HPD_68 = 0.6827
    const val HPD_95 = 0.9545
    const val CONFLICT_THRESHOLD = 0.6827

    private val RAW_AGE_REGEX = Regex("""^\s*([0-9]+(?:\.[0-9]+)?)\s*[±\+\-]+\s*([0-9]+(?:\.[0-9]+)?)\s*(.*)$""")

    data class ParsedAge(val age: Double, val sigma: Double, val unit: String)

    /** Keeps raw representation in the source layer; normalization happens only here. */
    fun parseRawAge(raw: String, declaredUnit: String): ParsedAge? {
        val m = RAW_AGE_REGEX.matchEntire(raw) ?: return null
        val (ageStr, sigStr, unitStr) = m.destructured
        val unit = unitStr.trim().replace("\\s+".toRegex(), " ")
        val normalized = unit.uppercase().replace("CAL ", "")
        if (normalized.isNotEmpty() && normalized != "BP" && normalized != "BP." && normalized != "YR BP" && normalized != "14C YR BP") {
            return null
        }
        if (declaredUnit.uppercase() !in listOf("BP", "14C YR BP", "14C YR BP (LAB)", "BP (UNCAL)", "LAB BP")) {
            return null
        }
        return ParsedAge(ageStr.toDouble(), sigStr.toDouble(), unit)
    }

    /** astronomical year (no year zero) -> conventional BCE/CE label. calBP=1950 -> 1 BCE. */
    fun eraLabel(calBP: Double): String {
        val y = 1950 - Math.round(calBP)
        return if (y >= 1) "CE $y" else "BCE ${1 - y}"
    }

    data class Interp(val value: Double, val sigma: Double)

    class Curve(val xs: DoubleArray, val ys: DoubleArray, val ss: DoubleArray) {
        val minX get() = xs.first()
        val maxX get() = xs.last()

        fun interp(x: Double): Interp {
            if (x <= xs.first()) return Interp(ys.first(), ss.first())
            if (x >= xs.last()) return Interp(ys.last(), ss.last())
            var lo = 0
            var hi = xs.size - 1
            while (hi - lo > 1) {
                val mid = (lo + hi) ushr 1
                if (xs[mid] <= x) lo = mid else hi = mid
            }
            val f = (x - xs[lo]) / (xs[hi] - xs[lo])
            return Interp(ys[lo] + f * (ys[hi] - ys[lo]), ss[lo] + f * (ss[hi] - ss[lo]))
        }

        companion object {
            fun fromPoints(points: List<List<Double>>): Curve =
                Curve(
                    DoubleArray(points.size) { points[it][0] },
                    DoubleArray(points.size) { points[it][1] },
                    DoubleArray(points.size) { points[it][2] }
                )
        }
    }

    data class Posterior(
        val grid: DoubleArray,
        val density: DoubleArray,
        val likelihood: DoubleArray,
        val mass: Double,
        val truncatedLeft: Boolean,
        val truncatedRight: Boolean
    )

    fun posterior(curve: Curve, ageBP: Double, sigmaBP: Double,
                  offsetMean: Double = 0.0, offsetSigma: Double = 0.0): Posterior {
        val n = Math.round(curve.maxX - curve.minX + 1).toInt()
        val grid = DoubleArray(n) { curve.minX + it * GRID_STEP }
        val t = ageBP - offsetMean
        val sig = sqrt(sigmaBP * sigmaBP + offsetSigma * offsetSigma)
        val lk = DoubleArray(n)
        var maxL = 0.0
        for (i in 0 until n) {
            val (mu, sc) = curve.interp(grid[i])
            val s = sqrt(sig * sig + sc * sc)
            val z = (t - mu) / s
            val l = exp(-0.5 * z * z)
            lk[i] = l
            if (l > maxL) maxL = l
        }
        // trapezoidal normalization, fixed 1-year grid, fixed curve version => reproducible
        var mass = 0.0
        for (i in 0 until n - 1) mass += 0.5 * (lk[i] + lk[i + 1]) * GRID_STEP
        val density = DoubleArray(n) { lk[it] / mass }
        val edgeTol = 1e-3 * maxL
        return Posterior(
            grid, density, lk, mass,
            truncatedLeft = lk.first() > edgeTol,
            truncatedRight = lk.last() > edgeTol
        )
    }

    private fun trapezoid(a: DoubleArray, step: Double = GRID_STEP): Double {
        var s = 0.0
        for (i in 0 until a.size - 1) s += 0.5 * (a[i] + a[i + 1]) * step
        return s
    }

    /** Local maxima with prominence; never merges distinct modes into a single average. */
    fun peaks(p: Posterior): List<Int> {
        val d = p.density
        if (d.isEmpty()) return emptyList()
        var maxD = d.max()
        if (maxD <= 0.0) return emptyList()
        val candidates = mutableListOf<Int>()
        for (i in 1 until d.size - 1) {
            if (d[i] >= d[i - 1] && d[i] >= d[i + 1] && d[i] > 0.02 * maxD) candidates += i
        }
        if (d[0] > d[1] && d[0] > 0.02 * maxD) candidates.add(0, 0)
        if (d.last() > d[d.size - 2] && d.last() > 0.02 * maxD) candidates += d.size - 1
        val merged = mutableListOf<Int>()
        for (idx in candidates) {
            if (merged.isEmpty()) {
                merged += idx; continue
            }
            val prev = merged.last()
            var valley = minOf(d[prev], d[idx])
            for (j in prev..idx) if (d[j] < valley) valley = d[j]
            // merge only if modes are not clearly separated (valley stays above 60% of lower peak)
            if (valley > 0.6 * minOf(d[prev], d[idx])) {
                if (d[idx] > d[prev]) merged[merged.size - 1] = idx
            } else {
                merged += idx
            }
        }
        return merged
    }

    /** Highest posterior density intervals: contiguous calendar ranges above a density cutoff. */
    fun hpdIntervals(p: Posterior, target: Double, derivedId: String, tag: String): List<Triple<Int, Int, Double>> {
        val d = p.density
        val order = IntArray(d.size) { it }.sortedByDescending { d[it] }
        val selected = BooleanArray(d.size)
        var acc = 0.0
        var threshold = 0.0
        for (i in order) {
            selected[i] = true
            acc += d[i] * GRID_STEP
            threshold = d[i]
            if (acc >= target) break
        }
        val runs = mutableListOf<Triple<Int, Int, Double>>()
        var start = -1
        for (i in d.indices) {
            if (selected[i] && start < 0) start = i
            if ((!selected[i] || i == d.size - 1) && start >= 0) {
                val end = if (!selected[i]) i - 1 else i
                val part = DoubleArray(end - start + 1) { d[start + it] }
                val prob = trapezoid(part)
                runs += Triple(start, end, prob)
                start = -1
            }
        }
        return runs.sortedBy { it.first }
    }

    fun buildIntervals(p: Posterior, runs: List<Triple<Int, Int, Double>>,
                       derivedId: String, tag: String): List<IntervalResult> =
        runs.mapIndexed { k, (a, b, prob) ->
            val slice = p.density.sliceArray(a..b)
            val n = b - a + 1
            IntervalResult(
                intervalId = "$derivedId#$tag-$k",
                fromCalBP = p.grid[a],
                toCalBP = p.grid[b],
                fromEra = eraLabel(p.grid[a]),
                toEra = eraLabel(p.grid[b]),
                probability = prob,
                evidence = IntervalEvidence(
                    intervalId = "$derivedId#$tag-$k",
                    fromIdx = a,
                    toIdx = b,
                    gridStep = GRID_STEP,
                    pointCount = n,
                    trapezoidSum = trapezoid(slice),
                    totalMass = 1.0,
                    method = NORM_METHOD,
                    minDensity = slice.min(),
                    headLikelihoods = slice.take(5).map { round6(it) },
                    tailLikelihoods = slice.drop(maxOf(0, n - 5)).take(5).map { round6(it) }
                )
            )
        }

    /** Probability that sample A is older than sample B (larger calBP) under independent posteriors. */
    fun pOlderThan(a: Posterior, b: Posterior): Double {
        // shared 1-year calBP grid required
        require(a.grid.first() == b.grid.first() && a.grid.last() == b.grid.last() && a.grid.size == b.grid.size)
        val n = a.grid.size
        var cdf = 0.0
        var acc = 0.0
        val step = GRID_STEP
        // P(thetaA > thetaB) = sum a_i * P(thetaB < thetaA_i)
        // CDF_B at i accumulated below
        for (i in 0 until n) {
            val probBLeq = if (i == 0) 0.5 * b.density[0] * step else cdf + 0.5 * b.density[i] * step
            acc += a.density[i] * probBLeq * step
            cdf += b.density[i] * step
        }
        return acc
    }

    private fun round6(v: Double) = Math.round(v * 1e6) / 1e6
}
