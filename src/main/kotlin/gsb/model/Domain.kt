package gsb.model

import kotlinx.serialization.Serializable

/** 语义类型标签。 */
enum class NodeKind { MEASUREMENT, CURVE, OFFSET, SEQUENCE, EXCLUSION, CHRONOLOGY }

/** 输入节点。一经提交不可覆盖；修订以新版本 + parentVersionId 表达。 */
@Serializable
data class InputNode(
    val id: String,
    val kind: NodeKind,
    val name: String,
    /** 来源出处：文件、行号、导入批次、人工决定 id 等。 */
    val origin: String,
    val createdAt: String,
    val currentVersionId: String,
    /** 历史版本 id，按时间从旧到新。 */
    val versionIds: List<String>
)

@Serializable
data class InputVersion(
    val id: String,
    val nodeId: String,
    val parentVersionId: String? = null,
    val revision: Int,
    val createdAt: String,
    val author: String,
    val note: String,
    /** 该版本的载荷（领域特定 JSON 在各 kind 的表中）。 */
    val payloadRef: String
)

/** 放射性碳测量：原测量值与标准差（14C BP, BP1950）。 */
@Serializable
data class MeasurementPayload(
    val labCode: String,
    val age: Quantity,       // 14C age, unit "14C yr BP", basis "BP1950"
    val sigma: Quantity,     // 1 sigma, unit "14C yr"
    val material: String,
    val sampleOrigin: String
)

/** 校准曲线上的一点。 */
@Serializable
data class CurvePoint(
    val calBp: Double,   // 坐标基准 cal BP (BP1950)
    val c14Bp: Double,   // 14C BP
    val sigma: Double
)

/** 校准曲线版本。 */
@Serializable
data class CurvePayload(
    val curveId: String,       // 例如 "GSB-FAKE"
    val version: String,       // 例如 "v1" / "v2"
    val basis: String,         // "cal BP / BP1950"
    val points: List<CurvePoint>,
    val supersedes: String? = null
) {
    fun ageRange(): ClosedFloatingPointRange<Double> =
        points.minOf { it.calBp }..points.maxOf { it.calBp }
}

/** 储库偏移：可给标量或离散混合分布。单位 14C yr。 */
@Serializable
data class OffsetComponent(val deltaR: Double, val sigma: Double, val weight: Double)

@Serializable
data class OffsetPayload(
    val name: String,
    val marine: Boolean,
    val components: List<OffsetComponent>, // 权重和=1；单峰即一个分量
    val basis: String = "14C yr reservoir correction"
)

/** 层序关系：before 在层序上早于 after。 */
@Serializable
data class SequenceEdge(val before: String, val after: String, val note: String = "")

@Serializable
data class SequencePayload(
    val name: String,
    val edges: List<SequenceEdge>
)

/** 排除决定：人工把某日历区间从候选中排除。 */
@Serializable
data class ExclusionRule(
    val id: String,
    val targetMeasurementId: String,
    /** 排除区间，天文纪年 [lo, hi]。 */
    val loYear: Int,
    val hiYear: Int,
    val reason: String
)

@Serializable
data class ExclusionPayload(val rules: List<ExclusionRule>)

/** 年代系统声明（BCE/CE 跨界等）。 */
@Serializable
data class ChronologyPayload(
    val system: String,        // "BCE/CE astronomical"
    val presentYear: Int,      // BP 基准年，1950
    val notes: String
)

/** 事件类型——机器可读、只追加（撤销是补偿事件，不改写旧事件）。 */
enum class EventType {
    PROJECT_CREATED,
    INPUT_COMMITTED,        // 提交一个新版本（含初始）
    INPUT_SUPERSEDED,       // 新版本使某节点旧版本失效
    DERIVED_COMPUTED,       // 计算出派生结果
    DERIVED_STALE,          // 依赖变化，派生结果过期
    CANDIDATE_PROPOSED,     // 层序冲突产生候选调整
    DECISION_ACCEPT,        // 人工接受
    DECISION_REJECT,        // 人工否定（含排除原因）
    DECISION_WITHDRAWN,     // 撤销上一条人工决定
    PARAM_ADJUST,           // 参数调整（例如更换偏移/曲线）
    REPLAY_NOTE,            // 反例档案的判定记录
    PROJECT_EXPORTED,
    PROJECT_IMPORTED
}

@Serializable
data class Event(
    val id: String,
    val seq: Long,
    val at: String,
    val type: EventType,
    val projectId: String,
    /** 受影响对象（节点/派生结果/候选 id）。 */
    val subjectId: String? = null,
    val detail: String = "",
    /** 直接来源 id 列表（用于血缘追溯）。 */
    val sources: List<String> = emptyList(),
    /** 结构化机器可读判定依据。 */
    val evidence: String? = null
)
