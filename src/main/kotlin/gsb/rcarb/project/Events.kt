package gsb.rcarb.project

import gsb.rcarb.domain.*
import kotlinx.serialization.Serializable

/** 档案事件类型。原始输入只追加、不可覆盖；修订以新版本+父版本表达。 */
@Serializable
sealed class EventPayload {
    @Serializable
    data class ProjectCreated(val projectId: String, val title: String) : EventPayload()

    @Serializable
    data class MeasurementSubmitted(val measurement: Measurement) : EventPayload()

    @Serializable
    data class CurveSubmitted(val curve: CalibrationCurve) : EventPayload()

    @Serializable
    data class ReservoirSubmitted(val reservoir: ReservoirModel) : EventPayload()

    @Serializable
    data class SequenceSubmitted(val constraint: SequenceConstraint) : EventPayload()

    @Serializable
    data class EraSystemSubmitted(
        val eraSystemId: String,
        val scheme: String,
        val anchorAstroYear: Int,
        val sourceRef: SourceRef
    ) : EventPayload()

    @Serializable
    data class Quarantined(
        val rawText: String,
        val expectedKind: String,
        val diagnostics: List<String>,
        val sourceRef: SourceRef
    ) : EventPayload()

    @Serializable
    data class CalibrationInputCreated(
        val inputId: String,
        val parentInputId: String? = null,
        val measurementId: String,
        val measurementVersion: Int,
        val curveId: String,
        val curveVersion: String,
        val reservoirId: String? = null,
        val reservoirVersion: Int? = null,
        val createdBy: String
    ) : EventPayload()

    @Serializable
    data class DecisionRecorded(
        val decisionId: String,
        val inputId: String,
        val intervalId: String,
        val action: ExclusionDecision.Action,
        val reason: String,
        val decidedBy: String,
        val supersedesDecision: String? = null
    ) : EventPayload()

    /** 对曲线升版的显式登记：标注被替代版本，便于反查差异来源。 */
    @Serializable
    data class CurveUpgradeRegistered(
        val curveId: String,
        val oldVersion: String,
        val newVersion: String,
        val note: String
    ) : EventPayload()
}

@Serializable
data class DossierEvent(
    val eventId: String,
    val projectId: String,
    val seq: Long,
    val at: String,
    val payload: EventPayload,
    val directSource: SourceRef
)
