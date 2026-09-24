package org.chrono.workbench.util

import java.security.MessageDigest

object Hashing {
    private val B32 = charArrayOf(
        '0','1','2','3','4','5','6','7','8','9','a','b','c','d','e','f','g','h','j','k','m','n','p','q','r','s','t','v','w','x','y','z'
    )

    fun sha256Hex(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun shortHash(prefix: String, canonical: String): String {
        val full = sha256Hex(canonical)
        val take = full.substring(0, 13)
        var acc = 0L
        for (ch in take) acc = acc * 31 + ch.code
        val sb = StringBuilder()
        var v = acc
        repeat(7) {
            sb.append(B32[(v and 31L).toInt()])
            v = v ushr 5
        }
        return "$prefix_${sb}"
    }
}
