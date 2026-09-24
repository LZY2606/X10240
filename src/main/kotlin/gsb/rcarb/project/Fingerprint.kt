package gsb.rcarb.project

import java.security.MessageDigest

/** 稳定指纹：对规范化后的依赖串取 SHA-256。相同固定版本输入 => 相同指纹。 */
object Fingerprint {
    fun sha256(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun of(vararg parts: String): String =
        sha256(parts.joinToString("|") { it.replace("\\", "\\\\").replace("|", "\\p") })
}
