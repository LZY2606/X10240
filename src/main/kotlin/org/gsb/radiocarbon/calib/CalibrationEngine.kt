package org.gsb.radiocarbon.calib

import org.gsb.radiocarbon.model.*
import java.security.MessageDigest

const val ALGORITHM_VERSION = "calib-1.0.0"

/**
 * Deterministic calendar-age calibration.
 *
 * L(t) ~ N(measuredAge | mu(t) + deltaR, sigmaA^2 + sigmaCurve(t)^2 (+sigmaDelta^2))
 * An empirical reservoir distribution is handled as a finite mixture, so every
 * offset sample contributes. The posterior is a normalized discrete distribution
 * on a fixed 1-year grid; it is never collapsed to a single mean.
 */
object CalibrationEngine {

    private const val SUPPORT_SIGMA = 5.0
    private const val EDGE_CLIP_FRACTION = 1e-6

    fun calibrate(
        measurement: MeasurementVersion,
        curve: CalibrationCurve,
        offset: OffsetHypothesis?,
        eraSystemId: String,
        gridStep: Int = 1,
        levelsPct: List<Double> = listOf(68.2, 95.4)
    ): CalibrationResult {
        val diagnostics = ArrayList<Diagnostic>()
        val grid = curve.span.first downTo curve.span.last step gridStep
        val raw = DoubleArray(grid.count())
        var total = 0.0
        val age = measurement.c14Age
        val sA = measurement.sigma

        grid.forEachIndexed { i, t ->
            val mu = curve.muAt(t)
            val sc = curve.sigmaAt(t)!!
            val l = when (val d = offset?.distribution) {
                null -> gauss(age, mu, sA, sc)
                is OffsetDistribution.Gaussian -> gauss(age, mu + d.deltaR, sA, sc, d.sigma)
                is OffsetDistribution.Empirical ->
                    d.samples.sumOf { dj -> gauss(age, mu + dj, sA, sc) } / d.samples.size
            }
            raw[i] = l
            total += l
        }

        val sources = linkedMapOf(
            "measurement" to measurement.ref(),
            "curve" to curve.ref()
        ).also { m -> offset?.let { m["offset"] = it.ref() } }

        val requestHash = sha256(
            buildString {
                append(ALGORITHM_VERSION).append('|')
                append(measurement.ref()).append('|').append(age).append('|').append(sA).append('|')
                append(curve.ref()).append('|').append(curve.points.size).append('|')
                append(curve.points.first().calBP).append(':').append(curve.points.last().calBP).append('|')
                append(offset?.ref() ?: "none").append('|')
                append(offsetFingerprint(offset)).append('|')
                append(gridStep).append('|').append(levelsPct.joinToString(","))
            }
        )

        if (total <= 0.0 || !total.isFinite()) {
            diagnostics += Diagnostic(
                "CALIB_OUTSIDE_CURVE",
                DiagnosticSeverity.ERROR,
                "Measurement ${age} ${measurement.unit} has no support on curve ${curve.ref()} span ${curve.span.first}-${curve.span.last} calBP (boundary interpolation exhausted)."
            )
            return emptyResult(measurement, curve, offset, eraSystemId, gridStep, levelsPct,
                grid.toList(), diagnostics, requestHash, sources, CalibrationStatus.OUTSIDE_CURVE, 0.0)
        }

        // Boundary clipping diagnostic: if the posterior mass at either edge of the
        // curve span is still appreciable, the interpolation boundary cuts support.
        val maxL = raw.maxOrNull()!!
        if (raw.first() / maxL > EDGE_CLIP_FRACTION) {
            diagnostics += Diagnostic(
                "CURVE_BOUNDARY_CLIP_OLD", DiagnosticSeverity.WARN,
                "Likelihood at the oldest interpolated grid cell (calBP ${grid.first()}) is still above ${EDGE_CLIP_FRACTION} of peak; the curve boundary truncates support."
            )
        }
        if (raw.last() / maxL > EDGE_CLIP_FRACTION) {
            diagnostics += Diagnostic(
                "CURVE_BOUNDARY_CLIP_YOUNG", DiagnosticSeverity.WARN,
                "Likelihood at the youngest interpolated grid cell (calBP ${grid.last()}) is still above ${EDGE_CLIP_FRACTION} of peak; the curve boundary truncates support."
            )
        }

        // Practical support reached the edges (within SUPPORT_SIGMA of measurement)?
        val effectiveSigma = effectiveSigma(sA, curve, offset)
        val muAtOld = curve.muAt(grid.first())!! + offsetShift(offset)
        val muAtYoung = curve.muAt(grid.last())!! + offsetShift(offset)
        if (age <= muAtOld + SUPPORT_SIGMA * effectiveSigma) {
            diagnostics += Diagnostic("CURVE_SPAN_OLD_LIMIT", DiagnosticSeverity.INFO,
                "Measurement support extends to the oldest curve point calBP ${grid.first()}.")
        }
        if (age >= muAtYoung - SUPPORT_SIGMA * effectiveSigma) {
            diagnostics += Diagnostic("CURVE_SPAN_YOUNG_LIMIT", DiagnosticSeverity.INFO,
                "Measurement support extends to the youngest curve point calBP ${grid.last()}.")
        }

        val weights = raw.map { it / total }
        val peaks = findPeaks(grid.toList(), weights)
        val intervals = hpdIntervals(grid.toList(), weights, levelsPct)

        return CalibrationResult(
            fingerprint = sha256(requestHash).take(16),
            algorithmVersion = ALGORITHM_VERSION,
            measurementRef = measurement.ref(),
            curveRef = curve.ref(),
            offsetRef = offset?.ref(),
            eraSystemId = eraSystemId,
            gridStep = gridStep,
            levelsPct = levelsPct,
            status = CalibrationStatus.OK,
            diagnostics = diagnostics,
            gridCalBP = grid.toList(),
            weights = weights,
            totalLikelihood = total,
            normalized = true,
            peaks = peaks,
            intervals = intervals,
            requestHash = requestHash,
            directSources = sources
        )
    }

