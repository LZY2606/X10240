package org.chrono.domain

import kotlinx.serialization.Serializable

/**
 * 事件溯源：一切变化都是追加事件。seq 为稳定顺序身份，eventId 为稳定内容身份。
 * 回放到任意 seq 即恢复当时的依赖版本与可见结论；新计算绝不覆盖历史。
 */
@Serializable
sealed class DomainEvent {
    abstract val eventId: String
    abstract val projectId: String
    abstract val actor: String
    abstract val occurredAt: String // ISO-8601，由可注入时钟产生（测试用虚拟时间）
    abstract val reason: String?
    abstract val sourceRef: String

    @Serializable
    data class ProjectCreated(
        override val eventId: String, override val projectId: String,
        val projectName: String, val calendarSystem: String,
        override val actor: String, override val occurredAt: String,
        override val reason: String? = null, override val sourceRef: String
    ) : DomainEvent()

    @Serializable
    data class CurveImported(
        override val eventId: String, override val projectId: String,
        val curve: CalibrationCurve,
        override val actor: String, override val occurredAt: String,
        override val reason: String? = null, override val sourceRef: String
    ) : DomainEvent()

    @Serializable
    data class MeasurementSubmitted(
        override val eventId: String, override val projectId: String,
        val measurement: Measurement,
        override val actor: String, override val occurredAt: String,
        override val reason: String? = null, override val sourceRef: String
    ) : DomainEvent()

    @Serializable
    data class MeasurementRevised(
        override val eventId: String, override val projectId: String,
        val measurement: Measurement, // version + 1, parentVersion 指向前版
        override val actor: String, override val occurredAt: String,
        override val reason: String? = null, override val sourceRef: String
    ) : DomainEvent()

    @Serializable
    data class SequenceDeclared(
        override val eventId: String, override val projectId: String,
        val edge: SequenceEdge,
        override val actor: String, override val occurredAt: String,
        override val reason: String? = null, override val sourceRef: String
    ) : DomainEvent()

    @Serializable
    data class ExclusionRecorded(
        override val eventId: String, override val projectId: String,
        val decision: ExclusionDecision,
        override val actor: String, override val occurredAt: String,
        override val reason: String? = null, override val sourceRef: String
    ) : DomainEvent()

    /** 撤销排除（生成新决定，不删除旧事件）。 */
    @Serializable
    data class ExclusionReversed(
        override val eventId: String, override val projectId: String,
        val decision: ExclusionDecision,
        override val actor: String, override val occurredAt: String,
        override val reason: String? = null, override val sourceRef: String
    ) : DomainEvent()

    /** 人工对层序冲突候选的裁定；rejected 时保留拒绝原因。 */
    @Serializable
    data class ConflictAdjudicated(
        override val eventId: String, override val projectId: String,
        val edgeId: String,
        val accepted: Boolean,
        val adjustmentKind: String,
        val targetMeasurementId: String,
        val payloadJson: String,
        val rejectReason: String? = null,
        override val actor: String, override val occurredAt: String,
        override val reason: String? = null, override val sourceRef: String
    ) : DomainEvent()

    /** 采纳「改用较老峰区间」：对该测量的候选视图施加区间约束（派生层参数，不改测量）。 */
    @Serializable
    data class IntervalRestrictionApplied(
        override val eventId: String, override val projectId: String,
        val measurementId: String,
        val fromAstro: Double, val toAstro: Double,
        val originEdgeId: String,
        override val actor: String, override val occurredAt: String,
        override val reason: String? = null, override val sourceRef: String
    ) : DomainEvent()

    @Serializable
    data class IntervalRestrictionLifted(
        override val eventId: String, override val projectId: String,
        val measurementId: String,
        val originEdgeId: String,
        override val actor: String, override val occurredAt: String,
        override val reason: String? = null, override val sourceRef: String
    ) : DomainEvent()

    /** 曲线升版：新版进入项目；旧版结果保留并标记过期，而非重写。 */
    @Serializable
    data class CurveUpgraded(
        override val eventId: String, override val projectId: String,
        val curve: CalibrationCurve,
        override val actor: String, override val occurredAt: String,
        override val reason: String? = null, override val sourceRef: String
    ) : DomainEvent()

    /** 明确把某测量重新钉到新版本（局部重算）。 */
    @Serializable
    data class CurvePinChanged(
        override val eventId: String, override val projectId: String,
        val measurementId: String, val measurementVersion: Int,
        val fromCurveVersion: String, val toCurveVersion: String,
        override val actor: String, override val occurredAt: String,
        override val reason: String? = null, override val sourceRef: String
    ) : DomainEvent()

    @Serializable
    data class NoteRecorded(
        override val eventId: String, override val projectId: String,
        val subject: String, val text: String,
        override val actor: String, override val occurredAt: String,
        override val reason: String? = null, override val sourceRef: String
    ) : DomainEvent()
}
