package rc.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

val RC_JSON: Json = Json {
    prettyPrint = false
    encodeDefaults = true
    ignoreUnknownKeys = true
    classDiscriminator = "#kind"
}

/**
 * Recursively sorts object keys so JSON hashing is independent of insertion
 * order. Arrays keep their order (order is meaningful evidence).
 */
fun canonicalJson(element: JsonElement): String =
    RC_JSON.encodeToString(JsonElement.serializer(), canonicalize(element))

private fun canonicalize(element: JsonElement): JsonElement = when (element) {
    is JsonObject -> JsonObject(element.entries.sortedBy { it.key }.associate { it.key to canonicalize(it.value) })
    is JsonArray -> JsonArray(element.map { canonicalize(it) })
    else -> element
}

fun jsonObjectOf(vararg pairs: Pair<String, JsonElement?>): JsonObject =
    JsonObject(buildMap {
        for ((k, v) in pairs) if (v != null) put(k, v)
    })

fun String.j(): JsonPrimitive = JsonPrimitive(this)
fun Number.j(): JsonPrimitive = JsonPrimitive(this)
fun Boolean.j(): JsonPrimitive = JsonPrimitive(this)
fun JsonElement?.asObject(): JsonObject? = this as? JsonObject