    private fun effectiveSigma(sA: Double, curve: CalibrationCurve, offset: OffsetHypothesis?): Double {
        val scMax = curve.points.maxOf { it.sigma }
        val sD = (offset?.distribution as? OffsetDistribution.Gaussian)?.sigma ?: 0.0
        return kotlin.math.sqrt(sA * sA + scMax * scMax + sD * sD)
    }

    private fun offsetShift(offset: OffsetHypothesis?): Double = when (val d = offset?.distribution) {
        null -> 0.0
        is OffsetDistribution.Gaussian -> d.deltaR
        is OffsetDistribution.Empirical -> d.samples.average()
    }

    private fun gauss(x: Double, mean: Double, vararg sigmas: Double): Double {
        var v = 0.0
        for (s in sigmas) v += s * s
        val sd = kotlin.math.sqrt(v)
        if (sd <= 0.0) return if (kotlin.math.abs(x - mean) < 1e-12) 1.0 else 0.0
        val z = (x - mean) / sd
        return kotlin.math.exp(-0.5 * z * z) / (sd * kotlin.math.sqrt(2.0 * Math.PI))
    }

    private fun findPeaks(grid: List<Int>, w: List<Double>): List<Peak> {
        val peaks = ArrayList<Pair<Int, Double>>()
        for (i in w.indices) {
            val local = when {
                i == 0 -> w[i] >= w[i + 1]
                i == w.size - 1 -> w[i] > w[i - 1]
                else -> w[i] >= w[i - 1] && w[i] >= w[i + 1] && !(w[i] == w[i - 1] && w[i] == w[i + 1])
            }
            if (local && w[i] > 0.0) peaks += grid[i] to w[i]
        }
        return peaks.sortedByDescending { it.second }
            .mapIndexed { idx, (t, d) -> Peak(idx + 1, t, d) }
    }

