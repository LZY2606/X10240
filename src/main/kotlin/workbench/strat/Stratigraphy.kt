package workbench.strat

import workbench.model.*

const val STRAT_ALGORITHM_VERSION = "strat-1.0"

/**
 * 层序评估：lower 必须老于 upper（calBP 更大）。
 * 冲突时不改写测量，只产生候选调整；无法唯一决定时保留全部合法候选。
 */
object Stratigraphy {

    fun evaluate(
        conflictId: String,
        constraint: StratConstraint,
        lowerResult: CalibrationResult,
        upperResult: CalibrationResult
    ): StratConflict? {
        val constraintRef = "${constraint.entityId}@v${constraint.version}"
        // 取 95.4% 层级区间（若无则取全部模式范围）
        val lowerRanges = credibleRanges(lowerResult)
        val upperRanges = credibleRanges(upperResult)
        // 存在至少一对区间使得 lower 全段老于 upper 全段 => 无冲突
        val compatible = lowerRanges.any { lr ->
            upperRanges.any { ur -> lr.second > ur.first } // lower.endCalBp(最年轻端) > upper.startCalBp(最老端)
        }
        if (compatible) return null

        val candidates = ArrayList<CandidateAdjustment>()
        var n = 0
        // 候选 1：将 lower 截断到老于 upper 最老端的部分（若合法）
        val upperOldest = upperRanges.maxOf { it.first }
        val lowerLegal = lowerRanges.filter { it.second > upperOldest }
        if (lowerLegal.isNotEmpty()) {
            candidates += CandidateAdjustment(
                candidateId = "$conflictId-c${++n}",
                description = "约束 lower 样本取老于 upper 最老端 ($upperOldest calBP) 的区间部分",
                impliedChange = "constrain:${lowerResult.resultId} to calBp>$upperOldest",
                resultingIntervalCalBp = IntRange(upperOldest + 1, lowerLegal.maxOf { it.first })
            )
        }
        // 候选 2：将 upper 截断到年轻于 lower 最年轻端的部分（若合法）
        val lowerYoungest = lowerRanges.minOf { it.second }
        val upperLegal = upperRanges.filter { it.first < lowerYoungest }
        if (upperLegal.isNotEmpty()) {
            candidates += CandidateAdjustment(
                candidateId = "$conflictId-c${++n}",
                description = "约束 upper 样本取年轻于 lower 最年轻端 ($lowerYoungest calBP) 的区间部分",
                impliedChange = "constrain:${upperResult.resultId} to calBp<$lowerYoungest",
                resultingIntervalCalBp = IntRange(upperLegal.minOf { it.second }, lowerYoungest - 1)
            )
        }
        // 候选 3：lower 视为扰动/再沉积（离群），测量本身不改写
        candidates += CandidateAdjustment(
            candidateId = "$conflictId-c${++n}",
            description = "将 lower 样本标记为扰动/再沉积候选（离群），保留原测量",
            impliedChange = "flag-outlier:${lowerResult.measurementRef}"
        )
        // 候选 4：upper 视为混入
        candidates += CandidateAdjustment(
            candidateId = "$conflictId-c${++n}",
            description = "将 upper 样本标记为晚期混入候选（离群），保留原测量",
            impliedChange = "flag-outlier:${upperResult.measurementRef}"
        )
        return StratConflict(
            conflictId = conflictId,
            constraintRef = constraintRef,
            lowerResultRef = lowerResult.resultId,
            upperResultRef = upperResult.resultId,
            detail = "层序要求 ${lowerResult.measurementRef} 老于 ${upperResult.measurementRef}，但可信区间不兼容",
            candidates = candidates
        )
    }

    /** 返回 (startCalBp 老端, endCalBp 年轻端) 区间列表。 */
    fun credibleRanges(result: CalibrationResult): List<Pair<Int, Int>> {
        val widest = result.intervals.filter { it.level >= 0.9 }
        val use = if (widest.isNotEmpty()) widest else result.intervals
        if (use.isEmpty()) return result.modes.map { it.rangeStartCalBp to it.rangeEndCalBp }
        return use.map { it.startCalBp to it.endCalBp }
    }
}
