package org.gsb.carbon.model

import kotlinx.serialization.Serializable

/**
 * 追加型事件日志。原始输入提交后不可覆盖，修订以父版本关系表达。
 * 每个事件带稳定身份（项目内单调序号 + 全局稳定 id）、虚拟时间戳与操作者。
 */
@Serializable
data class Event(
    val eventId: String,
    val seq: Int,
    val type: EventType,
    val clock: Long,
    val actor: String,
    val summary: String,
    val payload: EventPayload,
    /** 该事件直接读取的来源（事件 id / 实体版本 id），支持反查证据链 */
    val sources: List<String> = emptyList(),
)

@Serializable
enum class EventType {
    PROJECT_CREATED,
    CURVE_RECORDED,
    CURVE_SUPERSEDED,
    MEASUREMENT_RECORDED,
    MEASUREMENT_REVISED,
    OFFSET_RECORDED,
    OFFSET_REVISED,
    ERA_SYSTEM_RECORDED,
    SEQUENCE_RECORDED,
    SEQUENCE_REVISED,
    EXCLUSION_RECORDED,
    CALIBRATION_COMPUTED,
    SEQUENCE_EVALUATED,
    DECISION_MADE,
    DECISION_REVOKED,
    RESULTS_INVALIDATED,
    CASE_IMPORT_REJECTED,
    CASE_STAGE_RECORDED,
}

@Serializable
sealed interface EventPayload

@Serializable
data class ProjectCreated(val projectId: String, val name: String, val casePackId: String?) : EventPayload

@Serializable
data class CurveRecorded(val curveVersionId: String) : EventPayload

@Serializable
data class CurveSuperseded(val oldVersionId: String, val newVersionId: String) : EventPayload

@Serializable
data class MeasurementRecorded(val measurementIdVersion: String) : EventPayload

@Serializable
data class MeasurementRevised(val oldIdVersion: String, val newIdVersion: String) : EventPayload

@Serializable
data class OffsetRecorded(val offsetId: String, val revised: Boolean = false) : EventPayload

@Serializable
data class EraSystemRecorded(val eraSystemId: String) : EventPayload

@Serializable
data class SequenceRecorded(val edgeId: String, val revised: Boolean = false) : EventPayload

@Serializable
data class ExclusionRecorded(val exclusionId: String) : EventPayload

@Serializable
data class CalibrationComputed(val runId: String) : EventPayload

@Serializable
data class SequenceEvaluatedPayload(val evaluationId: String) : EventPayload

@Serializable
data class DecisionMadePayload(val decisionId: String) : EventPayload

@Serializable
data class DecisionRevokedPayload(val decisionId: String, val revocationId: String) : EventPayload

/** 局部输入变化：只让依赖它的结果过期；过期结果仍可查看但不能冒充当前结果。 */
@Serializable
data class ResultsInvalidated(
    val reason: String,
    val staleRunIds: List<String> = emptyList(),
    val staleEvaluationIds: List<String> = emptyList(),
) : EventPayload

@Serializable
data class CaseImportRejected(
    val file: String,
    val diagnostics: List<String>,
    val rawSnippet: String,
) : EventPayload

/** 反例档案的阶段/检查记录（机器可读事件）。 */
@Serializable
data class CaseStageRecorded(
    val archiveId: String,
    val stage: String,
    val status: String,
    val affectedObjectIds: List<String> = emptyList(),
    val assertionKey: String,
    val held: Boolean,
    val evidence: Map<String, String> = emptyMap(),
) : EventPayload
