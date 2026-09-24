package org.chronobench.domain

import kotlinx.serialization.Serializable

/** Every displayed value carries a stable identity; serializable payloads are canonical JSON. */

@Serializable
data class Quantity(
    val raw: String,
    val value: Double? = null,
    val unit: String,
    val diagnostics: List<String> = emptyList()
) {
    val convertible: Boolean get() = value != null
}

@Serializable
data class OriginRef(val label: String, val locator: String)

interface Sourced {
    val id: String
    val version: String
    val parentVersion: String?
    val origin: OriginRef
    val createdSeq: Long
}

@Serializable
data class CurvePoint(val calBP: Int, val c14BP: Double, val c14Sigma: Double) {
    fun pointId(curveId: String, curveVersion: String): String = "$curveId@$curveVersion#p$calBP"
}

@Serializable
data class CalCurve(
    override val id: String,
    override val version: String,
    override val parentVersion: String? = null,
    override val origin: OriginRef,
    override val createdSeq: Long = 0,
    val name: String,
    val points: List<CurvePoint>
) : Sourced {
    val tMin: Int get() = points.minOf { it.calBP }
    val tMax: Int get() = points.maxOf { it.calBP }
    fun pointAt(t: Int): CurvePoint? = points.firstOrNull { it.calBP == t }
}

@Serializable
data class Measurement(
    override val id: String,
    override val version: String,
    override val parentVersion: String? = null,
    override val origin: OriginRef,
    override val createdSeq: Long = 0,
    val labCode: String,
    val material: String,
    val age: Quantity,
    val sigma: Quantity
) : Sourced

@Serializable
data class OffsetComponent(val meanBP: Double, val sigmaBP: Double, val weight: Double)

@Serializable
data class ReservoirHypothesis(
    override val id: String,
    override val version: String,
    override val parentVersion: String? = null,
    override val origin: OriginRef,
    override val createdSeq: Long = 0,
    val label: String,
    val components: List<OffsetComponent>
) : Sourced {
    init { require(components.isNotEmpty()) }
}

@Serializable
data class YearSystem(
    override val id: String,
    override val version: String,
    override val parentVersion: String? = null,
    override val origin: OriginRef,
    override val createdSeq: Long = 0,
    val name: String,
    val scheme: String,
    val anchorCalBP: Int = 1950
) : Sourced

@Serializable
data class SequenceEdge(
    override val id: String,
    override val version: String,
    override val parentVersion: String? = null,
    override val origin: OriginRef,
    override val createdSeq: Long = 0,
    val olderMeasurementId: String,
    val youngerMeasurementId: String,
    val rationale: String
) : Sourced

@Serializable
data class ExclusionRule(
    override val id: String,
    override val version: String,
    override val parentVersion: String? = null,
    override val origin: OriginRef,
    override val createdSeq: Long = 0,
    val targetMeasurementId: String,
    val reason: String
) : Sourced

enum class CalStatus { OK, BOUNDARY_TRUNCATED, UNKNOWN }

@Serializable
data class GridPoint(val calBP: Int, val p: Double)

@Serializable
data class Peak(val calBP: Int, val p: Double, val modeLikelihood: Double)

@Serializable
data class InterpSighting(
    val calBP: Int,
    val pointId: String,
    val curveC14BP: Double,
    val curveSigma: Double,
    val offsetShiftedAge: Double,
    val likelihood: Double
)

@Serializable
data class IntegrationEvidence(
    val gridStepYears: Int,
    val gridSteps: Int,
    val rawSum: Double,
    val normalizer: Double,
    val normalizedMass: Double,
    val sightings: List<InterpSighting>
)

@Serializable
data class CalendarInterval(
    val id: String,
    val rank: Int,
    val loCalBP: Int,
    val hiCalBP: Int,
    val mass: Double,
    val evidence: IntegrationEvidence,
    val crossesBceCe: Boolean
)

@Serializable
data class InputRefs(
    val measurementId: String,
    val measurementVersion: String,
    val curveId: String,
    val curveVersion: String,
    val reservoirHypothesisId: String?,
    val reservoirHypothesisVersion: String?,
    val yearSystemId: String,
    val yearSystemVersion: String
)

@Serializable
data class Calibration(
    val id: String,
    val projectId: String,
    val inputs: InputRefs,
    val algorithmVersion: String,
    val fingerprint: String,
    val status: CalStatus,
    val diagnostics: List<String>,
    val grid: List<GridPoint>,
    val peaks: List<Peak>,
    val intervals95: List<CalendarInterval>,
    val createdSeq: Long,
    val adopted: Boolean = true
) {
    val meanCalBP: Double? get() = null // multimodal posteriors are never collapsed to one year
}

enum class OrderStatus { VALID, CONFLICT, AMBIGUOUS, UNKNOWN }

@Serializable
data class Candidate(
    val id: String,
    val kind: String,
    val description: String,
    val targetMeasurementId: String,
    val suggestedHypothesisId: String? = null,
    val suggestedExclusionRuleId: String? = null,
    val legal: Boolean,
    val rationale: String
)

@Serializable
data class SequenceCheck(
    val id: String,
    val projectId: String,
    val edgeId: String,
    val edgeVersion: String,
    val olderCalibrationId: String,
    val youngerCalibrationId: String,
    val status: OrderStatus,
    val pOlderBeforeYounger: Double,
    val candidates: List<Candidate>,
    val measurementFingerprintBefore: String,
    val measurementFingerprintAfter: String,
    val createdSeq: Long
)

enum class DecisionKind {
    ACCEPT_INTERVAL, REJECT_INTERVAL, SELECT_HYPOTHESIS,
    CONFIRM_CANDIDATE, EXCLUDE_MEASUREMENT, OVERRIDE_CONFLICT
}

@Serializable
data class Decision(
    val id: String,
    val projectId: String,
    val kind: DecisionKind,
    val subjectCalibrationId: String? = null,
    val intervalId: String? = null,
    val hypothesisId: String? = null,
    val measurementId: String? = null,
    val candidateId: String? = null,
    val note: String,
    val createdSeq: Long,
    val undoneSeq: Long? = null
)

enum class EventType {
    PROJECT_CREATED, SOURCES_IMPORTED, IMPORT_REJECTED, CALIBRATION_COMPUTED,
    SEQUENCE_CHECKED, CANDIDATES_PROPOSED, DECISION_MADE, DECISION_UNDONE,
    SOURCE_REVISED, CURVE_UPGRADED, RESULT_MARKED_STALE,
    PROBE_RECORDED, VERDICT_ISSUED, PROJECT_EXPORTED, NOTE
}

@Serializable
data class Event(
    val id: String,
    val projectId: String,
    val seq: Long,
    val type: EventType,
    val summary: String,
    val payloadJson: String,
    val atTime: String
)

enum class ProbeStatus { PASS, FAIL }

@Serializable
data class Probe(
    val id: String,
    val projectId: String,
    val scenario: String,
    val changedDirectSource: String,
    val changeDescription: String,
    val expectedAffectedIds: List<String>,
    val affectedIds: List<String>,
    val unaffectedIds: List<String>,
    val confined: Boolean,
    val status: ProbeStatus,
    val adoptedCalibrationId: String?,
    val supersededCalibrationId: String?,
    val verdictEventIds: List<String>,
    val createdSeq: Long,
    val detailJson: String
)

@Serializable
data class Project(
    val id: String,
    val name: String,
    val kind: String,
    val createdAt: String,
    val note: String = ""
)
