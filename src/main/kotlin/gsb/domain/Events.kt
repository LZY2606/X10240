package gsb.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** 虚拟时钟：测试注入固定时间，生产环境使用系统时钟。 */
fun interface Clock { fun now(): String }

val SYSTEM_CLOCK = Clock { java.time.Instant.now().toString() }

/**
 * 追加式事件日志。原始输入一经提交不可覆盖；任何修改都是“新版本 + 父版本”或人工决定。
 * payload 保存足够信息，使重放到任一事件即可恢复当时的依赖版本与可见结论。
 */
@Serializable
data class Event(
    val seq: Long,
    val id: String,
    val type: EventType,
    val occurredAt: String,
    val actor: String,
    val payload: JsonElement,
    val derivedIds: List<String> = emptyList(),
    val note: String = ""
)

@Serializable
enum class EventType {
    PROJECT_CREATED,
    CURVE_REGISTERED,
    CURVE_SUPERSEDED,
    MEASUREMENT_SUBMITTED,
    MEASUREMENT_REVISED,
    RESERVOIR_SUBMITTED,
    RESERVOIR_REVISED,
    SEQUENCE_SUBMITTED,
    SEQUENCE_REVISED,
    EXCLUSION_SUBMITTED,
    EXCLUSION_REVISED,
    CURVE_ASSIGNED,
    RUN_CALIBRATION,
    RUN_SEQUENCE_EVAL,
    DECISION_ACCEPT,
    DECISION_REJECT,
    DECISION_PARAM_ADJUST,
    UNDO
}

@Serializable
data class Project(
    val id: String,
    val name: String,
    val eraSystem: String,
    val source: SourceRef,
    val activeCurveId: String? = null,
    val createdAt: String
)

/** 时间点快照：reducer 从事件序列逐事件重放得到。 */
data class ProjectState(
    val project: Project? = null,
    val curves: LinkedHashMap<String, CalibrationCurve> = LinkedHashMap(),
    val measurements: LinkedHashMap<String, Measurement> = LinkedHashMap(),
    val reservoirs: LinkedHashMap<String, ReservoirAssumption> = LinkedHashMap(),
    val sequences: LinkedHashMap<String, SequenceConstraint> = LinkedHashMap(),
    val exclusions: LinkedHashMap<String, ExclusionDecision> = LinkedHashMap(),
    val runs: LinkedHashMap<String, CalibrationRunResult> = LinkedHashMap(),
    val sequenceEvals: LinkedHashMap<String, SequenceEvalResult> = LinkedHashMap(),
    val decisions: LinkedHashMap<String, DecisionRecord> = LinkedHashMap(),
    val lastSeq: Long = 0L,
    /** 派生记录指纹：局部输入变化时仅使依赖项过期。 */
    val runInputVersions: LinkedHashMap<String, Set<String>> = LinkedHashMap()
)

@Serializable
data class DecisionRecord(
    val id: String,
    val targetKind: String,        // INTERVAL | SAMPLE | SEQUENCE
    val targetId: String,
    val decision: String,          // ACCEPTED | REJECTED | ADJUSTED
    val reason: String,
    val actor: String,
    val at: String,
    val parentEventId: String?,
    val supersededBy: String? = null,
    val paramPatch: JsonElement? = null
)

@Serializable
data class StaleReport(
    val items: List<DerivedStaleness>
)
