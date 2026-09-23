package c14.domain

import java.security.MessageDigest

/**
 * Stable canonical fingerprinting.
 *
 * Everything is rendered through [canonical] so a fingerprint depends only on
 * logical content — never on map ordering, whitespace or the JVM version.
 */
object Fingerprint {
    fun canonical(value: Any?): String {
        val sb = StringBuilder()
        render(sb, value)
        return sb.toString()
    }

    fun sha256(canonical: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun of(value: Any?): String = sha256(canonical(value))

    @Suppress("UNCHECKED_CAST")
    private fun render(sb: StringBuilder, value: Any?) {
        when (value) {
            null -> sb.append("null")
            is Map<*, *> -> {
                sb.append('{')
                value.entries.sortedBy { it.key.toString() }.forEachIndexed { index, entry ->
                    if (index > 0) sb.append(',')
                    render(sb, entry.key.toString())
                    sb.append(':')
                    render(sb, entry.value)
                }
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                value.forEachIndexed { index, item ->
                    if (index > 0) sb.append(',')
                    render(sb, item)
                }
                sb.append(']')
            }
            is Boolean, is Number -> sb.append(value.toString())
            else -> {
                sb.append('"')
                sb.append(escape(value.toString()))
                sb.append('"')
            }
        }
    }

    private fun escape(raw: String): String = raw
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")
}
