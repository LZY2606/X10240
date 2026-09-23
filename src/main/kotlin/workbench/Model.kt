package workbench

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

val JSON = Json { prettyPrint = false; ignoreUnknownKeys = true; encodeDefaults = true }

@Serializable
data class Measurement(
    val id: String, val groupId: String, val labCode: String,
    val c14AgeBp: Double, val sigma: Double, val eraSystem: String,
    val sourceRef: String, val version: Int, val parentId: String?,
    val createdSeq: Long, val createdAt: String, val rawJson: String = "",
)

@Serializable
data class Curve(
    val id: String, val groupId: String, val name: String, val version: Int,
    val knots: List<KnotSer>, val sourceRef: String, val parentId: String?,
    val createdSeq: Long, val createdAt: String,
) {
    fun toKnots() = CurveKnots(knots.map { Knot(it.calBP, it.c14, it.sigma) })
}

@Serializable
data class KnotSer(val calBP: Double, val c14: Double, val sigma: Double)

@Serializable
data class Offset(
    val id: String, val groupId: String, val name: String,
    val mean: Double, val sigma: Double, val version: Int,
    val sourceRef: String, val parentId: String?,
    val createdSeq: Long, val createdAt: String,
)

@Serializable
data class Constraint(
    val id: String, val groupId: String, val olderId: String, val youngerId: String,
    val note: String, val sourceRef: String, val version: Int, val parentId: String?,
    val createdSeq: Long, val createdAt: String,
)

@Serializable
data class IntervalSer(
    val fromBP: Double, val toBP: Double, val probability: Double, val level: Double,
    val gridFrom: Int, val gridTo: Int, val rawWeightSum: Double,
    val fromEra: String, val toEra: String,
)

@Serializable
data class DistSer(
    val startBP: Double, val step: Double, val normConstant: Double,
    val clampedLow: Boolean, val clampedHigh: Boolean, val probs: List<Double>,
)

@Serializable
data class CalResult(
    val id: String, val measurementId: String, val curveId: String, val offsetId: String,
    val algoVersion: String, val fingerprint: String,
    val dist: DistSer, val intervals: List<IntervalSer>,
    val createdSeq: Long, val createdAt: String,
) {
    fun toDist() = CalDistribution(dist.startBP, dist.step, dist.probs.toDoubleArray(), dist.normConstant, dist.clampedLow, dist.clampedHigh)
}

@Serializable
data class Candidate(
    val id: String, val kind: String, val refId: String, val payloadJson: String,
    val status: String, // open | accepted | rejected
    val createdSeq: Long, val createdAt: String,
)

@Serializable
data class Decision(
    val id: String, val targetKind: String, val targetId: String, val action: String,
    val reason: String, val sourceRef: String, val reverted: Boolean,
    val createdSeq: Long, val createdAt: String,
)

@Serializable
data class Event(
    val id: String, val seq: Long, val ts: String, val type: String, val payloadJson: String,
)

@Serializable
data class Diagnostic(
    val id: String, val source: String, val message: String, val rawValue: String,
    val createdSeq: Long, val createdAt: String,
)
