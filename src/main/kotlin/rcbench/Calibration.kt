package rcbench

import java.security.MessageDigest
import kotlin.math.*

object Calibration {
    const val ALGORITHM_VERSION = "rc-calib/1.0.0"
    const val SEQ_ALGORITHM_VERSION = "rc-seq/1.0.0"

    fun sha256(s: String): String {
        val d = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
        return d.joinToString("") { "%02x".format(it) }
    }

    fun normalPdf(x: Double, mean: Double, sd: Double): Double {
        val z = (x - mean) / sd
        return exp(-0.5 * z * z) / (sd * sqrt(2.0 * PI))
    }

    fun interpolate(points: List<CurvePoint>, calBP: Double): Pair<Double, Double> {
        require(points.isNotEmpty())
        val sorted = points.sortedBy { it.calBP }
        if (calBP <= sorted.first().calBP) return sorted.first().c14 to sorted.first().sigma
        if (calBP >= sorted.last().calBP) return sorted.last().c14 to sorted.last().sigma
        for (i in 0 until sorted.size - 1) {
            val a = sorted[i]
            val b = sorted[i + 1]
            if (calBP >= a.calBP && calBP <= b.calBP) {
                if (b.calBP == a.calBP) return a.c14 to a.sigma
                val t = (calBP - a.calBP) / (b.calBP - a.calBP)
                return (a.c14 + t * (b.c14 - a.c14)) to (a.sigma + t * (b.sigma - a.sigma))
            }
        }
        return sorted.last().c14 to sorted.last().sigma
    }

    fun fingerprint(vararg parts: String): String = sha256(parts.joinToString("|"))

    fun calibrate(
        resultId: String,
        measurement: Measurement,
        curve: CalibrationCurve,
        offset: ReservoirOffset?,
        era: EraSystem,
        computedAt: String,
        step: Double = 1.0,
    ): CalibrationResult {
        val sorted = curve.points.sortedBy { it.calBP }
        val from = sorted.first().calBP
        val to = sorted.last().calBP
        val n = ((to - from) / step).toInt() + 1
        val offMean = offset?.mean ?: 0.0
        val offSd = offset?.sd ?: 0.0
        val raw = DoubleArray(n)
        for (i in 0 until n) {
            val t = from + i * step
            val (c14, sigC) = interpolate(sorted, t)
            val sd = sqrt(measurement.sigma * measurement.sigma + sigC * sigC + offSd * offSd)
            raw[i] = normalPdf(measurement.ageBP, c14 + offMean, sd)
        }
        val norm = raw.sum()
        val values = raw.map { it / norm }
        val grid = DensityGrid(from, step, values, norm)
        val peaks = findPeaks(values, from, step)
        val intervals = listOf(0.682, 0.954).flatMap { level -> hdrIntervals(resultId, grid, level) }
        val fp = fingerprint(
            ALGORITHM_VERSION,
            "${measurement.id}:${measurement.version}:${measurement.ageBP}:${measurement.sigma}",
            "${curve.id}:${curve.version}",
            offset?.let { "${it.id}:${it.version}:${it.mean}:${it.sd}" } ?: "no-offset",
            "${era.id}:${era.version}",
            "$from:$to:$step",
        )
        val deps = mutableListOf(
            refOf(measurement.id, measurement.version),
            refOf(curve.id, curve.version),
            refOf(era.id, era.version),
        )
        if (offset != null) deps.add(refOf(offset.id, offset.version))
        return CalibrationResult(
            id = resultId,
            measurementRef = refOf(measurement.id, measurement.version),
            curveRef = refOf(curve.id, curve.version),
            offsetRef = offset?.let { refOf(it.id, it.version) },
            eraRef = refOf(era.id, era.version),
            algorithmVersion = ALGORITHM_VERSION,
            paramFingerprint = fp,
            grid = grid,
            peaks = peaks,
            intervals = intervals,
            deps = deps,
            computedAt = computedAt,
        )
    }

    fun findPeaks(values: List<Double>, from: Double, step: Double): List<Peak> {
        val maxV = values.max()
        val peaks = mutableListOf<Peak>()
        for (i in values.indices) {
            val v = values[i]
            val left = if (i > 0) values[i - 1] else -1.0
            val right = if (i < values.size - 1) values[i + 1] else -1.0
            if (v >= left && v >= right && v >= maxV * 0.01 && v > 0.0) {
                if (peaks.none { abs(it.calBP - (from + i * step)) < 1e-9 }) {
                    peaks.add(Peak(from + i * step, v))
                }
            }
        }
        return peaks
    }

