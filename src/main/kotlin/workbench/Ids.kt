package workbench

import java.security.MessageDigest

object Ids {
    fun sha256(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    /** Stable, content-derived identity for derived results. */
    fun fingerprint(algorithm: String, params: String, sourceIds: List<String>): String =
        sha256(algorithm + "|" + params + "|" + sourceIds.sorted().joinToString(",")).take(16)

    fun sourceId(kind: String, slug: String, version: Int): String = "src:$kind:$slug@v$version"

    fun resultId(kind: String, fingerprint: String): String = "drv:$kind:$fingerprint"
}
