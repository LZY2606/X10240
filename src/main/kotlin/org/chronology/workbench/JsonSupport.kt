package org.chronology.workbench

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

val workbenchJson = Json {
    prettyPrint = true
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

fun JsonObject.obj(name: String): JsonObject? = this[name]?.jsonObject
fun JsonObject.arr(name: String): JsonArray? = this[name]?.jsonArray
fun JsonObject.str(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull
fun JsonObject.num(name: String): Double? = this[name]?.jsonPrimitive?.doubleOrNull
fun JsonObject.bool(name: String): Boolean? = (this[name] as? JsonPrimitive)?.let { if (it.isString) null else it.content.toBooleanStrictOrNull() }

fun jsonObjectOf(vararg pairs: Pair<String, Any?>): JsonObject {
    val map = mutableMapOf<String, JsonElement2>()
    pairs.forEach { (key, value) ->
        if (value != null) map[key] = value.toJsonElement()
    }
    return JsonObject(map)
}

typealias JsonElement2 = kotlinx.serialization.json.JsonElement

private fun Any?.toJsonElement(): JsonElement2 = when (this) {
    null -> JsonNull2
    is JsonElement2 -> this
    is String -> JsonPrimitive(this)
    is Number -> JsonPrimitive(this)
    is Boolean -> JsonPrimitive(this)
    is Enum<*> -> JsonPrimitive(this.name)
    is Map<*, *> -> JsonObject(this.entries.associate { it.key.toString() to it.value.toJsonElement() })
    is Iterable<*> -> JsonArray(this.map { it.toJsonElement() })
    is Array<*> -> JsonArray(this.map { it.toJsonElement() })
    else -> JsonPrimitive(toString())
}

private val JsonNull2 = kotlinx.serialization.json.JsonNull