    /**
     * Discrete HPD: sort cells by density, accumulate to the level, take
     * contiguous runs of cells at or above the crossing density. Equal-mass
     * runs are reported explicitly ("tied calendar intervals").
     */
    fun hpdIntervals(grid: List<Int>, weights: List<Double>, levelsPct: List<Double>): List<HpdInterval> {
        val out = ArrayList<HpdInterval>()
        for (level in levelsPct) {
            val target = level / 100.0
            val order = weights.indices.sortedWith(compareColumnDesc({ weights[it] }, { grid[it] }))
            var acc = 0.0
            var cutoffIdx = 0
            for ((n, i) in order.withIndex()) {
                acc += weights[i]
                cutoffIdx = i
                if (acc >= target - 1e-12) break
            }
            val cutoff = weights[cutoffIdx]
            val selected = BooleanArray(weights.size) { weights[it] >= cutoff - 1e-15 }
            val runs = ArrayList<IntRange>()
            var start = -1
            for (i in weights.indices) {
                if (selected[i]) {
                    if (start < 0) start = i
                } else if (start >= 0) {
                    runs += start..i - 1
                    start = -1
                }
            }
            if (start >= 0) runs += start..weights.lastIndex

            val raw = runs.map { r ->
                Triple(grid[r.last], grid[r.first], r.sumOf { weights[it] }) // start(older calBP), end(younger calBP), mass
            }.sortedByDescending { it.first }
            raw.forEachIndexed { ordinal, triple ->
                val sameMass = raw.mapIndexedNotNull { j, o ->
                    if (j != ordinal && kotlin.math.abs(o.third - triple.third) <= 1e-9) j + 1 else null
                }
                out += HpdInterval(level, ordinal + 1, triple.first, triple.second, triple.third, sameMass)
            }
        }
        return out
    }

    private fun compareColumnDesc(key: (Int) -> Double, tie: (Int) -> Int): Comparator<Int> =
        Comparator { a, b ->
            val c = key(b).compareTo(key(a))
            if (c != 0) c else tie(a).compareTo(tie(b))
        }

    /** P(T_older > T_younger), with half weight on equality, over a shared 1-year grid. */
    fun orderProbability(older: CalibrationResult, younger: CalibrationResult): Double {
        if (!older.normalized || !younger.normalized) return Double.NaN
        val lo = minOf(older.gridCalBP.last(), younger.gridCalBP.last())
        val hi = maxOf(older.gridCalBP.first(), younger.gridCalBP.first())
        val wo = older.gridCalBP.zip(older.weights).toMap()
        val wy = younger.gridCalBP.zip(younger.weights).toMap()
        var strict = 0.0
        var equal = 0.0
        for (t in hi downTo lo) {
            val a = wo[t] ?: 0.0
            for (u in (t - 1) downTo lo) strict += a * (wy[u] ?: 0.0)
            equal += a * (wy[t] ?: 0.0)
        }
        return strict + 0.5 * equal
    }

    private fun offsetFingerprint(offset: OffsetHypothesis?): String = when (val d = offset?.distribution) {
        null -> "none"
        is OffsetDistribution.Gaussian -> "g:${d.deltaR}:${d.sigma}:${d.unit}"
        is OffsetDistribution.Empirical -> "e:${d.samples.size}:${d.samples.joinToString(";")}"
    }

    private fun emptyResult(
        measurement: MeasurementVersion, curve: CalibrationCurve, offset: OffsetHypothesis?,
        eraSystemId: String, gridStep: Int, levelsPct: List<Double>, grid: List<Int>,
        diagnostics: List<Diagnostic>, requestHash: String, sources: Map<String, String>,
        status: CalibrationStatus, total: Double
    ) = CalibrationResult(
        fingerprint = sha256(requestHash + ":empty").take(16),
        algorithmVersion = ALGORITHM_VERSION,
        measurementRef = measurement.ref(),
        curveRef = curve.ref(),
        offsetRef = offset?.ref(),
        eraSystemId = eraSystemId,
        gridStep = gridStep,
        levelsPct = levelsPct,
        status = status,
        diagnostics = diagnostics,
        gridCalBP = grid,
        weights = grid.map { 0.0 },
        totalLikelihood = total,
        normalized = false,
        peaks = emptyList(),
        intervals = emptyList(),
        requestHash = requestHash,
        directSources = sources
    )

    fun sha256(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
