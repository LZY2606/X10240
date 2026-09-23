package org.chronobench.events

import kotlinx.serialization.json.Json
import java.security.MessageDigest

object Identity {
    fun sha256Short(input: String, length: Int = 12): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(length)
    }

    /**
     * Content-addressed event identity. The identity depends on the event payload and its
     * position (project + seq + virtual time + causation), so replaying the same case pack
     * on a fresh database yields byte-identical ids and ordering.
     */
    fun eventId(projectId: String, seq: Int, atEpochSecond: Long, type: String, canonicalPayload: String): String =
        "ev-" + sha256Short("$EVENT_SCHEMA_VERSION|$projectId|$seq|$atEpochSecond|$type|$canonicalPayload", 16)
}

/** Deterministic JSON: sorted keys where possible, no dynamic maps reordering, stable across machines. */
val EventJson: Json = Json {
    classDiscriminator = "eventType"
    ignoreUnknownKeys = true
    encodeDefaults = true
}
