package workbench

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

val JsonFmt: Json = Json { prettyPrint = true; ignoreUnknownKeys = true }
val JsonCompact: Json = Json { ignoreUnknownKeys = true }

enum class SourceKind { MEASUREMENT, CURVE, OFFSET, ERA_SYSTEM, SEQUENCE, EXCLUSION }
enum class ResultStatus { CURRENT, STALE, SUPERSEDED }
enum class Verdict { ACCEPTED, REJECTED, ADJUSTED }

@Serializable
data class Provenance(
    val author: String,
    val role: String,
    val rationale: String,
    val sourceRef: String,
    val submittedTick: Long
)

@Serializable
data class InputRef(val sourceId: String, val version: Int)

@Serializable
data class CalibrationPayload(
    val measurementSourceId: String,
    val measurementVersion: Int,
    val labCode: String,
    val measuredR: Double,
    val measSigma: Double,
    val curveSourceId: String,
    val curveVersion: Int,
    val curveId: String,
    val curveCitation: String,
    val offsetSourceId: String? = null,
    val offsetVersion: Int? = null,
    val deltaR: Double = 0.0,
    val offsetSigma: Double = 0.0,
    val eraSystem: String,
    val gridStart: Int,
    val gridEnd: Int,
    val step: Int,
    val sigmaEff: Double,
    val totalSum: Double,
    val peaks: List<PeakEvidenceS>,
    val hpd68: List<List<Int>>,
    val hpd95: List<List<Int>>
)

@Serializable
data class PeakEvidenceS(
    val peakId: String,
    val tStartBP: Int,
    val tEndBP: Int,
    val step: Int,
    val rawSum: Double,
    val totalSum: Double,
    val mass: Double,
    val touchesBoundary: Boolean,
    val eraStart: String,
    val eraEnd: String,
    val crossesEraBoundary: Boolean
)

fun PeakEvidence.s() = PeakEvidenceS(
    peakId, tStartBP, tEndBP, step, rawSum, totalSum, mass,
    touchesBoundary, eraStart, eraEnd, crossesEraBoundary
)

@Serializable
data class SeqConflict(
    val constraint: String,
    val olderId: String,
    val youngerId: String,
    val olderDominantPeak: String,
    val youngerDominantPeak: String,
    val detail: String
)

@Serializable
data class SeqCandidate(
    val sampleId: String,
    val peakIds: List<String>,
    val note: String
)

@Serializable
data class SequenceCheckPayload(
    val sequenceSourceId: String,
    val sequenceVersion: Int,
    val calibrationResultIds: Map<String, String>,
    val conflicts: List<SeqConflict>,
    val candidates: List<SeqCandidate>,
    val unresolved: List<String>
)

// ---- DB 行 ----

data class SourceRow(val id: String, val kind: String, val createdSeq: Int, val createdTick: Long)
data class VersionRow(
    val sourceId: String, val version: Int, val parentVersion: Int?,
    val rawPayload: String, val provenance: String, val tick: Long
)
data class ResultRow(
    val id: String, val kind: String, val algoVersion: String, val fingerprint: String,
    val inputRefs: String, val payload: String, val status: String,
    val createdTick: Long, val createdSeq: Int
)
data class EventRow(val seq: Int, val tick: Long, val type: String, val payload: String)
data class DecisionRow(
    val id: String, val targetId: String, val verdict: String, val reviewer: String,
    val reason: String, val tick: Long, val status: String
)
