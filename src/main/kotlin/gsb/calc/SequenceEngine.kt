package gsb.calc

import gsb.domain.*

data class SequenceInput(
    val constraint: SequenceConstraint,
    val fromRun: CalibrationRun,   // constraint.fromSampleId 的当前/指定校准
    val toRun: CalibrationRun
)

/**
 * 层序不是硬裁剪：用两个校准后分布计算满足/违背概率。
 * 矛盾时产出的是“候选调整”，绝不改写原测量；
 * 无法唯一决定（概率居中）时保留全部合法候选。
 */
class SequenceEngine {

    fun analyze(input: SequenceInput, id: String, eventSeq: Int, at: String): SequenceAnalysis {
        val c = input.constraint
        val a = input.fromRun; val b = input.toRun
        val step = a.gridStep
        val probs = jointProbability(a, b)
        var satisfy = 0.0; var violate = 0.0
        when (c.type) {
            SequenceType.BEFORE -> {
                // from 早于 to ⇔ calBP_from > calBP_to
                for (i in a.cells.indices) for (j in b.cells.indices) {
                    val w = probs[i][j]
                    if (a.cells[i].calBp > b.cells[j].calBp) satisfy += w
                    else if (a.cells[i].calBp < b.cells[j].calBp) violate += w
                }
            }
            SequenceType.CONTEMPORANEOUS -> {
                val w = c.windowCalBp ?: 0.0
                for (i in a.cells.indices) for (j in b.cells.indices) {
                    val pw = probs[i][j]
                    if (kotlin.math.abs(a.cells[i].calBp - b.cells[j].calBp) <= w) satisfy += pw
                    else violate += pw
                }
            }
        }
        val spacing = a.gridStep * 10 * b.gridStep * 10
        satisfy *= spacing; violate *= spacing
        val s = satisfy + violate
        if (s > 0) { satisfy /= s; violate /= s }

        val verdict = when {
            satisfy >= 0.90 -> SequenceVerdict.SATISFIED
            violate >= 0.90 -> SequenceVerdict.CONTRADICTED
            else -> SequenceVerdict.UNRESOLVED
        }

        val candidates = if (verdict == SequenceVerdict.SATISFIED) emptyList() else when (c.type) {
            SequenceType.BEFORE -> listOf(
                AdjustmentCandidate(
                    "cand-$id-offset", "OFFSET_SHIFT",
                    "检验更大储库偏移（+1σ）是否化解 ${a.measurementId} 早于 ${b.measurementId} 的矛盾",
                    mapOf("offsetId" to a.offsetId, "deltaMean14CYr" to "40"),
                    violate
                ),
                AdjustmentCandidate(
                    "cand-$id-exclude", "EXCLUDE_MODE",
                    "暂排除较老模式区间而非改写测量，重算层序概率",
                    mapOf("runId" to a.id, "exclude" to "OLDER_MODE"),
                    satisfy
                ),
                AdjustmentCandidate(
                    "cand-$id-revise", "REVISE_CONSTRAINT",
                    "将 ${fromLabel(input)} 改为同时性约束或撤回，由人工复核",
                    mapOf("constraintId" to c.id, "proposedType" to "CONTEMPORANEOUS"),
                    1.0 - kotlin.math.abs(satisfy - violate)
                ),
                AdjustmentCandidate(
                    "cand-$id-keep", "KEEP_ALL_LEGAL",
                    "证据不足唯一决定：保留全部合法日历候选，等待新材料",
                    mapOf("pSatisfy" to "%.4f".format(satisfy), "pViolate" to "%.4f".format(violate)),
                    1.0 - maxOf(satisfy, violate)
                )
            )
            SequenceType.CONTEMPORANEOUS -> listOf(
                AdjustmentCandidate(
                    "cand-$id-window", "REVISE_CONSTRAINT",
                    "扩大同时性窗口或撤回约束（测量不改写）",
                    mapOf("constraintId" to c.id, "proposedWindow" to ((c.windowCalBp ?: 0.0) + 50).toString()),
                    violate
                ),
                AdjustmentCandidate(
                    "cand-$id-keep", "KEEP_ALL_LEGAL",
                    "保留窗口内外全部合法候选",
                    mapOf("pSatisfy" to "%.4f".format(satisfy), "pViolate" to "%.4f".format(violate)),
                    1.0 - maxOf(satisfy, violate)
                )
            )
        }

        val fp = sha256Hex(
            listOf(
                SEQ_ALGORITHM_VERSION, c.id, c.type.name, (c.windowCalBp ?: 0.0).toString(),
                a.id, a.paramFingerprint, b.id, b.paramFingerprint
            ).joinToString("\n")
        )

        return SequenceAnalysis(
            id = id, eventSeq = eventSeq, constraintId = c.id,
            fromRunId = a.id, toRunId = b.id,
            verdict = verdict, probability = satisfy, reverseProbability = violate,
            algorithmVersion = SEQ_ALGORITHM_VERSION, paramFingerprint = fp,
            status = RunStatus.CURRENT, candidates = candidates, createdAt = at
        )
    }

    private fun fromLabel(i: SequenceInput) = "${i.constraint.fromSampleId} → ${i.constraint.toSampleId}"

    private fun jointProbability(a: CalibrationRun, b: CalibrationRun): Array<DoubleArray> {
        val na = a.cells.size; val nb = b.cells.size
        // 完整 na×nb 在网格 0..2600（步长1）下过大；按 10 年重采样保持概率与可重放性
        val sa = 10; val sb = 10
        val pa = resample(a, sa); val pb = resample(b, sb)
        val ia0 = a.cells.first().calBp; val ib0 = b.cells.first().calBp
        val m = Array(pa.size) { DoubleArray(pb.size) }
        for (i in pa.indices) for (j in pb.indices) {
            val calA = ia0 + i * a.gridStep * sa
            val calB = ib0 + j * b.gridStep * sb
            m[i][j] = pa[i] * pb[j]
        }
        return m
    }

    private fun resample(run: CalibrationRun, stride: Int): DoubleArray {
        val picked = (run.cells.indices step stride).map { run.cells[it].density }
        val s = picked.sum() * run.gridStep * stride
        return DoubleArray(picked.size) { if (s > 0) picked[it] else 0.0 }.also {
            if (s > 0) for (i in it.indices) it[i] /= s
        }
    }
}
