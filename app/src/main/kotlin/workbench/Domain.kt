package workbench

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** 虚拟时钟：测试注入固定时间，生产用系统时间。 */
interface Clock { fun now(): String }
class FixedClock(private val t: String) : Clock { override fun now() = t }
class SystemClock : Clock {
    override fun now(): String =
        java.time.Instant.now().toString()
}

/** 领域量：数值 + 单位/坐标基准 + 原始表示（导入层保留原文，派生层才规范化）。 */
@Serializable
data class Quantity(
    val value: Double,
    val unit: String,          // "BP" | "yr" | "calBP" | "BCE" | "CE" | "1"
    val datum: String,         // 坐标基准，如 "calBP(1950)" / "historical-era(no-year-zero)"
    val raw: String            // 原始表示，例如 "2450 ± 30 BP"
)

@Serializable
data class SourceRecord(
    val id: String,            // src-0001 …
    val kind: String,          // measurement | curve | offset-dist | era-system | sequence | exclusion
    val version: Int,
    val parentId: String? = null,
    val origin: String,        // 来源说明（实验室编号 / 曲线发布方 / 人工录入…）
    val raw: String,           // 原始提交文本，不可覆盖
    val data: JsonObject,      // 解析后的结构化内容
    val diagnostics: List<String> = emptyList() // 无法转换的值留在原位并附诊断
)

@Serializable
data class DerivedRecord(
    val id: String,            // drv-0001 …
    val kind: String,          // calibration | interval-set | sequence-check | adjustment-candidates
    val algoVersion: String,   // 算法版本，如 "calib-1.2.0"
    val fingerprint: String,   // 参数指纹 = hash(algoVersion + 直接来源 id@version)
    val sources: List<String>, // 直接来源 "src-0003@2"
    val payload: JsonObject,
    val status: String = "current" // current | stale | rejected | undecidable
)

@Serializable
data class DecisionRecord(
    val id: String,            // dec-0001 …
    val action: String,        // accept | reject | adjust | undo
    val targetId: String,      // 被裁定的派生结果或候选
    val rationale: String,
    val undoneBy: String? = null
)

@Serializable
data class Event(
    val seq: Long,
    val id: String,            // evt-0001 …
    val ts: String,
    val kind: String,          // source.registered / source.revised / derived.computed /
                             // decision.recorded / conflict.raised / conflict.resolved /
                             // undo.recorded / project.imported / project.exported
    val refId: String,         // 事件作用对象（src-/drv-/dec- 等稳定身份）
    val summary: String,
    val payload: JsonObject
)

@Serializable
data class ConflictRecord(
    val id: String,            // cfl-0001 …
    val kind: String,          // sequence-order | decision-disagreement
    val involved: List<String>,
    val candidates: List<JsonObject>, // 无法唯一决定时保留全部合法候选
    val status: String = "open",      // open | resolved
    val resolvedBy: String? = null
)

val JsonFmt = Json { prettyPrint = false; ignoreUnknownKeys = true; encodeDefaults = true }

fun sha256(s: String): String {
    val md = java.security.MessageDigest.getInstance("SHA-256")
    return md.digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
