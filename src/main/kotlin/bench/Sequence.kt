package bench

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

/** A pairwise stratigraphic relation: [olderKey] must calibrate to an older
 *  calendar year (larger calBP) than [youngerKey]. Keys are measurement input keys. */
data class SequenceConstraint(
    val inputKey: String,
    val older: String,
    val younger: String,
    val note: String
)

data class SequenceConflict(
    val constraintKey: String,
    val older: String,
    val younger: String,
    val violatedMass: Double,   // posterior mass of the older sample falling younger than the younger sample's bulk
    val detail: String
)

/** A legal candidate boundary: both samples keep at least [minRetained] posterior
 *  mass on their permitted side. All legal candidates are preserved; the engine
 *  never rewrites the underlying measurements. */
data class CandidateAdjustment(
    val constraintKey: String,
    val boundaryCalBp: Double,
    val boundaryEra: String,
    val olderRetained: Double,
    val youngerRetained: Double
)

data class SequenceEvaluation(
    val conflicts: List<SequenceConflict>,
    val candidates: List<CandidateAdjustment>,
    val satisfiable: List<String>   // constraint keys fully satisfied as-is
)

object SequenceEngine {
    const val MIN_RETAINED = 0.05

    fun evaluate(
        constraints: List<SequenceConstraint>,
        calibrations: Map<String, CalibrationOutcome>
    ): SequenceEvaluation {
        val conflicts = ArrayList<SequenceConflict>()
        val candidates = ArrayList<CandidateAdjustment>()
        val ok = ArrayList<String>()
        for (c in constraints) {
            val older = calibrations[c.older] ?: continue
            val younger = calibrations[c.younger] ?: continue
            // Posterior mass of the "older" sample that is younger than the
            // "younger" sample's 95% lower edge signals a conflict.
            val youngerLo = younger.intervals95.minOf { it.fromCalBp }
            val violated = massBelow(older, youngerLo)
            if (violated > 0.5) {
                conflicts.add(
                    SequenceConflict(
                        constraintKey = c.inputKey,
                        older = c.older,
                        younger = c.younger,
                        violatedMass = violated,
                        detail = "measurement ${c.older} places ${"%.3f".format(violated)} of posterior younger than ${c.younger}'s 95% lower edge (${youngerLo} calBP)"
                    )
                )
                candidates.addAll(legalBoundaries(c, older, younger))
            } else {
                ok.add(c.inputKey)
            }
        }
        return SequenceEvaluation(conflicts, candidates, ok)
    }

    /** Every grid year where both sides keep >= MIN_RETAINED mass is a legal candidate. */
    private fun legalBoundaries(
        c: SequenceConstraint,
        older: CalibrationOutcome,
        younger: CalibrationOutcome
    ): List<CandidateAdjustment> {
        val out = ArrayList<CandidateAdjustment>()
        val grid = older.grid
        for (t in grid) {
            val olderRetained = massAbove(older, t)
            val youngerRetained = massBelow(younger, t)
            if (olderRetained >= MIN_RETAINED && youngerRetained >= MIN_RETAINED) {
                out.add(
                    CandidateAdjustment(
                        constraintKey = c.inputKey,
                        boundaryCalBp = t,
                        boundaryEra = calBpToEra(t),
                        olderRetained = olderRetained,
                        youngerRetained = youngerRetained
                    )
                )
            }
        }
        return out
    }

    private fun massAbove(o: CalibrationOutcome, t: Double): Double {
        var sum = 0.0
        val g = o.grid
        val d = o.density
        for (i in 0 until g.size - 1) {
            if (g[i + 1] <= t) continue
            if (g[i] >= t) sum += (d[i] + d[i + 1]) * 0.5 * (g[i + 1] - g[i])
            else {
                val f = (t - g[i]) / (g[i + 1] - g[i])
                val dt = d[i] + f * (d[i + 1] - d[i])
                sum += (dt + d[i + 1]) * 0.5 * (g[i + 1] - t)
            }
        }
        return sum
    }

    private fun massBelow(o: CalibrationOutcome, t: Double): Double = 1.0 - massAbove(o, t)
}

fun SequenceEvaluation.toJson(): JsonObject = buildJsonObject {
    put("conflicts", buildJsonArray {
        for (c in conflicts) add(buildJsonObject {
            put("constraint", JsonPrimitive(c.constraintKey))
            put("older", JsonPrimitive(c.older))
            put("younger", JsonPrimitive(c.younger))
            put("violatedMass", JsonPrimitive(c.violatedMass))
            put("detail", JsonPrimitive(c.detail))
        })
    })
    put("candidates", buildJsonArray {
        for (c in candidates) add(buildJsonObject {
            put("constraint", JsonPrimitive(c.constraintKey))
            put("boundaryCalBp", JsonPrimitive(c.boundaryCalBp))
            put("boundaryEra", JsonPrimitive(c.boundaryEra))
            put("olderRetained", JsonPrimitive(c.olderRetained))
            put("youngerRetained", JsonPrimitive(c.youngerRetained))
        })
    })
    put("satisfiable", buildJsonArray { satisfiable.forEach { add(JsonPrimitive(it)) } })
}
