package carbonbench.util

import java.security.MessageDigest

object Hashing {
    fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun shortFingerprint(input: String): String = sha256Hex(input).take(16)
}
