package workbench

import java.security.MessageDigest

/** Deterministic, content-derived stable identities. No randomness: fixed-seed friendly. */
object Ids {
    fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /** Stable id like "src_9f2c..." derived from kind + parts. */
    fun of(kind: String, vararg parts: String): String =
        kind + "_" + sha256(parts.joinToString("|")).substring(0, 16)

    /** Fingerprint for a derived value: algorithm version + params + direct source identities. */
    fun fingerprint(algoVersion: String, paramsCanonicalJson: String, sourceIds: List<String>): String =
        sha256(algoVersion + "|" + paramsCanonicalJson + "|" + sourceIds.sorted().joinToString("|"))
}
