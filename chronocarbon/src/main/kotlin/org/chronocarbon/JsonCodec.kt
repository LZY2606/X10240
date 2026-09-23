package org.chronocarbon

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.encodeToString
import kotlinx.serialization.serializer

object JsonCodec {
    val json = Json {
        prettyPrint = false
        encodeDefaults = true
        ignoreUnknownKeys = false
        classDiscriminator = "kind"
    }

    val pretty = Json(json) { prettyPrint = true }

    inline fun <reified T> encode(value: T): String = json.encodeToString(value)

    inline fun <reified T> decode(text: String): T = json.decodeFromString(text)

    fun canonicalJson(element: JsonElement): String =
        json.encodeToString(JsonElement.serializer(), sort(element))

    internal fun sort(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(element.entries.sortedBy { it.key }.associate { it.key to sort(it.value) })
        else -> element
    }
}
