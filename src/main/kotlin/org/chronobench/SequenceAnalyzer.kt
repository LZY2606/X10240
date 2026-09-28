package org.chronobench

/**
 * 层序约束分析：枚举两个样本候选区间对的全部合法组合（older.end>=younger.start, calBP）。
 * - 存在合法组合 -> satisfied，给出联合概率质量与每一对贡献；
 * - 无合法组合 -> violated，产出候选调整建议（不允许自动改写测量，只生成供人工裁定的候选）；
 * - 任一缺候选/未知 -> unknown。
 * 这是纯派生层：输入不变则结果不变；它从不修改任何测量。
 */
object SequenceAnalyzer {
    private const val EPS = 1e-12

    fun analyze(
        projectId: String,
        link: SequenceLinkPayload,
        linkId: String,
        linkVersion: Int,
        older: CalibrationResult?,
        younger: CalibrationResult?,
        derivationId: String,
        at: Long,
        otherLinks: List<LinkAssessment> = emptyList()
    ): SequenceResult {
        val assessment = assess(link, linkId, linkVersion, older, younger)
        val sources = buildList {
            add("$linkId@v$linkVersion")
            older?.let { add("${it.measurementId}@v${it.measurementVersion}") }
            younger?.let { add("${it.measurementId}@v${it.measurementVersion}") }
        }
        val fp = Fingerprints.of(
            SequenceResult.ALGORITHM_VERSION,
            linkId, linkVersion.toString(),
            older?.paramFingerprint ?: "missing",
            younger?.paramFingerprint ?: "missing"
        )
        val overall = if (assessment.status == LinkAssessment.VIOLATED) SequenceResult.CONFLICT else SequenceResult.CONSISTENT
        return SequenceResult(
            derivationId = derivationId, projectId = projectId,
            algorithmVersion = SequenceResult.ALGORITHM_VERSION,
            paramFingerprint = fp, directSources = sources, computedAt = at,
            links = listOf(assessment), overallStatus = overall
        )
    }

    fun assess(
        link: SequenceLinkPayload,
        linkId: String,
        linkVersion: Int,
        older: CalibrationResult?,
        younger: CalibrationResult?
    ): LinkAssessment {
        val olderIv = older?.intervals?.filterNot { it.excluded } ?: emptyList()
        val youngerIv = younger?.intervals?.filterNot { it.excluded } ?: emptyList()
        if (older == null || younger == null || older.modeCount == 0 || younger.modeCount == 0) {
            return LinkAssessment(
                linkId, linkVersion, link.olderSampleCode, link.youngerSampleCode, link.relation,
                satisfied = false, probability = 0.0, legalCombinations = emptyList(),
                suggestedAdjustments = listOf("层序两端样本缺少可用校准候选，状态保持未知，不改写测量"),
                status = LinkAssessment.UNKNOWN
            )
        }
        val legal = ArrayList<IntervalPair>()
        for (o in olderIv) {
            for (y in youngerIv) {
                val ok = when (link.relation) {
                    "same_layer" -> overlap(o, y) > EPS
                    else -> o.endCalBP >= y.startCalBP
                }
                if (ok) legal += IntervalPair(o.intervalId, y.intervalId, o.mass * y.mass)
            }
        }
        val prob = legal.sumOf { it.jointProbability }
        val violated = prob <= EPS
        val suggestions = if (violated) buildSuggestions(link, older, younger, olderIv, youngerIv) else emptyList()
        return LinkAssessment(
            linkId, linkVersion, link.olderSampleCode, link.youngerSampleCode, link.relation,
            satisfied = !violated, probability = prob,
            legalCombinations = legal.sortedByDescending { it.jointProbability },
            suggestedAdjustments = suggestions,
            status = if (violated) LinkAssessment.VIOLATED else LinkAssessment.SATISFIED
        )
    }

    private fun overlap(a: CalibratedInterval, b: CalibratedInterval): Double {
        val lo = maxOf(a.endCalBP, b.endCalBP)
        val hi = minOf(a.startCalBP, b.startCalBP)
        return maxOf(0, hi - lo).toDouble()
    }

    /**
     * 冲突时只产出“候选调整”：要么人工作出排除决定，要么接受储库偏移假设的变更，
     * 要么放宽/移除约束；绝不自动改写测量年龄或标准差。
     */
    private fun buildSuggestions(
        link: SequenceLinkPayload,
        older: CalibrationResult,
        younger: CalibrationResult,
        olderIv: List<CalibratedInterval>,
        youngerIv: List<CalibratedInterval>
    ): List<String> {
        val out = mutableListOf<String>()
        val oOldest = olderIv.maxByOrNull { it.startCalBP }
        val yYoungest = youngerIv.minByOrNull { it.endCalBP }
        out += "候选调整A：人工排除 ${link.youngerSampleCode} 早于约束界限的区间（需要排除决定，不修改测量）"
        out += "候选调整B：人工排除 ${link.olderSampleCode} 过年轻的区间（需要排除决定，不修改测量）"
        val gap = (yYoungest?.startCalBP ?: 0) - (oOldest?.endCalBP ?: 0)
        out += "候选调整C：复查 ${link.youngerSampleCode} 的储库偏移假设（最小约需 %d 放射性碳年的差异，以新版本偏移假设提交，不改测量）".format(gap)
        out += "候选调整D：人工裁定层序约束 ${link.label} 不适用/修订（以新版本 SEQUENCE_LINK 提交）"
        return out
    }
}
