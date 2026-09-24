package workbench

import java.security.MessageDigest
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.roundToLong
import kotlin.math.sqrt

fun sha256(text: String): String =
    MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

object Calibrator {
    const val ALGORITHM_VERSION = "calib-1.0.0"
    val HPD_LEVELS = listOf(0.6827, 0.9545)

    data class CurveVal(val c14: Double, val sigma: Double, val clamped: Boolean)

    /** 固定版本曲线上的分段线性插值；边界处钳制并标记。 */
    fun interpolate(curve: Curve, calBp: Double): CurveVal {
        val pts = curve.points.sortedBy { it.calBp }
        require(pts.isNotEmpty()) { "curve ${curve.id} has no points" }
        if (calBp <= pts.first().calBp) {
            return CurveVal(pts.first().c14Age, pts.first().sigma, calBp < pts.first().calBp)
        }
        if (calBp >= pts.last().calBp) {
            return CurveVal(pts.last().c14Age, pts.last().sigma, calBp > pts.last().calBp)
        }
        var lo = 0
        var hi = pts.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) / 2
            if (pts[mid].calBp <= calBp) lo = mid else hi = mid
        }
        val a = pts[lo]
        val b = pts[hi]
        val t = (calBp - a.calBp) / (b.calBp - a.calBp)
        return CurveVal(
            a.c14Age + t * (b.c14Age - a.c14Age),
            a.sigma + t * (b.sigma - a.sigma),
            false
        )
    }

    /** calBP -> BCE/CE 标签；没有 0 年。 */
    fun eraLabel(calBp: Double, era: EraSystem): String {
        val year = (era.bpReference - calBp).roundToLong()
        return if (year >= 1) "CE $year" else "BCE ${1 - year}"
    }

    fun fingerprint(
        meas: Measurement, curve: Curve, offset: OffsetDist?, era: EraSystem,
        gridStart: Double, gridEnd: Double, step: Double,
        clampMin: Double?, clampMax: Double?
    ): String {
        val canonical = listOf(
            "alg=$ALGORITHM_VERSION",
            "meas=${meas.id}@${meas.version}",
            "curve=${curve.id}@${curve.version}",
            "offset=${offset?.let { "${it.id}@${it.version}" } ?: "none"}",
            "era=${era.id}",
            "grid=$gridStart:$gridEnd:$step",
            "clamp=${clampMin?.toString() ?: "-"}:${clampMax?.toString() ?: "-"}"
        ).joinToString("|")
        return sha256(canonical)
    }

    /**
     * 在固定网格上计算归一化的后验概率。多峰分布保持为多峰，不压成平均年。
     * 同一组输入 + 固定算法版本 => 完全可重现。
     */
    fun calibrate(
        runId: String,
        meas: Measurement,
        curve: Curve,
        offset: OffsetDist?,
        era: EraSystem,
        gridStart: Double,
        gridEnd: Double,
        step: Double,
        clampMinCalBp: Double? = null,
        clampMaxCalBp: Double? = null,
        kind: String = "calibration",
        derivedFromCandidateId: String? = null
    ): CalibrationRun {
        val age = requireNotNull(meas.c14Age) { "measurement ${meas.id} has no convertible 14C age" }
        val sigma = requireNotNull(meas.sigma) { "measurement ${meas.id} has no sigma" }
        val effAge = age - (offset?.mean ?: 0.0)
        val effSigma = sqrt(sigma * sigma + (offset?.sigma ?: 0.0) * (offset?.sigma ?: 0.0))

        val n = floor((gridEnd - gridStart) / step).toInt() + 1
        require(n > 1) { "grid too small" }
        val probs = DoubleArray(n)
        var clampedAnywhere = false
        for (i in 0 until n) {
            val t = gridStart + i * step
            if ((clampMinCalBp != null && t < clampMinCalBp) ||
                (clampMaxCalBp != null && t > clampMaxCalBp)
            ) {
                probs[i] = 0.0
                continue
            }
            val cv = interpolate(curve, t)
            if (cv.clamped) clampedAnywhere = true
            val variance = effSigma * effSigma + cv.sigma * cv.sigma
            val diff = cv.c14 - effAge
            probs[i] = exp(-(diff * diff) / (2.0 * variance)) / sqrt(2.0 * Math.PI * variance)
        }
        val total = probs.sum()
        require(total > 0.0) { "likelihood vanished on grid" }
        val normalized = probs.map { it / total }

        val grid = ProbGrid(gridStart, step, normalized)
        val intervals = hpdIntervals(grid, curve, era, clampedAnywhere)
        return CalibrationRun(
            id = runId,
            measurementId = meas.id,
            measurementVersion = meas.version,
            curveId = curve.id,
            curveVersion = curve.version,
            offsetId = offset?.id,
            offsetVersion = offset?.version,
            eraId = era.id,
            algorithmVersion = ALGORITHM_VERSION,
            paramFingerprint = fingerprint(meas, curve, offset, era, gridStart, gridEnd, step, clampMinCalBp, clampMaxCalBp),
            grid = grid,
            intervals = intervals,
            kind = kind,
            derivedFromCandidateId = derivedFromCandidateId
        )
    }

    /** 最高后验密度区间：可以是多个互不相交的并列日历区间。 */
    fun hpdIntervals(grid: ProbGrid, curve: Curve, era: EraSystem, clampedAnywhere: Boolean): List<HpdInterval> {
        val probs = grid.probs
        val total = probs.sum()
        val order = probs.indices.sortedByDescending { probs[it] }
        val out = mutableListOf<HpdInterval>()
        for (level in HPD_LEVELS) {
            val selected = BooleanArray(probs.size)
            var acc = 0.0
            var i = 0
            while (acc < level * total && i < order.size) {
                selected[order[i]] = true
                acc += probs[order[i]]
                i++
            }
            var j = 0
            while (j < selected.size) {
                if (!selected[j]) {
                    j++
                    continue
                }
                var k = j
                while (k + 1 < selected.size && selected[k + 1]) k++
                val from = grid.startCalBp + j * grid.step
                val to = grid.startCalBp + k * grid.step
                val mass = (j..k).sumOf { probs[it] }
                val curvePoints = curve.points
                    .filter { it.calBp >= from - grid.step && it.calBp <= to + grid.step }
                    .sortedBy { it.calBp }
                val touchesBoundary = j == 0 || k == selected.size - 1
                out += HpdInterval(
                    level = level,
                    fromCalBp = from,
                    toCalBp = to,
                    mass = mass,
                    fromEra = eraLabel(from, era),
                    toEra = eraLabel(to, era),
                    evidence = IntegrationEvidence(
                        gridFrom = j,
                        gridTo = k,
                        fromCalBp = from,
                        toCalBp = to,
                        mass = mass,
                        boundaryClamped = clampedAnywhere && touchesBoundary,
                        curvePoints = curvePoints,
                        note = "sum of normalized grid probabilities p[$j..$k] = ${"%.6f".format(mass)}"
                    )
                )
                j = k + 1
            }
        }
        return out.sortedWith(compareBy({ it.level }, { it.fromCalBp }))
    }

    fun medianOf(grid: ProbGrid): Double {
        val total = grid.probs.sum()
        var acc = 0.0
        for (i in grid.probs.indices) {
            acc += grid.probs[i]
            if (acc >= total / 2.0) return grid.startCalBp + i * grid.step
        }
        return grid.startCalBp + (grid.probs.size - 1) * grid.step
    }
}

