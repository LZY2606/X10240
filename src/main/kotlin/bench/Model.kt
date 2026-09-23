package bench

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.security.MessageDigest

const val ALGORITHM_VERSION = "calib-1.0.0"
const val SEQUENCE_ALGORITHM_VERSION = "seq-1.0.0"

val JsonFmt = Json {
    prettyPrint = false
    encodeDefaults = true
    ignoreUnknownKeys = true
    explicitNulls = false
}

fun sha256(text: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    return digest.digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}

fun stableId(prefix: String, vararg parts: String): String =
    "$prefix-" + sha256(parts.joinToString("|")).substring(0, 12)

enum class InputKind(val wire: String) {
    MEASUREMENT("measurement"),
    CURVE("curve"),
    OFFSET("offset"),
    ERA_SYSTEM("era_system"),
    SEQUENCE_CONSTRAINT("sequence_constraint"),
    EXCLUSION_DECISION("exclusion_decision");

    companion object {
        fun of(wire: String): InputKind = entries.first { it.wire == wire }
    }
}

/** An immutable submitted input version. Raw representation is always preserved. */
data class InputRecord(
    val id: String,
    val version: Int,
    val parent: String?,          // "id@version" of the superseded version, null for v1
    val kind: InputKind,
    val provenance: String,       // where this direct source comes from
    val raw: String,              // original representation exactly as submitted
    val content: JsonObject,      // normalized view derived at import time
    val hash: String,
    val submittedAt: String
) {
    val key: String get() = "$id@$version"
}

enum class ResultStatus { CURRENT, STALE, SUPERSEDED }

/** A derived value: carries algorithm version, parameter fingerprint and direct sources. */
data class DerivedResult(
    val id: String,
    val kind: String,             // "calibration" | "sequence_evaluation"
    val algorithm: String,
    val fingerprint: String,
    val sources: List<String>,    // input keys this result directly depends on
    val content: JsonObject,
    var status: ResultStatus,
    val createdAt: String
)

data class Event(
    val seq: Long,
    val id: String,
    val type: String,
    val at: String,
    val payload: JsonObject
)

fun parseJsonObject(text: String): JsonObject = JsonFmt.parseToJsonElement(text).jsonObject
