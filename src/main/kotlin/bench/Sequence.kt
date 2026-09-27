package bench

data class CandidateSpec(val kind: String, val detail: String)

data class ConflictAnalysis(
    val constraintId: String,
    val olderId: String,
    val youngerId: String,
    val pConstraintHolds: Double,
    val conflict: Boolean,
    val candidateSpecs: List<CandidateSpec>
)

object SequenceAnalysis {
    const val CONFLICT_THRESHOLD = 0.5

    /** P(older 的日历年 > younger 的日历年)，两者在同一 calBP 网格上。 */
    fun probabilityOlderThan(older: List<Double>, younger: List<Double>): Double {
        var cum = 0.0
        var p = 0.0
        for (i in older.indices) {
            val py = younger[i]
            p += older[i] * (cum + 0.5 * py)
            cum += py
        }
        return p
    }

    /**
     * 层序矛盾产生候选调整而不改写测量；无法唯一决定时保留全部合法候选。
     */
    fun analyze(
        constraint: SequenceConstraint,
        older: CalibrationResult,
        younger: CalibrationResult
    ): ConflictAnalysis {
        val p = probabilityOlderThan(older.density, younger.density)
        val conflict = p < CONFLICT_THRESHOLD
        val specs = if (!conflict) emptyList() else {
            val youngerLo = younger.intervals.filter { it.level >= 0.95 }.minOfOrNull { it.loCalBp } ?: younger.gridFrom
            val olderHi = older.intervals.filter { it.level >= 0.95 }.maxOfOrNull { it.hiCalBp } ?: older.gridTo
            listOf(
                CandidateSpec("truncate-older",
                    "候选A：将 ${constraint.olderId} 的后验截断到 calBP > $youngerLo（${constraint.youngerId} 95.4% HPD 下界）并重归一化；原始测量不变。"),
                CandidateSpec("truncate-younger",
                    "候选B：将 ${constraint.youngerId} 的后验截断到 calBP < $olderHi（${constraint.olderId} 95.4% HPD 上界）并重归一化；原始测量不变。"),
                CandidateSpec("reexamine",
                    "候选C：约束与测量冲突无法唯一决定，保留全部合法候选；建议复核 ${constraint.olderId} 或 ${constraint.youngerId} 的实验室数据（污染/再沉积）。")
            )
        }
        return ConflictAnalysis(constraint.constraintId, constraint.olderId, constraint.youngerId, p, conflict, specs)
    }
}
