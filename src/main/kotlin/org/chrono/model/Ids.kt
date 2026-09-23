package org.chrono.model

import java.security.MessageDigest

object Hashing {
    private val HEX = "0123456789abcdef".toCharArray()
    fun sha256Hex(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        val out = CharArray(digest.size * 2)
        digest.forEachIndexed { index, byte ->
            out[index * 2] = HEX[byte.toInt() ushr 4 and 0x0F]
            out[index * 2 + 1] = HEX[byte.toInt() and 0x0F]
        }
        return String(out)
    }
    fun short(text: String): String = sha256Hex(text).take(8)
}

object IdKinds {
    const val EVENT = "evt"
    const val PROJECT = "prj"
    const val CURVE = "crv"
    const val MEASUREMENT = "msr"
    const val RESERVOIR = "rsv"
    const val OFFSET_BINDING = "obd"
    const val EPOCH = "epd"
    const val SEQUENCE = "seq"
    const val EXCLUSION = "exc"
    const val CALIBRATION = "cal"
    const val ORDERING = "ord"
    const val DECISION = "dec"
    const val INTERVAL = "hpd"
    const val EVIDENCE = "evd"
    const val CANDIDATE = "cnd"
    const val DIAGNOSTIC = "diag"
}

fun stableId(kind: String, material: String): String = "${kind}_${Hashing.short(material)}"
