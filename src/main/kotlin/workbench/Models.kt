package workbench

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

const val ALGORITHM_VERSION = "radiocarbon-calendar-grid/v1.0"
const val CURVE_INTERPOLATION = "piecewise_linear_yearly_grid"
const val RESERVOIR_QUADRATURE = "gauss_hermite_5point"

val json: ObjectMapper = jacksonObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL)

data class CurvePoint(val calendarYear: Int, val radiocarbonAge: Double)

data class CurveVersion(
    val id: String,
    val label: String,
    val parentId: String? = null,
    val points: List<CurvePoint>,
    val sourceRef: String = "bundled-case"
)

data class Reservoir(
    val seriesId: String,
    val version: String,
    val parentVersion: String? = null,
    val label: String,
    val mean: Double,
    val sigma: Double,
    val unit: String,
    val sourceRef: String
)

data class Measurement(
    val seriesId: String,
    val version: String,
    val parentVersion: String? = null,
    val label: String,
    val rawAge: String,
    val rawSigma: String,
    val age: Double,
    val sigma: Double,
    val unit: String,
    val curveId: String,
    val reservoirSeriesId: String,
    val sourceRef: String,
    val rawText: String,
    val excluded: Boolean = false,
    val excludedBy: String? = null
)

data class SequenceConstraint(
    val id: String,
    val olderId: String,
    val youngerId: String,
    val sourceRef: String
)

data class Interval(
    val stableId: String,
    val level: String,
    val startYear: Int,
    val endYear: Int,
    val label: String,
    val probability: Double,
    val years: List<Int>,
    val integralBasis: String
)

data class ProbabilityPoint(val calendarYear: Int, val p: Double, val curveAge: Double)

data class Calibration(
    val stableId: String,
    val measurementSeriesId: String,
    val measurementVersion: String,
    val curveId: String,
    val reservoirSeriesId: String,
    val reservoirVersion: String,
    val algorithmVersion: String,
    val parameterFingerprint: String,
    val status: String,
    val intervals: List<Interval>,
    val probabilities: List<ProbabilityPoint>,
    val modes: List<Int>,
    val totalMass: Double,
    val diagnostic: String? = null,
    val integralEvidence: List<JsonNode> = emptyList(),
    val evidence: List<String> = emptyList()
)

data class AdjustmentCandidate(
    val kind: String,
    val targetId: String,
    val description: String,
    val preservesMeasurement: Boolean
)

data class SequenceStatus(
    val sequenceId: String,
    val olderId: String,
    val youngerId: String,
    val state: String,
    val olderCandidateYears: List<Int>,
    val youngerCandidateYears: List<Int>,
    val legalAdjustmentCandidates: List<AdjustmentCandidate>,
    val evidenceRefs: List<String>,
    val reason: String
)

data class Decision(
    val eventId: String,
    val seq: Long,
    val targetId: String,
    val sequenceId: String? = null,
    val action: String,
    val state: String,
    val selectedYears: List<List<Int>>,
    val rationale: String,
    val sourceRef: String,
    val undoneBy: String? = null,
    val parentEventId: String? = null
)

data class EventRecord(
    val eventId: String,
    val seq: Long,
    val at: String,
    val actor: String,
    val type: String,
    val payload: JsonNode,
    val parentEventId: String?,
    val causalHash: String
)

data class Finding(
    val code: String,
    val severity: String,
    val objectIds: List<String>,
    val eventId: String,
    val basis: JsonNode
)

data class ProjectState(
    val packageId: String? = null,
    val title: String? = null,
    val calendarEra: String? = null,
    val curves: Map<String, CurveVersion> = emptyMap(),
    val reservoirs: Map<String, Reservoir> = emptyMap(),
    val measurements: Map<String, Measurement> = emptyMap(),
    val sequences: Map<String, SequenceConstraint> = emptyMap(),
    val calibrations: List<Calibration> = emptyList(),
    val sequenceStatuses: List<SequenceStatus> = emptyList(),
    val decisions: List<Decision> = emptyList(),
    val findings: List<Finding> = emptyList(),
    val events: List<EventRecord> = emptyList()
)

class ValidationException(val diagnostics: List<String>) :
    RuntimeException(diagnostics.joinToString("; "))

data class ProjectExport(val format: String, val events: List<EventRecord>, val causalHash: String)
