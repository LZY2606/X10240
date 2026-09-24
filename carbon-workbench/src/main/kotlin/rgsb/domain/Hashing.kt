package rgsb.domain

import kotlinx.serialization.json.Json
import java.security.MessageDigest

/**
 * Canonical JSON hashing: stable across JVM runs, locale independent and key-order
 * deterministic (kotlinx.serialization encodes declared property order, and we sort
 * maps before hashing where relevant).
 */
object Hashing {
    val canonicalJson: Json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    fun sha256Hex(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    inline fun <reified T> fingerprint(value: T): String =
        sha256Hex(canonicalJson.encodeToString(serializer<T>(), value))
}
