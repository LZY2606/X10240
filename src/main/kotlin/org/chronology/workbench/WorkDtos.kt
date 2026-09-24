package org.chronology.workbench

import kotlinx.serialization.Serializable

@Serializable
data class CurvePayload(
    val curveId: String,
    val versionId: String,
    val parentVersionId: String? = null,
    val name: String,
    val points: List<CurvePoint>,
    val provenance: Provenance,
    val changeStartBP: Double? = null,
    val changeEndBP: Double? = null,
)

@Serializable
data class MeasurementPayload(
    val measurementId: String,
    val sampleId: String,
    val versionId: String,
    val parentVersionId: String? = null,
    val name: String,
    val age14CBP: Quantity,
    val sigma14CBP: Quantity,
    val provenance: Provenance,
)

@Serializable
data class ReservoirScenarioPayload(
    val hypothesisId: String,
    val offsetBP: Double,
    val sigmaBP: Double,
    val weight: Double,
    val label: String,
)

@Serializable
data class ReservoirPayload(
    val reservoirId: String,
    val versionId: String,
    val parentVersionId: String? = null,
    val name: String,
    val mixtureNormalization: String = "weights-sum-to-one",
    val scenarios: List<ReservoirScenarioPayload>,
    val provenance: Provenance,
)

@Serializable
data class EraSystemPayload(
    val eraSystemId: String,
    val versionId: String,
    val name: String,
    val calendarConvention: String,
    val boundaryCalendarBP: Double = 1950.0,
    val coordinateDatum: String = "CAL_BP_CE1950",
    val provenance: Provenance,
)

@Serializable
data class SequenceRelationPayload(
    val relationId: String,
    val versionId: String,
    val youngerSampleId: String,
    val olderSampleId: String,
    val rationale: String,
    val provenance: Provenance,
)

@Serializable
data class ExclusionPayload(
    val exclusionId: String,
    val versionId: String,
    val label: String,
    val startBP: Double,
    val endBP: Double,
    val unit: String = "CAL_BP_YEAR",
    val coordinateDatum: String = "CAL_BP_CE1950",
    val rationale: String,
    val provenance: Provenance,
)

@Serializable
data class CalibrationRequestPayload(
    val requestId: String,
    val sampleId: String,
    val measurementVersionId: String,
    val curveVersionId: String,
    val reservoirVersionId: String,
    val eraSystemVersionId: String? = null,
    val exclusionVersionIds: List<String> = emptyList(),
    val rationale: String = "",
)

@Serializable
data class SequenceRequestPayload(
    val requestId: String,
    val sequenceId: String,
    val relationVersionIds: List<String>,
    val calibrationRequestIds: List<String>,
    val rationale: String = "",
)

@Serializable
data class DecisionPayload(
    val decisionId: String,
    val action: String,
    val targetType: String,
    val targetId: String,
    val subjectId: String,
    val rationale: String,
    val conflictKey: String? = null,
)
