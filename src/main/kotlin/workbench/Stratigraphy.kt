package workbench

object Stratigraphy {
    const val ALGO_VERSION = "strat-check-1.0.0"

    /**
     * BEFORE(a, b)：a 必须比 b 更老（calBP 更大）。
     * 冲突时产生候选调整（仅约束候选区间），绝不改写测量本身；
     * 无法唯一决定时保留全部合法候选。
     */
    fun check(
        id: String,
        constraints: List<ConstraintV>,
        resultsByMeasurement: Map<String, CalibrationResult>,
        idGen: () -> String
    ): StratCheckResult {
        val conflicts = mutableListOf<StratConflict>()
        val used = mutableMapOf<String, String>()
        val checked = mutableListOf<String>()
        for (c in constraints.filter { it.kind == "BEFORE" }) {
            val ra = resultsByMeasurement[c.aMeasurementId]
            val rb = resultsByMeasurement[c.bMeasurementId]
            checked.add(c.id)
            if (ra == null || rb == null || ra.status != "OK" || rb.status != "OK") continue
            used[c.aMeasurementId] = ra.id
            used[c.bMeasurementId] = rb.id
            // 合法配对：存在 peakA.toCalBp > peakB.fromCalBp（A 的某段整体老于 B 的某段的年轻端）
            val legalPairs = mutableListOf<Pair<CalPeak, CalPeak>>()
            for (pa in ra.peaks) for (pb in rb.peaks) {
                if (pa.toCalBp > pb.fromCalBp) legalPairs.add(pa to pb)
            }
            val allPairsLegal = ra.peaks.isNotEmpty() && rb.peaks.isNotEmpty() &&
                legalPairs.size == ra.peaks.size * rb.peaks.size
            if (allPairsLegal) continue
            val adjustments = mutableListOf<CandidateAdjustment>()
            if (legalPairs.isEmpty()) {
                // 完全冲突：保留双方全部峰作为待裁定候选，不删除任何可能
                for (pa in ra.peaks) {
                    adjustments.add(
                        CandidateAdjustment(
                            id = idGen(), measurementId = c.aMeasurementId,
                            fromCalBp = pa.fromCalBp, toCalBp = pa.toCalBp,
                            basis = "约束 ${c.id} 与结果 ${ra.id}#p${pa.index} 完全冲突；保留为待裁定候选（测量未被改写）"
                        )
                    )
                }
                for (pb in rb.peaks) {
                    adjustments.add(
                        CandidateAdjustment(
                            id = idGen(), measurementId = c.bMeasurementId,
                            fromCalBp = pb.fromCalBp, toCalBp = pb.toCalBp,
                            basis = "约束 ${c.id} 与结果 ${rb.id}#p${pb.index} 完全冲突；保留为待裁定候选（测量未被改写）"
                        )
                    )
                }
            } else {
                val seen = mutableSetOf<String>()
                for ((pa, pb) in legalPairs) {
                    if (seen.add("A${pa.index}")) {
                        adjustments.add(
                            CandidateAdjustment(
                                id = idGen(), measurementId = c.aMeasurementId,
                                fromCalBp = pa.fromCalBp, toCalBp = pa.toCalBp,
                                basis = "约束 ${c.id}：${ra.id}#p${pa.index} 与 ${rb.id}#p${pb.index} 可共存，列为合法候选"
                            )
                        )
                    }
                    if (seen.add("B${pb.index}")) {
                        adjustments.add(
                            CandidateAdjustment(
                                id = idGen(), measurementId = c.bMeasurementId,
                                fromCalBp = pb.fromCalBp, toCalBp = pb.toCalBp,
                                basis = "约束 ${c.id}：${rb.id}#p${pb.index} 与 ${ra.id}#p${pa.index} 可共存，列为合法候选"
                            )
                        )
                    }
                }
            }
            val desc = if (legalPairs.isEmpty()) {
                "约束 ${c.id}（${c.aMeasurementId} 早于 ${c.bMeasurementId}）与当前校准结果完全矛盾：" +
                    "${c.aMeasurementId} 最老候选 ${ra.peaks.maxOf { it.toCalBp }} calBP ≤ " +
                    "${c.bMeasurementId} 最年轻候选 ${rb.peaks.minOf { it.fromCalBp }} calBP"
            } else {
                "约束 ${c.id}（${c.aMeasurementId} 早于 ${c.bMeasurementId}）部分冲突：" +
                    "存在 ${legalPairs.size} 组合法峰配对，其余峰组合被排除；测量保持不变"
            }
            conflicts.add(StratConflict(c.id, desc, adjustments))
        }
        return StratCheckResult(id, checked, conflicts, used)
    }
}
