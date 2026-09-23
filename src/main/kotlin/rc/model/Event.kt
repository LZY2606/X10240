package rc.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class EventRecord(
    val seq: Long,
    val eventId: String,
    val kind: String,
    val occurredAt: Long,
    val parentEventHash: String?,
    val hash: String,
    val payload: JsonObject
)

object EventKinds {
    const val PROJECT_CREATED = "project.created"
    const val CURVE_REGISTERED = "curve.registered"
    const val CURVE_ACTIVATED = "curve.activated"
    const val ERA_REGISTERED = "era.registered"
    const val OFFSET_REGISTERED = "offset.registered"
    const val OFFSET_SUPERSEDED = "offset.superseded"
    const val MEASUREMENT_SUBMITTED = "measurement.submitted"
    const val MEASUREMENT_AMENDED = "measurement.amended"
    const val SEQUENCE_SUBMITTED = "sequence.submitted"
    const val EXCLUSION_DECIDED = "exclusion.decided"
    const val CANDIDATE_ADJUDICATED = "candidate.adjudicated"
    const val ADJUDICATION_REVOKED = "adjudication.revoked"
    const val REVIEW_JUDGMENT = "review.judgment"
}

/** Direct dependency keys used for stale detection, e.g. measurement:OAK-7, curve:IntCalOffline. */
object DepKeys {
    fun measurement(id: String) = "measurement:$id"
    fun curve(id: String) = "curve:$id"
    fun offset(id: String) = "offset:$id"
    fun sequence(id: String) = "sequence:$id"
    fun exclusion(id: String) = "exclusion:$id"
    fun adjudication(id: String) = "adjudication:$id"
}
