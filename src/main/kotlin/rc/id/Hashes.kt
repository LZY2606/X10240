package rc.id

import java.security.MessageDigest

object Hashes {
    private val HEX = "0123456789abcdef".toCharArray()
    private val B32 = "0123456789abcdefghijklmnopqrstuv".toCharArray()

    fun sha256Bytes(text: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))

    fun sha256(text: String): String {
        val bytes = sha256Bytes(text)
        val out = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            out[i * 2] = HEX[(bytes[i].toInt() ushr 4) and 0xF]
            out[i * 2 + 1] = HEX[bytes[i].toInt() and 0xF]
        }
        return String(out)
    }

    fun stableId(prefix: String, payload: String, length: Int = 10): String {
        val digest = sha256Bytes(payload)
        var bits = 0L
        var value = 0L
        val sb = StringBuilder()
        for (b in digest) {
            value = (value shl 8) or (b.toLong() and 0xFF)
            bits += 8
            while (bits >= 5) {
                bits -= 5
                sb.append(B32[((value ushr bits) and 0x1F).toInt()])
            }
        }
        if (bits > 0) sb.append(B32[((value shl (5 - bits)) and 0x1F).toInt()])
        return "$prefix-${sb.substring(0, minOf(length, sb.length))}"
    }
}
