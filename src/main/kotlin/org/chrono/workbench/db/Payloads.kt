package org.chrono.workbench.db

import kotlinx.serialization.Serializable
import org.chrono.workbench.events.EventRef
import org.chrono.workbench.domain.SequenceVerdict
import org.chrono.workbench.model.*

@Serializable
data class ProjectOpenedPayload(val projectId: String, val name: String, val basis: String)

@Serializable
data class ProjectOpendedSafe(val projectId: String, val name: String)

@Serializable
data class CurvePayload(val curve: Curve)

@Serializable
data class MeasurementPayload(val measurement: Measurement)

@Serializable
data class ReservoirPayload(val reservoir: ReservoirOffset)

@Serializable
data class EraPayload(val era: EraSystem)

@Serializable
data class ConstraintPayload(val constraint: SequenceConstraint)

@Serializable
data class DecisionPayload(val decision: ExclusionDecision)

@Serializable
data class ReviewPayload(
    val measurementId: String,
    val action: String,
    val reason: String,
    val decidedBy: String,
    val fromStatus: String,
    val toStatus: String
)

@Serializable
data class DerivedPayload(
    val derivedId: String,
    val measurementId: String,
    val curveId: String,
    val reservoirId: String?,
    val parameterFingerprint: String,
    val algorithmVersion: String,
    val status: String,
    val multimodal: Boolean,
    val modeCount: Int,
    val intervalCount: Int,
    val intervalMasses: List<Double>,
    val tiedGroups: List<List<String>>,
    val stale: Boolean
)

@Serializable
data class JudgmentPayload(
    val code: String,
    val subjectId: String,
    val decided: Boolean,
    val basis: String,
    val evidenceRef: String,
    val detail: Map<String, String>
)

@Serializable
data class ReversalPayload(
    val reversedEventId: String,
    val reversedType: String,
    val targetObjectId: String,
    val effect: String
)

@Serializable
data class BundleImportPayload(val file: String, val importedEventCount: Int, val mode: String)
