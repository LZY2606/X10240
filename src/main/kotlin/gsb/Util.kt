package gsb

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.security.MessageDigest
import java.time.Instant

/** Deterministic canonical form of a JSON value: sorted object keys, no whitespace. */
fun canonical(e: JsonElement): String = when (e) {
    is JsonObject -> e.entries.sortedBy { it.key }
        .joinToString(",", "{", "}") { JsonPrimitive(it.key).toString() + ":" + canonical(it.value) }
    is JsonArray -> e.joinToString(",", "[", "]") { canonical(it) }
    is JsonPrimitive -> e.toString()
    else -> e.toString()
}

fun sha256(s: String): String {
    val d = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
    return d.joinToString("") { "%02x".format(it) }
}

/** Stable, content-derived identity. Same inputs -> same id, across export/import. */
fun stableId(prefix: String, vararg parts: String): String =
    prefix + "-" + sha256(parts.joinToString("\u0001")).take(16)

interface Clock {
    fun now(): String
}

class SystemClock : Clock {
    override fun now(): String = Instant.now().toString()
}

/** Deterministic virtual clock for tests and replay. */
class VirtualClock(start: Instant = Instant.parse("2026-01-01T00:00:00Z")) : Clock {
    private var t = start
    override fun now(): String {
        val r = t.toString()
        t = t.plusSeconds(1)
        return r
    }
}
