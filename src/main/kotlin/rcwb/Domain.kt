package rcwb

import kotlinx.serialization.json.*

val JSON = Json { ignoreUnknownKeys = false; encodeDefaults = true }

fun interface Clock { fun now(): String }

object SystemClock : Clock {
    override fun now(): String = java.time.Instant.now().toString()
}

/** 虚拟时间：测试专用，每次调用前进固定秒数，保证可重现。 */
class FixedClock(start: String = "2026-01-01T00:00:00Z", private val stepSeconds: Long = 1) : Clock {
    private var t = java.time.Instant.parse(start)
    override fun now(): String { val v = t.toString(); t = t.plusSeconds(stepSeconds); return v }
}

data class Event(
    val id: String,
    val seq: Long,
    val type: String,
    val payload: JsonObject,
    val at: String,
    val actor: String,
)

fun toJson(v: Any?): JsonElement = when (v) {
    null -> JsonNull
    is String -> JsonPrimitive(v)
    is Number -> JsonPrimitive(v)
    is Boolean -> JsonPrimitive(v)
    is JsonElement -> v
    is List<*> -> JsonArray(v.map { toJson(it) })
    is Map<*, *> -> JsonObject(v.entries.associate { it.key.toString() to toJson(it.value) })
    else -> JsonPrimitive(v.toString())
}

fun obj(vararg pairs: Pair<String, Any?>): JsonObject =
    JsonObject(pairs.associate { it.first to toJson(it.second) })

fun sha256(s: String): String =
    java.security.MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

/** 事件类型 -> 稳定身份前缀。 */
fun prefixFor(type: String): String = when {
    type.startsWith("measurement.") -> "MEA"
    type.startsWith("curve.") -> "CRV"
    type.startsWith("offset.") -> "OFS"
    type.startsWith("sequence.") && type != "sequence.checked" -> "SEQ"
    type == "sequence.checked" -> "SCK"
    type.startsWith("exclusion.") -> "EXC"
    type.startsWith("calibration.") -> "RES"
    type.startsWith("decision.undone") -> "UND"
    type.startsWith("decision.") -> "DEC"
    type.startsWith("conflict.") -> "CNF"
    type.startsWith("params.") -> "PAR"
    type.startsWith("import.") -> "IMP"
    type.startsWith("casepack.") -> "PKG"
    type.startsWith("project.") -> "PRJ"
    else -> "EVT"
}
