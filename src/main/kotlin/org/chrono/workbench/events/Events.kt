package org.chrono.workbench.events

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class EventRef(val eventId: String, val seq: Int, val atInstant: String)

@Serializable
data class StoredEvent(
    val seq: Int,
    val eventId: String,
    val parentEventId: String?,
    val type: String,
    val atInstant: String,
    val actor: String,
    val summary: String,
    val payload: JsonElement,
    val sources: List<String> = emptyList(),
    val affectedObjectIds: List<String> = emptyList(),
    val reversedEventId: String? = null,
    val replayTag: String? = null
)

object EventTypes {
    const val PROJECT_OPENED = "PROJECT_OPENED"
    const val CURVE_REGISTERED = "CURVE_REGISTERED"
    const val CURVE_SUPERSEDED = "CURVE_SUPERSEDED"
    const val MEASUREMENT_IMPORTED = "MEASUREMENT_IMPORTED"
    const val MEASUREMENT_REVISED = "MEASUREMENT_REVISED"
    const val RESERVOIR_SET = "RESERVOIR_SET"
    const val ERA_SET = "ERA_SET"
    const val SEQUENCE_SET = "SEQUENCE_SET"
    const val EXCLUSION_DECIDED = "EXCLUSION_DECIDED"
    const val REVIEW_CANDIDATE = "REVIEW_CANDIDATE"
    const val DERIVED_REQUESTED = "DERIVED_REQUESTED"
    const val JUDGMENT_RECORDED = "JUDGMENT_RECORDED"
    const val EVENT_REVERSED = "EVENT_REVERSED"
    const val BUNDLE_IMPORTED = "BUNDLE_IMPORTED"
}

object JudgmentCodes {
    const val MULTIMODAL_KEPT = "MULTIMODAL_KEPT"
    const val BOUNDARY_TRUNCATED = "BOUNDARY_TRUNCATED"
    const val OUTSIDE_CURVE_UNKNOWN = "OUTSIDE_CURVE_UNKNOWN"
    const val BCECE_CROSSING = "BCECE_CROSSING"
    const val REPRODUCIBLE_FIXED_VERSION = "REPRODUCIBLE_FIXED_VERSION"
    const val SEQUENCE_SATISFIED = "SEQUENCE_SATISFIED"
    const val SEQUENCE_CONFLICT_ADJUSTMENT = "SEQUENCE_CONFLICT_ADJUSTMENT"
    const val TIED_CALENDAR_INTERVALS = "TIED_CALENDAR_INTERVALS"
    const val RESERVOIR_DISTRIBUTION_APPLIED = "RESERVOIR_DISTRIBUTION_APPLIED"
}
