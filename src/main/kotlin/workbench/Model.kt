package workbench

import java.security.MessageDigest
import java.time.Instant

/** 时钟：生产用系统时钟，测试用虚拟时钟。 */
interface Clock { fun now(): Instant }
object SystemClock : Clock { override fun now(): Instant = Instant.now() }
class VirtualClock(private var t: Instant = Instant.parse("2026-01-01T00:00:00Z")) : Clock {
    override fun now(): Instant = t
    fun tickSeconds(s: Long) { t = t.plusSeconds(s) }
}

fun sha256(s: String): String {
    val d = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
    return d.joinToString("") { "%02x".format(it) }
}

/** 来源类别：每类输入各自携带来源。 */
enum class SourceKind(val label: String) {
    MEASUREMENT("原测量值和标准差"),
    CURVE("校准曲线版本"),
    OFFSET("储库偏移分布"),
    ERA_SYSTEM("年代系统"),
    SEQUENCE("层序约束"),
    EXCLUSION("排除决定"),
}

/** 派生结果类别。 */
enum class ResultKind(val label: String) {
    CALIBRATION("校准多峰分布"),
    INTERVAL_CANDIDATE("并列日历区间候选"),
    SEQUENCE_CHECK("层序一致性检验"),
    ADJUSTMENT_CANDIDATE("层序候选调整"),
}

enum class DecisionAction(val label: String) {
    ACCEPT("接受"), REJECT("否定"), ADJUST_PARAM("参数调整"), RESOLVE("冲突解决"), UNDO("撤销"),
}

data class Source(
    val id: String, val kind: SourceKind, val versionNo: Int, val parentId: String?,
    val raw: String, val unit: String, val provenance: String, val diagnostics: String,
    val createdSeq: Long, val createdAt: String,
)

data class DerivedResult(
    val id: String, val kind: ResultKind, val algoVersion: String, val fingerprint: String,
    val sourceIds: List<String>, val payload: String, val createdSeq: Long, val createdAt: String,
)

data class Decision(
    val id: String, val targetId: String, val action: DecisionAction, val rationale: String,
    val payload: String, val createdSeq: Long, val createdAt: String, val undoneBySeq: Long?,
)

data class Event(val seq: Long, val id: String, val type: String, val payload: String, val at: String)

fun newId(prefix: String, vararg parts: Any?): String =
    prefix + "-" + sha256(parts.joinToString("|") { it.toString() }).substring(0, 12)
