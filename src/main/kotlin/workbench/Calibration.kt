package workbench

import java.security.MessageDigest
import kotlin.math.*

object Calibration {
    const val ALGORITHM = "intercept-grid-v1"
    const val GRID_STEP = 5.0
    val LEVELS = listOf(0.6827, 0.9545)

    fun interpolate(curve: CurveData, calBP: Double): CurvePoint {
        val pts = curve.points.sortedBy { it.calBP }
        require(pts.isNotEmpty()) { "curve ${curve.key()} has no points" }
        if (calBP <= pts.first().calBP) return pts.first().copy(calBP = calBP)
        if (calBP >= pts.last().calBP) return pts.last().copy(calBP = calBP)
        var lo = 0
        var hi = pts.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) / 2
            if (pts[mid].calBP <= calBP) lo = mid else hi = mid
        }
        val a = pts[lo]
        val b = pts[hi]
        val f = (calBP - a.calBP) / (b.calBP - a.calBP)
        return CurvePoint(calBP, a.c14Age + f * (b.c14Age - a.c14Age), a.sigma + f * (b.sigma - a.sigma))
    }

    fun calibrate(
        curve: CurveData,
        c14Age: Double,
        sigma: Double,
        deltaR: Double = 0.0,
        deltaRSigma: Double = 0.0
    ): Pair<List<Double>, List<Double>> {
        val lo = curve.points.minOf { it.calBP }
        val hi = curve.points.maxOf { it.calBP }
        val start = ceil(lo / GRID_STEP) * GRID_STEP
        val end = floor(hi / GRID_STEP) * GRID_STEP
        val n = ((end - start) / GRID_STEP).toInt() + 1
        val grid = (0 until n).map { start + it * GRID_STEP }
        val adjusted = c14Age - deltaR
        val varBase = sigma * sigma + deltaRSigma * deltaRSigma
        val raw = grid.map { t ->
            val cp = interpolate(curve, t)
            val v = varBase + cp.sigma * cp.sigma
            exp(-(adjusted - cp.c14Age).pow(2) / (2.0 * v)) / sqrt(2.0 * Math.PI * v)
        }
        val norm = raw.sum() * GRID_STEP
        require(norm > 0.0) { "calibration density normalizes to zero" }
        return grid to raw.map { it / norm }
    }

    fun intervals(grid: List<Double>, density: List<Double>, levels: List<Double> = LEVELS): List<CalInterval> {
        val step = grid[1] - grid[0]
        val result = mutableListOf<CalInterval>()
        var idx = 0
        for (level in levels) {
            val order = density.indices.sortedByDescending { density[it] }
            var acc = 0.0
            var k = 0
            while (k < order.size && acc < level) {
                acc += density[order[k]] * step
                k++
            }
            val threshold = if (k == 0) Double.MAX_VALUE else density[order[k - 1]]
            var i = 0
            while (i < grid.size) {
                if (density[i] >= threshold) {
                    var j = i
                    var p = 0.0
                    while (j < grid.size && density[j] >= threshold) {
                        p += density[j] * step
                        j++
                    }
                    val from = grid[i] - step / 2
                    val to = grid[j - 1] + step / 2
                    result.add(
                        CalInterval(
                            index = idx++,
                            level = level,
                            fromCalBP = from,
                            toCalBP = to,
                            probability = p,
                            fromCalendar = calendarLabel(from),
                            toCalendar = calendarLabel(to)
                        )
                    )
                    i = j
                } else i++
            }
        }
        return result
    }

    fun integrate(grid: List<Double>, density: List<Double>, from: Double, to: Double, calibrationId: String, intervalIndex: Int, level: Double): IntegrationEvidence {
        val step = grid[1] - grid[0]
        val segments = mutableListOf<EvidenceSegment>()
        var total = 0.0
        for (i in 0 until grid.size - 1) {
            val a = grid[i]
            val b = grid[i + 1]
            val lo = max(a, from)
            val hi = min(b, to)
            if (hi <= lo) continue
            val fa = density[i]
            val fb = density[i + 1]
            val mean = (fa + fb) / 2.0
            val contribution = mean * (hi - lo)
            total += contribution
            segments.add(EvidenceSegment(lo, hi, mean, contribution))
        }
        return IntegrationEvidence(calibrationId, intervalIndex, level, from, to, total, ALGORITHM, step, segments)
    }

    fun probabilityOlderThan(gridA: List<Double>, densA: List<Double>, gridB: List<Double>, densB: List<Double>): Double {
        val stepA = gridA[1] - gridA[0]
        val stepB = gridB[1] - gridB[0]
        val cumB = DoubleArray(gridB.size)
        var acc = 0.0
        for (j in gridB.indices) {
            acc += densB[j] * stepB
            cumB[j] = acc
        }
        var p = 0.0
        for (i in gridA.indices) {
            val t = gridA[i]
            val j = kotlin.math.floor((t - gridB[0]) / stepB).toInt()
            val massBelow = when {
                j < 0 -> 0.0
                j >= gridB.size -> 1.0
                else -> cumB[j]
            }
            p += densA[i] * stepA * massBelow
        }
        return p
    }

    fun calendarLabel(calBP: Double): String {
        val y = 1950 - Math.round(calBP).toInt()
        return if (y > 0) "$y CE" else "${1 - y} BCE"
    }

    fun fingerprint(vararg parts: Any?): String {
        val md = MessageDigest.getInstance("SHA-256")
        val text = parts.joinToString("|") { it.toString() }
        return md.digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    fun shortHash(vararg parts: Any?): String = fingerprint(*parts).substring(0, 12)
}
