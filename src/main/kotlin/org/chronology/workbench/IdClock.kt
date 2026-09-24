package org.chronology.workbench

import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

interface WorkClock {
    fun nowIso(): String
    fun epochSeconds(): Long
}

class FixedClock(private val instant: Instant = Instant.parse("2026-09-24T00:00:00Z")) : WorkClock {
    override fun nowIso(): String = DateTimeFormatter.ISO_INSTANT.format(instant)
    override fun epochSeconds(): Long = instant.epochSecond
}

class SystemClock : WorkClock {
    private val instant get() = Instant.now()
    override fun nowIso(): String = DateTimeFormatter.ISO_INSTANT.format(instant)
    override fun epochSeconds(): Long = instant.epochSecond
}

object StableIds {
    private const val FIXED_NAMESPACE_SEED = 0x5f424331L
    fun sha256(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    fun stable(prefix: String, components: List<String>): String {
        val canonical = components.joinToString("\u001f")
        return "$prefix-${sha256("v1:$FIXED_NAMESPACE_SEED:$canonical").take(20)}"
    }

    fun fingerprint(algorithmVersion: String, parameters: Map<String, String>): String {
        val canonical = parameters.toSortedMap().entries.joinToString("\u001f") { "${it.key}=${it.value}" }
        return sha256("$algorithmVersion\n$canonical").take(24)
    }
}

fun isoDate(epochSeconds: Long): String = DateTimeFormatter.ISO_INSTANT
    .format(java.time.Instant.ofEpochSecond(epochSeconds).atOffset(ZoneOffset.UTC))
