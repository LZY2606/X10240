package workbench

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

enum class EraSystem { CAL_BP, ASTRONOMICAL, BCE_CE }

@Serializable
data class C14Measurement(val ageBP: Double, val sigma: Double, val unit: String = "BP")

@Serializable
sealed class ReservoirOffset {
    @Serializable
    @SerialName("none")
    data object None : ReservoirOffset()

    @Serializable
    @SerialName("normal")
    data class Normal(val deltaR: Double, val sigma: Double) : ReservoirOffset()

    @Serializable
    @SerialName("mixture")
    data class Mixture(val components: List<Component>) : ReservoirOffset() {
        @Serializable
        data class Component(val weight: Double, val deltaR: Double, val sigma: Double)
    }
}

@Serializable
data class CurvePoint(val calBP: Double, val c14AgeBP: Double, val sigma: Double)

@Serializable
data class CalibrationCurve(
    val id: String,
    val name: String,
    val version: String,
    val points: List<CurvePoint>,
    val sourceRef: String,
    val contentHash: String
) {
    val minCalBP: Double get() = points.first().calBP
    val maxCalBP: Double get() = points.last().calBP
    val minC14: Double get() = points.minOf { it.c14AgeBP }
    val maxC14: Double get() = points.maxOf { it.c14AgeBP }

    /** Linear interpolation between knots; null outside the grid (no extrapolation). */
    fun at(calBP: Double): CurvePoint? {
        if (points.isEmpty() || calBP < minCalBP || calBP > maxCalBP) return null
        var lo = 0
        var hi = points.size - 1
        while (lo + 1 < hi) {
            val mid = (lo + hi) / 2
            if (points[mid].calBP <= calBP) lo = mid else hi = mid
        }
        val a = points[lo]
        val b = points[hi]
        if (b.calBP == a.calBP) return a
        val f = (calBP - a.calBP) / (b.calBP - a.calBP)
        return CurvePoint(calBP, a.c14AgeBP + f * (b.c14AgeBP - a.c14AgeBP), a.sigma + f * (b.sigma - a.sigma))
    }
}

@Serializable
data class Sample(
    val id: String,
    val labCode: String,
    val description: String,
    val measurement: C14Measurement,
    val reservoir: ReservoirOffset = ReservoirOffset.None,
    val eraSystem: EraSystem = EraSystem.CAL_BP,
    val sourceRef: String,
    val revision: Int = 1,
    val supersedes: String? = null,
    val importedAt: String = ""
)

@Serializable
data class SequenceConstraint(
    val id: String,
    val olderSampleId: String,
    val youngerSampleId: String,
    val note: String = "",
    val sourceRef: String,
    val active: Boolean = true
)

@Serializable
data class Diagnostic(val code: String, val message: String, val calBP: Double? = null)

@Serializable
data class Posterior(
    val gridMin: Double,
    val gridMax: Double,
    val step: Double,
    val density: List<Double>,
    val diagnostics: List<Diagnostic> = emptyList()
) {
    fun totalMass(): Double = density.sum() * step
}

@Serializable
data class CredibleInterval(val fromCalBP: Double, val toCalBP: Double, val probability: Double)

enum class RunResultStatus { OK, ERROR }
enum class Freshness { CURRENT, STALE }

@Serializable
data class CalibrationRun(
    val id: String,
    val sampleId: String,
    val sampleRevision: Int,
    val curveId: String,
    val curveName: String,
    val curveVersion: String,
    val reservoir: ReservoirOffset,
    val algorithmVersion: String,
    val fingerprint: String,
    val resultStatus: RunResultStatus,
    val posterior: Posterior? = null,
    val intervals: List<CredibleInterval> = emptyList(),
    val diagnostics: List<Diagnostic> = emptyList(),
    val inputEventId: String,
    val createdAt: String
)

@Serializable
data class SequenceConflict(
    val constraintId: String,
    val olderSampleId: String,
    val youngerSampleId: String,
    val reason: String
)

enum class SequenceStatus { CONSISTENT, AMBIGUOUS, CONTRADICTION, INSUFFICIENT_DATA }

@Serializable
data class SequenceRun(
    val id: String,
    val algorithmVersion: String,
    val fingerprint: String,
    val inputRunIds: List<String>,
    val constraintIds: List<String>,
    val status: SequenceStatus,
    /** Each candidate maps sampleId -> index into that sample's current-run interval list. */
    val candidates: List<Map<String, Int>> = emptyList(),
    val conflicts: List<SequenceConflict> = emptyList(),
    val diagnostics: List<Diagnostic> = emptyList(),
    val createdAt: String
)

enum class DecisionKind { ACCEPT_INTERVAL, REJECT_INTERVAL, EXCLUDE_SAMPLE, INCLUDE_SAMPLE, NOTE }
enum class DecisionStatus { ACTIVE, SUPERSEDED }
enum class TargetType { SAMPLE, INTERVAL, PROJECT }

@Serializable
data class Decision(
    val id: String,
    val kind: DecisionKind,
    val targetType: TargetType,
    val targetId: String,
    val sampleId: String? = null,
    val rationale: String,
    val sourceRef: String,
    val parallel: Boolean = false,
    val status: DecisionStatus = DecisionStatus.ACTIVE,
    val dependsOn: List<String> = emptyList(),
    val createdAt: String = ""
)

@Serializable
data class DecisionConflict(val decisionIds: List<String>, val reason: String)

@Serializable
data class IntervalEvidence(
    val runId: String,
    val fromCalBP: Double,
    val toCalBP: Double,
    val probability: Double,
    val gridPoints: Int,
    val riemannSum: String,
    val algorithmVersion: String,
    val fingerprint: String
)
