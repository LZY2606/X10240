package org.chronolab.carbon.workbench

import java.security.MessageDigest

object Fingerprint {
    fun sha256(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
    fun short(text: String): String = sha256(text).take(12)
}
