package rgsb.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Append-only event log. Original inputs are immutable; revisions are expressed as new
 * events with a parent reference. Every derived result records algorithm version, a
 * parameter fingerprint and direct sources, so any historical view can be replayed.
 */
@Serializable
sealed class EventPayload {
    @Serializable @SerialName("project_created")
    data class ProjectCreated(val projectId: String, val name: String, val seed: Long) : EventPayload()

    @Serializable @SerialName("era_defined")
    data class EraDefined(val era: EraSystem) : EventPayload()

    @Serializable @SerialName("curve_installed")
    data class CurveInstalled(val curve: CalibrationCurve) : EventPayload()

    @Serializable @SerialName("measurement_submitted")
    data class MeasurementSubmitted(val measurement: RawMeasurement) : EventPayload()

    @Serializable @SerialName("offset_submitted")
    data class OffsetSubmitted(val offset: OffsetSpec) : EventPayload()

    @Serializable @SerialName("sequence_submitted")
    data class SequenceSubmitted(val sequence: SequenceSpec) : EventPayload()

    @Serializable @SerialName("exclusion_submitted")
    data class ExclusionSubmitted(val exclusion: ExclusionSpec) : EventPayload()

    @Serializable @SerialName("computation_produced")
    data class ComputationProduced(val result: CalibrationResult) : EventPayload()

    @Serializable @SerialName("sequence_check_produced")
    data class SequenceCheckProduced(val verdicts: List<SequenceVerdict>) : EventPayload()

    @Serializable @SerialName("decision_made")
    data class DecisionMade(val decision: DecisionRecord) : EventPayload()

    @Serializable @SerialName("decision_revoked")
    data class DecisionRevoked(val decisionId: String, val reason: String) : EventPayload()

    @Serializable @SerialName("mark_stale")
    data class MarkStale(val resultIds: List<String>, val becauseEventId: String) : EventPayload()

    @Serializable @SerialName("annotation")
    data class Annotation(val text: String, val targetIds: List<String> = emptyList()) : EventPayload()
}

@Serializable
data class EventEnvelope(
    val eventId: String,
    val seq: Long,
    val branch: String,
    val parentEventIds: List<String>,
    val virtualTime: String,
    val actor: String,
    val payload: EventPayload,
    val payloadHash: String,
)

/** Full export bundle: event log plus local sequence bookkeeping. */
@Serializable
data class ProjectBundle(
    val bundleFormat: String,
    val projectId: String,
    val name: String,
    val seed: Long,
    val events: List<EventEnvelope>,
    val branches: List<BranchRow>,
)

@Serializable
data class BranchRow(
    val branch: String,
    val tipEventId: String,
    val forkEventId: String?,
)
