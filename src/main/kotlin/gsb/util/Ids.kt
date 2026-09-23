package gsb.util

import java.security.MessageDigest

/** Stable identity helpers. Ids are content/lineage derived so an export ->
 * delete -> import round trip preserves identity, order and provenance. */
object Ids {
    private fun sha(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    fun sha256(text: String): String = sha(text.toByteArray(Charsets.UTF_8))

    /** Short, stable, human-recognisable id derived from a namespace + stable key. */
    fun sid(namespace: String, stableKey: String): String =
        namespace + "_" + sha256(namespace + "|" + stableKey).take(16)

    /** Deterministic PRNG (xorshift128+) seeded from a fingerprint, used so any
     * "random" choice is reproducible offline. */
    fun rng(seedText: String): LongArray {
        val d = sha(seedText.toByteArray(Charsets.UTF_8))
        var a = 0L; var b = 0L
        for (i in 0 until 8) a = (a shl 8) or (d[i].toLong() and 0xff)
        for (i in 8 until 16) b = (b shl 8) or (d[i].toLong() and 0xff)
        if (a == 0L && b == 0L) a = 0x9E3779B97F4A7C15UL.toLong()
        return longArrayOf(a, b)
    }

    fun nextLong(state: LongArray): Long {
        var x = state[0]; val y = state[1]
        state[0] = y
        x = x xor (x shl 23)
        state[1] = x xor y xor (x ushr 17) xor (y ushr 26)
        return state[1] + y
    }
}
