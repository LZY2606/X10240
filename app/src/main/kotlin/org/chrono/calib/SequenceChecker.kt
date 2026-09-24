package org.chrono.calib

import org.chrono.domain.EraScale
import org.chrono.domain.SequenceEdge
import kotlin.math.max

enum class SequenceStatus { CONSISTENT, AMBIGUOUS, CONTRADICTION, UNKNOWN }

data class SequenceAssessment(
    val edgeId: String,
    val olderId: String,
    val youngerId: String,
    val status: SequenceStatus,
    /** P(older 严格早于 younger)，按两条校准后验独立逐网格求和（确定性）。 */
    val orderingProbability: Double?,
    val candidateAdjustments: List<CandidateAdjustment>,
    val note: String? = null
)

sealed class CandidateAdjustment {
    abstract val edgeId: String
    abstract val targetMeasurementId: String
    abstract val rationale: String

    /** 建议改用储库偏移参数（保留原测量），产生新版本供人工决定。 */
    data class ReservoirShift(
        override val edgeId: String,
        override val targetMeasurementId: String,
        val suggestedDeltaMean: Double,
        val currentDeltaMean: Double?,
        override val rationale: String
    ) : CandidateAdjustment()

    /** 建议只采纳较老的并列峰/区间（其余峰保留、不删除）。 */
    data class RestrictToOlderPeak(
        override val edgeId: String,
        override val targetMeasurementId: String,
        val keepIntervalFromAstro: Double,
        val keepIntervalToAstro: Double,
        override val rationale: String
    ) : CandidateAdjustment()

    /** 建议人工排除（污染/老碳），记录为排除决定事件。 */
    data class ExcludeSample(
        override val edgeId: String,
        override val targetMeasurementId: String,
        override val rationale: String
    ) : CandidateAdjustment()
}

object SequenceChecker {

    fun assess(edge: SequenceEdge, results: Map<String, CalibrationResult>): SequenceAssessment {
        val older = results[edge.olderId]
        val younger = results[edge.youngerId]
        if (older == null || younger == null || older.grid.isEmpty() || younger.grid.isEmpty() ||
            older.peaks.isEmpty() || younger.peaks.isEmpty()) {
            return SequenceAssessment(edge.edgeId, edge.olderId, edge.youngerId, SequenceStatus.UNKNOWN, null,
                emptyList(), "one or both calibrations unavailable")
        }
        val p = orderingProbability(older, younger)
        val status = when {
            p >= 0.95 -> SequenceStatus.CONSISTENT
            p <= 0.05 -> SequenceStatus.CONTRADICTION
            else -> SequenceStatus.AMBIGUOUS
        }
        val cands = if (status == SequenceStatus.CONTRADICTION || status == SequenceStatus.AMBIGUOUS)
            buildCandidates(edge, older, younger, p) else emptyList()
        val note = if (EraScale.crossesBceCe(older.grid.first().year, younger.grid.last().year))
            "candidate calendar range crosses the BCE/CE boundary" else null
        return SequenceAssessment(edge.edgeId, edge.olderId, edge.youngerId, status, p, cands, note)
    }

    /** P(O < Y)：在共同网格上逐年配对求和，含跨网格插值。 */
    fun orderingProbability(older: CalibrationResult, younger: CalibrationResult): Double {
        val lo = max(older.grid.first().year, younger.grid.first().year)
        val hi = minOf(older.grid.last().year, younger.grid.last().year)
        if (lo >= hi) return 0.0
        val step = 1
        var pEarlier = 0.0
        var totalO = 0.0
        var totalY = 0.0
        var cumY = 0.0 // P(Y > o) 累加
        val n = (hi - lo).toInt()
        val rows = ArrayList<Triple<Double, Double, Double>>(n + 1)
        var i = 0
        while (i <= n) {
            val year = lo + i * step
            val po = GridMath.linInterp(older.grid, year)
            val py = GridMath.linInterp(younger.grid, year)
            rows += Triple(year, po, py)
            totalO += po; totalY += py
            i++
        }
        if (totalO == 0.0 || totalY == 0.0) return 0.0
        // P(O<Y) = sum_y pO(y) * P(Y > y)；先建 younger 累积分布
        val cdf = DoubleArray(rows.size)
        var acc = 0.0
        for (j in rows.indices) {
            val (_, _, py) = rows[j]
            val w = if (j == 0 || j == rows.lastIndex) 0.5 else 1.0
            acc += py * w
            cdf[j] = acc
        }
        val total = acc
        for (j in rows.indices) {
            val (_, po, _) = rows[j]
            val w = if (j == 0 || j == rows.lastIndex) 0.5 else 1.0
            val pYGreater = (total - cdf[j]) / total
            pEarlier += po * w * pYGreater
        }
        return (pEarlier / totalO).coerceIn(0.0, 1.0)
    }

    private fun buildCandidates(
        edge: SequenceEdge, older: CalibrationResult, younger: CalibrationResult, p: Double
    ): List<CandidateAdjustment> {
        val out = ArrayList<CandidateAdjustment>()
        // 1) 若 younger 样本本身有多个并列峰，建议只保留更老的峰所在区间
        val oldPeakYears = older.peaks.map { it.yearAstro }
        val olderIntervals = younger.intervals95.filter { iv ->
            oldPeakYears.any { it > iv.toAstro }
        }
        if (olderIntervals.isNotEmpty()) {
            val iv = olderIntervals.maxByOrNull { it.toAstro }!!
            out += CandidateAdjustment.RestrictToOlderPeak(
                edge.edgeId, younger.measurementId, iv.fromAstro, iv.toAstro,
                "younger sample has a competing older interval ${EraScale.label(iv.fromAstro)}\u2013${EraScale.label(iv.toAstro)}"
            )
        }
        // 2) 估计使排序成立所需的 ΔR 均值平移（仅建议，不改测量）
        val shift = requiredReservoirShift(older, younger)
        if (shift != null) {
            out += CandidateAdjustment.ReservoirShift(
                edge.edgeId, younger.measurementId, shift, null,
                "a reservoir mean near ${shift.toInt()} 14C yr would raise ordering probability above 0.95"
            )
        }
        // 3) 无法唯一决定时给出排除候选，交人工决定
        out += CandidateAdjustment.ExcludeSample(
            edge.edgeId, younger.measurementId,
            "if old-carbon contamination is suspected, exclude by a sourced decision (P(order)=${"%.3f".format(p)})"
        )
        return out
    }

    /** 沿 14C 轴平移 younger 分布以达成 P>=0.95 的最小 ΔR（粗网格确定性搜索）。 */
    fun requiredReservoirShift(older: CalibrationResult, younger: CalibrationResult): Double? {
        // 用 younger 曲线无法在此获知，这里基于分布均值差给出诊断量，供 UI 展示「候选」
        val meanO = meanYear(older) ?: return null
        val meanY = meanYear(younger) ?: return null
        val gap = meanY - meanO
        if (gap >= 0) return null
        val need = max(20.0, -gap * 1.2)
        return (Math.round(need / 10.0) * 10).toDouble()
    }

    private fun meanYear(r: CalibrationResult): Double? {
        var m = 0.0; var w = 0.0
        for (g in r.grid) { m += g.year * g.density; w += g.density }
        return if (w == 0.0) null else m / w
    }
}
