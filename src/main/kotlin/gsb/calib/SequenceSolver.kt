package gsb.calib

import gsb.model.*

/**
 * 层序求解器。对每个测量保留校准后的多峰后验；层序约束按“年代更老”方向
 * 计算联合可行质量。产生矛盾时给出候选调整（修剪区间 / 拒绝测量），
 * 但绝不改写测量本身。
 */
object SequenceSolver {
    const val ALGORITHM_VERSION = "sequence-feasibility-v1"
    const val CONFLICT_THRESHOLD = 0.05

    private fun densityMap(r: CalibrationResult): Map<Int, Double> {
        val m = HashMap<Int, Double>()
        for (i in r.density.p.indices) m[r.density.yearAt(i)] = r.density.p[i]
        return m
    }

    /** 日历“更老”= 天文纪年更小。约束 before 早于 after。 */
    fun solve(
        seq: SequencePayload,
        calibrations: Map<String, CalibrationResult>,
        provenance: Provenance,
        resultId: String,
        constraintNodeId: String
    ): SequenceResult {
        val candidates = mutableListOf<SequenceCandidate>()
        val details = mutableListOf<String>()
        var anyConflict = false
        var ci = 0

        for (edge in seq.edges) {
            val older = calibrations[edge.before]
            val younger = calibrations[edge.after]
            if (older == null || younger == null) {
                details += "约束 ${edge.before} < ${edge.after} 缺少校准结果，状态 UNKNOWN"
                continue
            }
            val pO = densityMap(older)
            val pY = densityMap(younger)
            // 可行：存在 (o,y) 使 o < y 的联合概率（独立后验）
            val yearsO = pO.keys.sorted()
            var feasible = 0.0
            var total = 0.0
            for (yo in yearsO) {
                val po = pO[yo]!!
                var pyOk = 0.0; var pyAll = 0.0
                for ((yy, py) in pY) { pyAll += py; if (yy > yo) pyOk += py }
                feasible += po * pyOk
                total += po * pyAll
            }
            val ratio = if (total <= 0) 0.0 else feasible / total

            if (ratio < CONFLICT_THRESHOLD) {
                anyConflict = true
                details += "层序矛盾：${edge.before} 应早于 ${edge.after}，可行联合概率 %.4f < %.2f（${edge.note}）"
                    .format(ratio, CONFLICT_THRESHOLD)
                // 候选 1：把“较年轻”样本的区间修剪为必须老于 older 的最小可能年
                val olderMinYear = older.density.gridMinYear
                val youngerMaxYear = younger.density.gridMaxYear
                ci++; candidates += SequenceCandidate(
                    id = "$resultId@cand$ci",
                    constraintId = constraintNodeId,
                    kind = "TRIM_YOUNGER",
                    targetMeasurementId = edge.after,
                    loYear = null,
                    hiYear = older.intervals.minOfOrNull { it.loYear } ?: olderMinYear,
                    feasibility = ratio,
                    rationale = "将 ${edge.after} 的日历候选限制为不晚于 ${edge.before}（仅修剪后验区间，测量值不变）",
                    sources = listOf(older.id, younger.id),
                    status = CandidateStatus.PROPOSED
                )
                // 候选 2：把“较老”样本修剪为更老
                ci++; candidates += SequenceCandidate(
                    id = "$resultId@cand$ci",
                    constraintId = constraintNodeId,
                    kind = "TRIM_OLDER",
                    targetMeasurementId = edge.before,
                    loYear = younger.intervals.maxOfOrNull { it.hiYear } ?: youngerMaxYear,
                    hiYear = null,
                    feasibility = ratio,
                    rationale = "将 ${edge.before} 的日历候选限制为不早于 ${edge.after}（仅修剪后验区间，测量值不变）",
                    sources = listOf(older.id, younger.id),
                    status = CandidateStatus.PROPOSED
                )
                // 候选 3：人工复核/可能拒绝测量关联（不改测量）
                ci++; candidates += SequenceCandidate(
                    id = "$resultId@cand$ci",
                    constraintId = constraintNodeId,
                    kind = "REJECT_MEASUREMENT",
                    targetMeasurementId = edge.after,
                    loYear = null, hiYear = null,
                    feasibility = ratio,
                    rationale = "若储库/上下文无法调和，建议人工把 ${edge.after} 标记为层序不可用（拒绝关联而非改写数值）",
                    sources = listOf(older.id, younger.id),
                    status = CandidateStatus.PROPOSED
                )
            } else {
                ci++; candidates += SequenceCandidate(
                    id = "$resultId@cand$ci",
                    constraintId = constraintNodeId,
                    kind = "NO_CHANGE",
                    targetMeasurementId = edge.after,
                    loYear = null, hiYear = null,
                    feasibility = ratio,
                    rationale = "约束 ${edge.before} 早于 ${edge.after} 可行（%.3f），多峰候选全部保留".format(ratio),
                    sources = listOf(older.id, younger.id),
                    status = CandidateStatus.PROPOSED
                )
            }
        }

        return SequenceResult(
            id = resultId,
            constraintNodeId = constraintNodeId,
            candidates = candidates,
            hasConflict = anyConflict,
            conflictDetails = details,
            provenance = provenance,
            status = ArtifactStatus.CURRENT
        )
    }
}
