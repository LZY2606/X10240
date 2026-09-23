package gsb

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.MessageDigest

val jsonFmt: Json = Json { encodeDefaults = true; ignoreUnknownKeys = true; prettyPrint = false }
val jsonPretty: Json = Json { encodeDefaults = true; ignoreUnknownKeys = true; prettyPrint = true }

fun sha256(text: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    return digest.digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}

object SourceKind {
    const val MEASUREMENT = "MEASUREMENT"
    const val CURVE = "CURVE"
    const val OFFSET = "OFFSET"
    const val ERA_SYSTEM = "ERA_SYSTEM"
    const val SEQUENCE = "SEQUENCE"
    const val EXCLUSION = "EXCLUSION"
    val ALL = listOf(MEASUREMENT, CURVE, OFFSET, ERA_SYSTEM, SEQUENCE, EXCLUSION)
}

object DerivedKind {
    const val CALIBRATION = "CALIBRATION"
    const val SEQUENCE_CHECK = "SEQUENCE_CHECK"
}

object EventType {
    const val IMPORT_SOURCE = "IMPORT_SOURCE"
    const val REVISE_SOURCE = "REVISE_SOURCE"
    const val CALIBRATE = "CALIBRATE"
    const val SEQUENCE_CHECK = "SEQUENCE_CHECK"
    const val ADJUDICATE = "ADJUDICATE"
    const val UNDO = "UNDO"
    const val IMPORT_PACK = "IMPORT_PACK"
    const val EXPORT = "EXPORT"
    const val IMPORT_FILE = "IMPORT_FILE"
}

object Verdict {
    const val ACCEPTED = "ACCEPTED"
    const val REJECTED = "REJECTED"
    const val PARAM_ADJUST = "PARAM_ADJUST"
}

@Serializable
data class MeasurementData(
    val labCode: String,
    val bpAge: Double,
    val sigma: Double,
    val material: String = "",
    val note: String = ""
)

@Serializable
data class CurvePoint(val calBp: Double, val c14Age: Double, val sigma: Double)

@Serializable
data class CurveData(val name: String, val curveVersion: String, val points: List<CurvePoint>)

@Serializable
data class OffsetData(val label: String, val deltaR: Double, val deltaRSigma: Double, val note: String = "")

@Serializable
data class EraSystemData(val convention: String, val presentYear: Int = 1950)

@Serializable
data class SequenceData(val beforeSourceId: String, val afterSourceId: String, val note: String = "")

@Serializable
data class ExclusionData(val targetSourceId: String, val excluded: Boolean, val reason: String)

/** One immutable version of a source input. Raw representation is never overwritten;
 *  revisions are new versions linked by parentId. */
@Serializable
data class SourceVersion(
    val id: String,
    val sourceId: String,
    val kind: String,
    val version: Int,
    val parentId: String?,
    val unit: String,
    val provenance: String,
    val raw: String,
    val dataJson: String?,
    val diagnostics: List<String> = emptyList(),
    val createdSeq: Long
)

/** A derived result. Carries algorithm version, parameter fingerprint and direct sources. */
@Serializable
data class DerivedRecord(
    val id: String,
    val kind: String,
    val algoVersion: String,
    val fingerprint: String,
    val deps: List<String>,
    val payload: String,
    val createdSeq: Long
)

@Serializable
data class EventRecord(
    val seq: Long,
    val id: String,
    val type: String,
    val actor: String,
    val at: String,
    val payload: String
)

@Serializable
data class ExportBundle(
    val format: String,
    val formatVersion: Int,
    val sourceVersions: List<SourceVersion>,
    val derived: List<DerivedRecord>,
    val events: List<EventRecord>
)

// ---- calibration result payloads ----

@Serializable
data class DensityPoint(val calBp: Double, val density: Double)

@Serializable
data class Peak(val calBp: Double, val density: Double, val eraLabel: String)

@Serializable
data class CalInterval(
    val fromCalBp: Double,
    val toCalBp: Double,
    val mass: Double,
    val fromEra: String,
    val toEra: String
)

@Serializable
data class CalibrationResult(
    val labCode: String,
    val measurementSourceId: String,
    val curveRef: String,
    val offsetLabel: String?,
    val deltaR: Double,
    val deltaRSigma: Double,
    val effectiveMu: Double,
    val effectiveSigma: Double,
    val gridStep: Double,
    val gridFrom: Double,
    val gridTo: Double,
    val normalization: Double,
    val points: List<DensityPoint>,
    val peaks: List<Peak>,
    val intervals682: List<CalInterval>,
    val intervals954: List<CalInterval>,
    val diagnostics: List<String>
)

@Serializable
data class IntegralEvidence(
    val derivedId: String,
    val level: String,
    val intervalIndex: Int,
    val interval: CalInterval,
    val gridStep: Double,
    val nGridPoints: Int,
    val rawMass: Double,
    val normalization: Double,
    val probability: Double,
    val curveRef: String,
    val curveSegment: List<CurvePoint>,
    val interpolation: String,
    val offsetAssumption: String,
    val algoVersion: String,
    val fingerprint: String
)

@Serializable
data class SequenceCandidate(val kind: String, val description: String)

@Serializable
data class SequenceCheckResult(
    val constraintSourceId: String,
    val beforeSourceId: String,
    val afterSourceId: String,
    val beforeCalibrationId: String,
    val afterCalibrationId: String,
    val pOrder: Double,
    val conflict: Boolean,
    val status: String,
    val candidates: List<SequenceCandidate>,
    val note: String
)

@Serializable
data class AdjudicatePayload(val derivedId: String, val verdict: String, val reason: String)

@Serializable
data class UndoPayload(val targetSeq: Long, val reason: String)