object Sequencer {
    /** 层序说 older 应更老（calBp 更大），但校准中位数相反 => 冲突。 */
    fun isConflict(constraint: SequenceConstraint, olderRun: CalibrationRun, youngerRun: CalibrationRun): Boolean {
        val olderMedian = Calibrator.medianOf(olderRun.grid)
        val youngerMedian = Calibrator.medianOf(youngerRun.grid)
        return olderMedian < youngerMedian
    }

    /**
     * 层序冲突只产生候选调整，不改写测量。
     * 无法唯一决定时保留全部合法候选。
     */
    fun candidatesFor(conflictId: String, constraint: SequenceConstraint, olderRun: CalibrationRun, youngerRun: CalibrationRun): List<CandidateAdjustment> {
        val olderMedian = Calibrator.medianOf(olderRun.grid)
        val youngerMedian = Calibrator.medianOf(youngerRun.grid)
        return listOf(
            CandidateAdjustment(
                id = "$conflictId-c1",
                kind = "truncate-older",
                description = "截断 ${constraint.olderId}：只保留不晚于层序允许的 calBP >= ${"%.1f".format(youngerMedian)} 的部分",
                targetMeasurementId = constraint.olderId,
                clampMinCalBp = youngerMedian
            ),
            CandidateAdjustment(
                id = "$conflictId-c2",
                kind = "truncate-younger",
                description = "截断 ${constraint.youngerId}：只保留 calBP <= ${"%.1f".format(olderMedian)} 的部分",
                targetMeasurementId = constraint.youngerId,
                clampMaxCalBp = olderMedian
            )
        )
    }
}
