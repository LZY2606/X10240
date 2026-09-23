package workbench

import java.security.MessageDigest

/**
 * Stable, content-addressed identity.
 *
 * Everything the workbench persists - raw inputs, derived calibration results,
 * human decisions, integration evidence - gets an identifier that is a digest of
 * its canonical content and direct provenance. Recomputing the same thing yields
 * the same id, which is what keeps identity stable across replay and across
 * export -> delete database -> import.
 *
 * Event rows are the exception: their id also embeds the append position, so the
 * order of history is part of the identity and cannot be silently reshuffled.
 */
object StableIds {
    private const val HASH_LEN = 16

    fun sha256Hex(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** Deterministic canonical rendering for fingerprinting. */
    fun canonical(parts: List<Pair<String, Any?>>): String {
        val sb = StringBuilder("{")
        parts.forEachIndexed { index, (key, value) ->
            if (index > 0) sb.append(',')
            sb.append(escape(key)).append(':').append(renderValue(value))
        }
        sb.append('}')
        return sb.toString()
    }

    private fun renderValue(value: Any?): String = when (value) {
        null -> "null"
        is Boolean, is Number -> value.toString()
        is String -> escape(value)
        is Map<*, *> -> {
            value.entries.joinToString(prefix = "{", postfix = "}") { (key, nested) ->
                escape(key.toString()) + ":" + renderValue(nested)
            }
        }
        is Iterable<*> -> value.joinToString(prefix = "[", postfix = "]") { renderValue(it) }
        is DoubleArray -> value.joinToString(prefix = "[", postfix = "]", separator = ",") {
            // fixed rendering so fingerprints do not depend on platform Double formatting
            formatProbability(it)
        }
        else -> escape(value.toString())
    }

    private fun escape(text: String): String {
        val sb = StringBuilder("\"")
        text.forEach { char ->
            when (char) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> sb.append(char)
            }
        }
        return sb.append('"').toString()
    }

    /** Stable rendering of a probability / likelihood value at a fixed precision. */
    fun formatProbability(value: Double): String {
        if (value == 0.0) return "0"
        if (value.isNaN()) return "NaN"
        // 12 significant decimal digits is far beyond what is displayed but
        // keeps replay bit-comparable without embedding JVM float formatting.
        return "%.12g".format(value)
    }

    fun contentId(prefix: String, parts: List<Pair<String, Any?>>): String =
        prefix + "_" + sha256Hex(canonical(parts)).take(HASH_LEN)

    /** Ordered event identity: position is part of the hash so order survives export/import. */
    fun eventId(seq: Long, eventType: String, payloadFingerprint: String, prevEventId: String?): String {
        val material = canonical(
            listOf(
                "seq" to seq,
                "type" to eventType,
                "payload" to payloadFingerprint,
                "prev" to (prevEventId ?: "GENESIS")
            )
        )
        return "evt_" + seq.toString().padStart(6, '0') + "_" + sha256Hex(material).take(10)
    }

    fun fingerprint(parts: List<Pair<String, Any?>>): String = "fp_" + sha256Hex(canonical(parts)).take(24)
}
