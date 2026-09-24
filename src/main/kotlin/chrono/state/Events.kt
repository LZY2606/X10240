package chrono.state

import chrono.calib.ALGORITHM_VERSION
import chrono.domain.CalibrationCurve
import chrono.domain.CalibrationRun
import chrono.domain.Source
import kotlinx.serialization.Serializable
import java.time.Instant

/**
 * Append-only event envelope. Events are the only thing stored and exported:
 * derived rows are recomputable, but they are also logged as system events so a
 * reopened project replays byte-identical ids and conclusions.
 */
@Serializable
data class Event(
    val seq: Long,
    val eventId: String,
    val projectId: String,
    val type: String,
    val actor: String,
    @Serializable(with = chrono.domain.InstantSerializer::class)
    val at: Instant,
    val basis: String? = null,
    val payloadJson: String
)

/**
 * Stable, human-meaningful ids. They survive delete-db + re-import because ids are
 * derived from content/version position rather than database rowids.
 */
object EventIds {
    fun project(id: String) = "evt-project-$id"
    fun curve(id: String, version: String) = "evt-curve-$id-v${slug(version)}"
    fun activateCurve(id: String, version: String) = "evt-activate-$id-v${slug(version)}"
    fun measurement(id: String, version: Int) = "evt-meas-$id-v$version"
    fun reservoir(id: String, version: Int) = "evt-res-$id-v$version"
    fun hypothesis(hid: String) = "evt-hyp-$hid"
    fun constraint(id: String) = "evt-seq-$id"
    fun era(id: String, version: Int) = "evt-era-$id-v$version"
    fun exclusion(id: String) = "evt-excl-$id"
    fun run(runId: String) = "evt-run-$runId"
    fun evaluation(evaluationId: String) = "evt-seqeval-$evaluationId"
    fun decision(id: String) = "evt-dec-$id"
    fun revoke(decisionId: String, n: Int) = "evt-revoke-$decisionId-$n"
    fun note(id: String) = "evt-note-$id"
    fun slug(v: String) = v.replace('.', '-')
}

// ---- payloads (one @Serializable class per event type) ----

@Serializable
data class ProjectCreated(
    val name: String,
    val eraSystem: String,
    val eraSource: Source,
    @Serializable(with = chrono.domain.InstantSerializer::class)
    val createdAt: Instant
)

@Serializable
data class CurveRegistered(
    val id: String,
    val label: String,
    val version: String,
    val supersedes: String? = null,
    val points: List<CalibrationCurve.CurvePoint>,
    val source: Source
)

@Serializable
data class CurveActivated(
    val curveId: String,
    val version: String,
    val reason: String,
    val source: Source
)

@Serializable
data class MeasurementSubmitted(
    val id: String,
    val version: Int,
    val sampleCode: String,
    val labCode: String,
    val rawAge: String,
    val rawSigma: String,
    val ageBP: Double,
    val sigmaBP: Double,
    val material: String,
    val unit: String,
    val source: Source,
    val parentId: String? = null,
    val unconvertible: Boolean = false,
    val diagnostic: String? = null
)

@Serializable
data class ReservoirSubmitted(
    val id: String,
    val version: Int,
    val label: String,
    val offset: chrono.domain.OffsetSpec,
    val source: Source,
    val parentId: String? = null
)

@Serializable
data class HypothesisCreated(
    val id: String,
    val measurementId: String,
    val reservoirId: String? = null
)

@Serializable
data class ConstraintSubmitted(
    val id: String,
    val olderHypothesisId: String? = null,
    val youngerHypothesisId: String? = null,
    val coevalA: String? = null,
    val coevalB: String? = null,
    val note: String,
    val source: Source
)

@Serializable
data class EraSystemChanged(val eraSystem: String, val source: Source)

@Serializable
data class ExclusionRecorded(
    val id: String,
    val targetType: String,
    val targetId: String,
    val reason: String,
    val source: Source
)

@Serializable
data class ExclusionRevoked(val id: String, val reviewer: String)

@Serializable
data class RunRecorded(val run: CalibrationRun)

@Serializable
data class EvaluationRecorded(val evaluation: chrono.domain.SequenceEvaluation)

@Serializable
data class DecisionRecorded(
    val id: String,
    val intervalId: String,
    val runId: String,
    val verdict: String,
    val rationale: String,
    val reviewer: String
)

@Serializable
data class DecisionSuperseded(val newDecisionId: String, val oldDecisionId: String)

@Serializable
data class DecisionRevoked(val decisionId: String, val reason: String, val reviewer: String)

@Serializable
data class ReviewNote(
    val id: String,
    val text: String,
    val reviewer: String,
    @Serializable(with = chrono.domain.InstantSerializer::class)
    val at: Instant
)

const val ACTOR_SYSTEM = "system"
const val ALGO = ALGORITHM_VERSION
