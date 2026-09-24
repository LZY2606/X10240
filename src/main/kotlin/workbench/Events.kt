package workbench

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed class Payload {
    @Serializable @SerialName("MeasurementSubmitted")
    data class MeasurementSubmitted(val m: MeasurementV) : Payload()

    @Serializable @SerialName("CurveRegistered")
    data class CurveRegistered(val c: CurveV) : Payload()

    @Serializable @SerialName("OffsetSubmitted")
    data class OffsetSubmitted(val o: OffsetV) : Payload()

    @Serializable @SerialName("ConstraintSubmitted")
    data class ConstraintSubmitted(val c: ConstraintV) : Payload()

    @Serializable @SerialName("ExclusionSubmitted")
    data class ExclusionSubmitted(val e: ExclusionV) : Payload()

    @Serializable @SerialName("EraSystemSet")
    data class EraSystemSet(val e: EraSystemV) : Payload()

    @Serializable @SerialName("CalibrationComputed")
    data class CalibrationComputed(val r: CalibrationResult) : Payload()

    @Serializable @SerialName("StratChecked")
    data class StratChecked(val s: StratCheckResult) : Payload()

    @Serializable @SerialName("DecisionMade")
    data class DecisionMade(val d: DecisionV) : Payload()

    @Serializable @SerialName("DecisionUndone")
    data class DecisionUndone(val u: UndoV) : Payload()
}

@Serializable
data class Envelope(val seq: Long, val id: String, val at: String, val payload: Payload)

@Serializable
data class ExportFile(val format: String = "rcbench-export-1", val events: List<Envelope>)
