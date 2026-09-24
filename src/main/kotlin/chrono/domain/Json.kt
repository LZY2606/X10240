package chrono.domain

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encodeToString
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import java.time.Instant

object InstantSerializer : KSerializer<Instant> {
    override val descriptor = PrimitiveSerialDescriptor("java.time.Instant", PrimitiveKind.STRING)
    override fun deserialize(decoder: Decoder): Instant = Instant.parse(decoder.decodeString())
    override fun serialize(encoder: Encoder, value: Instant) = encoder.encodeString(value.toString())
}

/** Canonical JSON: stable key order, no pretty tricks — used for fingerprints and archives. */
val canonicalJson = Json {
    classDiscriminator = "kind"
    ignoreUnknownKeys = false
    encodeDefaults = true
}

val prettyJson = Json(from = canonicalJson) {
    prettyPrint = true
}

inline fun <reified T> T.toJson(pretty: Boolean = false): String =
    if (pretty) prettyJson.encodeToString(this) else canonicalJson.encodeToString(this)

inline fun <reified T> String.decodeJson(): T = canonicalJson.decodeFromString(this)
