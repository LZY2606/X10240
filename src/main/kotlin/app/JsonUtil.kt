package app

import kotlinx.serialization.json.*
import java.security.MessageDigest

object JsonUtil {
    val json = Json { prettyPrint = false; ignoreUnknownKeys = true }

    /** 规范化序列化：键排序、无空白，用于参数指纹。 */
    fun canonical(e: JsonElement): String = when (e) {
        is JsonObject -> e.entries.sortedBy { it.key }
            .joinToString(",", "{", "}") { "\"${it.key}\":" + canonical(it.value) }
        is JsonArray -> e.joinToString(",", "[", "]") { canonical(it) }
        else -> e.toString()
    }

    fun sha256(s: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    fun obj(vararg pairs: Pair<String, JsonElement>): JsonObject = JsonObject(mapOf(*pairs))

    fun str(s: String) = JsonPrimitive(s)
    fun num(d: Double) = JsonPrimitive(d)
    fun num(i: Int) = JsonPrimitive(i)
    fun num(l: Long) = JsonPrimitive(l)
    fun bool(b: Boolean) = JsonPrimitive(b)

    fun JsonObject.req(key: String): JsonElement =
        this[key] ?: throw IllegalArgumentException("缺少字段: $key")

    fun JsonObject.reqString(key: String): String =
        (this[key] as? JsonPrimitive)?.content ?: throw IllegalArgumentException("缺少字符串字段: $key")

    fun JsonObject.optString(key: String): String? = (this[key] as? JsonPrimitive)?.content

    fun JsonObject.reqDouble(key: String): Double =
        (this[key] as? JsonPrimitive)?.doubleOrNull ?: throw IllegalArgumentException("字段 $key 不是数值")

    fun JsonObject.reqInt(key: String): Int =
        (this[key] as? JsonPrimitive)?.intOrNull ?: throw IllegalArgumentException("字段 $key 不是整数")

    fun JsonObject.reqArray(key: String): JsonArray =
        this[key] as? JsonArray ?: throw IllegalArgumentException("字段 $key 不是数组")

    fun JsonObject.reqObject(key: String): JsonObject =
        this[key] as? JsonObject ?: throw IllegalArgumentException("字段 $key 不是对象")
}
