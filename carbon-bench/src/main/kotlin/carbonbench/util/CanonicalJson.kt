package carbonbench.util

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.serializer

object CanonicalJson {
    val json: Json = Json {
        prettyPrint = false
        encodeDefaults = true
    }

    inline fun <reified T> toElement(value: T): JsonElement =
        json.encodeToJsonElement(serializer(), value)

    fun canonical(element: JsonElement): String = when (element) {
        is JsonObject -> element.entries
            .sortedBy { it.key }
            .joinToString(",", "{", "}") { "\"${escape(it.key)}\":${canonical(it.value)}" }
        is JsonArray -> element.joinToString(",", "[", "]") { canonical(it) }
        is JsonNull -> "null"
        is JsonPrimitive -> when {
            element.isString -> "\"${escape(element.content)}\""
            else -> element.content
        }
    }

    fun fingerprint(input: String): String = Hashing.shortFingerprint(input)

    private fun escape(s: String): String = buildString {
        for (c in s) when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (c.code < 0x20) append("\\u%04x".format(c.code)) else append(c)
        }
    }
}
