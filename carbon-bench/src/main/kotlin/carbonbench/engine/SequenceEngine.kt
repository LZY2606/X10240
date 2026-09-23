package carbonbench.engine

import carbonbench.domain.CalibrationResult
import carbonbench.domain.CandidateAdjustment
import carbonbench.domain.PairSequenceResult
import carbonbench.domain.SequenceAnalysis
import carbonbench.domain.SequenceConstraint
import carbonbench.util.CanonicalJson
import carbonbench.util.Hashing
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class SequenceEngine(private val version: String = "seq-engine-1.0.0") {

    fun algorithmVersion(): String = version

    /**
     * 两个校准密度网格在同一步长下的成对后验（独立样本近似）：
     * P(older 早于 younger) = Σ pA(tA) pB(tB) tA>tB（cal BP 越大越早）。
     * 不压平、不丢弃并列（等概率网格对计入 undecided）。
     */
    fun pairProbability(older: CalibrationResult, younger: CalibrationResult): Triple<Double, Double, Double> {
        val ga = older.grid.filter { it.density > 0.0 }
        val gb = younger.grid.filter { it.density > 0.0 }
        val step = older.intervals68.firstOrNull()?.integralEvidence?.gridStep
            ?: older.intervals95.firstOrNull()?.integralEvidence?.gridStep ?: 2.0
        var pOlderFirst = 0.0
        var pYoungerFirst = 0.0
        var undecided = 0.0
        for (a in ga) {
            for (b in gb) {
                val mass = a.density * b.density * step * step
                when {
                    a.calBP > b.calBP + 1e-9 -> pOlderFirst += mass
                    a.calBP < b.calBP - 1e-9 -> pYoungerFirst += mass
                    else -> undecided += mass
                }
            }
        }
        val sum = pOlderFirst + pYoungerFirst + undecided
        if (sum <= 0.0) return Triple(0.0, 0.0, 0.0)
        return Triple(pOlderFirst / sum, pYoungerFirst / sum, undecided / sum)
    }

    fun analyze(
        id: String,
        projectId: String,
        calibrations: List<CalibrationResult>,
        constraints: List<SequenceConstraint>,
        nowIso: String
    ): SequenceAnalysis {
        val byLab = calibrations.filter { it.status == "current" }.associateBy { it.labCode }
        val pairs = mutableListOf<PairSequenceResult>()
        val candidates = mutableListOf<CandidateAdjustment>()

        for (c in constraints.filter { it.status == "asserted" }) {
            val older = byLab[c.olderLabCode]
            val younger = byLab[c.youngerLabCode]
            if (older == null || younger == null) {
                pairs.add(
                    PairSequenceResult(
                        constraintId = c.id,
                        olderLabCode = c.olderLabCode,
                        youngerLabCode = c.youngerLabCode,
                        probabilityOlderFirst = 0.0,
                        probabilityYoungerFirst = 0.0,
                        undecidedMass = 1.0,
                        verdict = "undecided"
                    )
                )
                candidates.add(
                    CandidateAdjustment(
                        id = "$id#cand-${c.id}-missing",
                        kind = "recheck-offset",
                        targetId = c.id,
                        description = "约束 ${c.id} 缺少当前校准密度（${c.olderLabCode}/${c.youngerLabCode}），补算后再判，测量保持不变",
                        preservesMeasurement = true
                    )
                )
                continue
            }
            val (pO, pY, und) = pairProbability(older, younger)
            val verdict = when {
                pO >= 0.95 -> "consistent"
                pY >= 0.95 -> "conflict"
                else -> "undecided"
            }
            pairs.add(
                PairSequenceResult(
                    constraintId = c.id,
                    olderLabCode = c.olderLabCode,
                    youngerLabCode = c.youngerLabCode,
                    probabilityOlderFirst = pO,
                    probabilityYoungerFirst = pY,
                    undecidedMass = und,
                    verdict = verdict
                )
            )
            when (verdict) {
                "conflict" -> candidates.add(
                    CandidateAdjustment(
                        id = "$id#cand-${c.id}-relax",
                        kind = "relax-constraint",
                        targetId = c.id,
                        description = "层序约束 ${c.id}（${c.olderLabCode} 早于 ${c.youngerLabCode}）后验支持相反方向 P=%.3f；候选：放宽/撤销该约束，或复核样本归属；测量值不改动".format(pY),
                        preservesMeasurement = true
                    )
                )
                "undecided" -> candidates.add(
                    CandidateAdjustment(
                        id = "$id#cand-${c.id}-tie",
                        kind = "add-constraint",
                        targetId = c.id,
                        description = "约束 ${c.id} 无法唯一决定（P(older-first)=%.3f，并列质量=%.3f）；保留全部合法候选，可增补地层证据".format(pO, und),
                        preservesMeasurement = true
                    )
                )
            }
        }

        val paramFp = Hashing.shortFingerprint(
            CanonicalJson.canonical(
                JsonObject(
                    mapOf(
                        "algorithm" to JsonPrimitive(version),
                        "calibrationIds" to JsonArray(calibrations.map { JsonPrimitive(it.id) }),
                        "constraintIds" to JsonArray(constraints.map { JsonPrimitive(it.id) }),
                        "calibrationFingerprints" to JsonArray(calibrations.map { JsonPrimitive(it.parameterFingerprint) })
                    )
                )
            )
        )

        return SequenceAnalysis(
            id = id,
            projectId = projectId,
            calibrationIds = calibrations.map { it.id },
            constraintIds = constraints.filter { it.status == "asserted" }.map { it.id },
            pairs = pairs,
            candidates = candidates,
            algorithmVersion = version,
            parameterFingerprint = paramFp,
            status = "current",
            computedAt = nowIso,
            directSources = (calibrations.map { it.id } + constraints.map { it.id }).distinct()
        )
    }
}
