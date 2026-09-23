package org.chronocarbon

import java.security.MessageDigest

object Hashing {
    fun sha256Hex(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}

/** Short stable identifier derived from a textual basis (deterministic, no randomness). */
fun stableId(prefix: String, basis: String): String =
    prefix + "-" + Hashing.sha256Hex(basis).take(12)
