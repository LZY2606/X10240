package gsb.model

import kotlinx.serialization.Serializable

object InputKind {
    const val MEASUREMENT = "MEASUREMENT"
    const val OFFSET_DIST = "OFFSET_DIST"
    const val ERA_SYSTEM = "ERA_SYSTEM"
    const val STRAT_CONSTRAINT = "STRAT_CONSTRAINT"
    const val EXCLUSION = "EXCLUSION"
    const val CURVE = "CURVE"
}

/** A domain quantity always carries its unit / coordinate datum and the raw
 *  representation exactly as submitted. Normalization happens only in the
 *  derived layer; unconvertible values keep [value]/[sigma] null and carry a
 *  diagnostic instead of being dropped. */
@Serializable
data class Quantity(
    val raw: String,
    val unit: String,
    val value: Double? = null,
    val sigma: Double? = null,
    val datum: String? = null,
    val diagnostic: String? = null,
)

@Serializable
data class Measurement(
    val labCode: String,
    val age: Quantity,          // unit "14C yr BP"
    val material: String = "",
)

@Serializable
data class OffsetDist(
    val name: String,
    val deltaR: Quantity,       // unit "14C yr", mean +/- sigma
)

@Serializable
data class EraSystemChoice(val system: String) // "BCE_CE" | "CAL_BP"

@Serializable
data class StratConstraint(
    val constraintId: String,
    val olderSample: String,
    val youngerSample: String,
    val note: String = "",
)

@Serializable
data class Exclusion(
    val sampleId: String,
    val reason: String,
    val active: Boolean = true,
)

@Serializable
data class CurveKnot(val calBp: Double, val c14Age: Double, val sigma: Double)

@Serializable
data class CurveDef(
    val name: String,
    val version: String,
    val knots: List<CurveKnot>,
)

@Serializable
data class ModePeak(val calBp: Double, val density: Double, val mass: Double)

@Serializable
data class HpdInterval(
    val loBp: Double,
    val hiBp: Double,
    val mass: Double,
    val level: Double,
    val label: String,
)

@Serializable
data class CalibrationPayload(
    val sampleId: String,
    val offsetName: String,
    val eraSystem: String,
    val gridStartBp: Double,
    val gridStep: Double,
    val gridCount: Int,
    val densities: List<Double>,
    val modes: List<ModePeak>,
    val hpd68: List<HpdInterval>,
    val hpd95: List<HpdInterval>,
    val extrapolated: Boolean,
    val diagnostics: List<String> = emptyList(),
) {
    fun calBpAt(i: Int): Double = gridStartBp + i * gridStep
}

@Serializable
data class StratCandidate(
    val candidateId: String,
    val method: String,
    val description: String,
    val windows: Map<String, Pair<Double, Double>>,
    val orderProbability: Double,
)

@Serializable
data class StratConflictPayload(
    val constraintId: String,
    val olderSample: String,
    val youngerSample: String,
    val orderProbability: Double,
    val candidates: List<StratCandidate>,
    val status: String, // "OPEN" | "RESOLVED"
)