    fun hdrIntervals(resultId: String, grid: DensityGrid, level: Double): List<CalInterval> {
        val values = grid.values
        val sortedDesc = values.sortedDescending()
        var acc = 0.0
        var threshold = 0.0
        for (v in sortedDesc) {
            acc += v
            threshold = v
            if (acc >= level) break
        }
        val intervals = mutableListOf<CalInterval>()
        var i = 0
        var idx = 0
        while (i < values.size) {
            if (values[i] >= threshold) {
                val start = i
                val segments = mutableListOf<EvidenceSlice>()
                var mass = 0.0
                while (i < values.size && values[i] >= threshold) {
                    val t0 = grid.fromCalBP + i * grid.step
                    segments.add(EvidenceSlice(t0, t0 + grid.step, values[i], values[i] * grid.step))
                    mass += values[i] * grid.step
                    i++
                }
                idx++
                intervals.add(
                    CalInterval(
                        id = "$resultId-L${(level * 1000).toInt()}-I$idx",
                        level = level,
                        fromCalBP = grid.fromCalBP + start * grid.step,
                        toCalBP = grid.fromCalBP + i * grid.step,
                        probability = mass,
                        threshold = threshold,
                        segments = segments,
                    )
                )
            } else {
                i++
            }
        }
        return intervals
    }

    fun eraLabel(calBP: Double, convention: String): String {
        if (convention == "calBP") return "${calBP.toInt()} cal BP"
        val astro = 1950 - calBP.toInt()
        return when {
            astro >= 1 -> "$astro CE"
            astro <= 0 -> "${1 - astro} BCE"
            else -> unreachable()
        }
    }

    private fun unreachable(): Nothing = error("unreachable")

    fun orderProbability(gridA: DensityGrid, gridB: DensityGrid): Double {
        var p = 0.0
        for (i in gridA.values.indices) {
            val tA = gridA.fromCalBP + i * gridA.step
            val pA = gridA.values[i]
            if (pA <= 0.0) continue
            var cumB = 0.0
            for (j in gridB.values.indices) {
                val tB = gridB.fromCalBP + j * gridB.step
                if (tB < tA) cumB += gridB.values[j]
            }
            p += pA * cumB
        }
        return p
    }

    fun analyzeSequence(
        analysisId: String,
        constraints: List<SequenceConstraint>,
        resultsByMeasurement: Map<String, CalibrationResult>,
        computedAt: String,
        idGen: (String) -> String,
    ): SequenceAnalysis {
        val conflicts = mutableListOf<SequenceConflict>()
        val satisfied = mutableListOf<String>()
        val deps = mutableListOf<String>()
        for (c in constraints) {
            val ra = resultsByMeasurement[c.beforeId]
            val rb = resultsByMeasurement[c.afterId]
            deps.add(refOf(c.id, c.version))
            if (ra == null || rb == null) continue
            deps.addAll(ra.deps)
            deps.addAll(rb.deps)
            val p = orderProbability(ra.grid, rb.grid)
            if (p < 0.5) {
                val candidates = mutableListOf<CandidateAdjustment>()
                candidates.add(
                    CandidateAdjustment(
                        id = idGen("cand"),
                        kind = "flag-outlier",
                        measurementId = c.beforeId,
                        rationale = "P(${c.beforeId} 早于 ${c.afterId})=${"%.3f".format(p)} < 0.5；建议将 ${c.beforeId} 标记为可疑离群样品（不改写测量值）",
                    )
                )
                candidates.add(
                    CandidateAdjustment(
                        id = idGen("cand"),
                        kind = "flag-outlier",
                        measurementId = c.afterId,
                        rationale = "P(${c.beforeId} 早于 ${c.afterId})=${"%.3f".format(p)} < 0.5；建议将 ${c.afterId} 标记为可疑离群样品（不改写测量值）",
                    )
                )
                for ((m, r) in listOf(c.beforeId to ra, c.afterId to rb)) {
                    for (iv in r.intervals.filter { it.level == 0.954 }) {
                        candidates.add(
                            CandidateAdjustment(
                                id = idGen("cand"),
                                kind = "prefer-interval",
                                measurementId = m,
                                intervalId = iv.id,
                                rationale = "在无法唯一决定时保留合法候选：采纳 $m 的并列日历区间 ${iv.id}（${"%.1f".format(iv.fromCalBP)}–${"%.1f".format(iv.toCalBP)} calBP, p=${"%.3f".format(iv.probability)}）后重估层序",
                            )
                        )
                    }
                }
                conflicts.add(
                    SequenceConflict(
                        id = idGen("conflict"),
                        constraintRef = refOf(c.id, c.version),
                        pOrder = p,
                        candidates = candidates,
                    )
                )
            } else {
                satisfied.add(refOf(c.id, c.version))
            }
        }
        val fp = fingerprint(SEQ_ALGORITHM_VERSION, constraints.joinToString(",") { refOf(it.id, it.version) })
        return SequenceAnalysis(
            id = analysisId,
            algorithmVersion = SEQ_ALGORITHM_VERSION,
            paramFingerprint = fp,
            conflicts = conflicts,
            satisfied = satisfied,
            deps = deps.distinct(),
            computedAt = computedAt,
        )
    }
}
