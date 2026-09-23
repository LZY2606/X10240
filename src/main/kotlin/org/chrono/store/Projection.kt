package org.chrono.store

import kotlinx.serialization.Serializable
import org.chrono.model.CalibrationCurveSpec
import org.chrono.model.DomainEvent
import org.chrono.model.EpochSystemSpec
import org.chrono.model.ExclusionSpec
import org.chrono.model.IdKinds
import org.chrono.model.MeasurementSpec
import org.chrono.model.OffsetBindingSpec
import org.chrono.model.ProvenanceRef
import org.chrono.model.ReservoirSpec
import org.chrono.model.SequenceConstraintSpec
import org.chrono.model.StoredEvent
import org.chrono.model.canonicalJson

@Serializable
data class ItemRevision(
    val itemId: String,
    val kind: String,
    val version: Int,
    val parentVersion: Int?,
    val payloadClass: String,
    val payloadJson: String,
    val sourceRef: String,
    val contentSha: String,
    val commitEventId: String,
    val commitSeq: Long,
)

@Serializable
data class Derivation(
    val derivationId: String,
    val kind: String,
    val algorithmVersion: String,
    val parameterFingerprint: String,
    val resultJson: String,
    val directSources: List<ProvenanceRef>,
    val recordedSeq: Long,
    val recordedEventId: String,
)

@Serializable
data class Decision(
    val decisionId: String,
    val subject: String,
    val verdict: String,
    val rationale: String,
    val candidates: List<String>,
    val rejectedReason: String?,
    val effective: Boolean,
    val directSources: List<ProvenanceRef>,
    val recordedSeq: Long,
)

@Serializable
data class Conflict(
    val conflictId: String,
    val decisionA: String,
    val decisionB: String,
    val subject: String,
    val description: String,
    val resolved: Boolean,
    val resolution: String?,
)

@Serializable
data class Diagnostic(
    val diagnosticId: String,
    val severity: String,
    val code: String,
    val subject: String,
    val message: String,
    val directSources: List<ProvenanceRef>,
)

@Serializable
data class ProjectView(
    val projectCode: String?,
    val title: String?,
    val events: List<StoredEvent>,
    val items: Map<String, ItemRevision>,
    val revisions: Map<String, List<ItemRevision>>,
    val derivations: List<Derivation>,
    val decisions: List<Decision>,
    val conflicts: List<Conflict>,
    val diagnostics: List<Diagnostic>,
) {
    fun curves(): List<ItemRevision> = items.values.filter { it.kind == ItemKinds.CURVE }
    fun measurements(): List<ItemRevision> = items.values.filter { it.kind == ItemKinds.MEASUREMENT }
    fun reservoirs(): List<ItemRevision> = items.values.filter { it.kind == ItemKinds.RESERVOIR }
    fun bindings(): List<ItemRevision> = items.values.filter { it.kind == ItemKinds.OFFSET_BINDING }
    fun epochs(): List<ItemRevision> = items.values.filter { it.kind == ItemKinds.EPOCH }
    fun sequences(): List<ItemRevision> = items.values.filter { it.kind == ItemKinds.SEQUENCE }
    fun exclusions(): List<ItemRevision> = items.values.filter { it.kind == ItemKinds.EXCLUSION }

    fun itemOf(ref: ProvenanceRef): ItemRevision? =
        revisions[ref.kind]?.firstOrNull { it.commitEventId == ref.eventId }
            ?: items.values.firstOrNull { it.commitEventId == ref.eventId }

    fun effectiveDecisions(): List<Decision> = decisions.filter { it.effective }
}

object ItemKinds {
    const val CURVE = "calibration_curve"
    const val MEASUREMENT = "measurement"
    const val RESERVOIR = "reservoir_offset"
    const val OFFSET_BINDING = "offset_binding"
    const val EPOCH = "epoch_system"
    const val SEQUENCE = "sequence_constraint"
    const val EXCLUSION = "exclusion"
}

object DerivationKinds {
    const val CALIBRATION = "calibration"
    const val ORDERING = "ordering"
}

class Projector {
    private val items = linkedMapOf<String, ItemRevision>()
    private val revisions = linkedMapOf<String, MutableList<ItemRevision>>()
    private val derivations = mutableListOf<Derivation>()
    private val decisions = linkedMapOf<String, Decision>()
    private val conflicts = linkedMapOf<String, Conflict>()
    private val diagnostics = linkedMapOf<String, Diagnostic>()
    private val events = mutableListOf<StoredEvent>()
    private var projectCode: String? = null
    private var title: String? = null

    fun apply(event: StoredEvent) {
        events += event
        when (val payload = event.payload) {
            is DomainEvent.ProjectCreated -> {
                projectCode = payload.projectCode
                title = payload.title
            }
            is DomainEvent.SourceCommitted -> {
                val revision = ItemRevision(
                    itemId = payload.itemId,
                    kind = payload.kind,
                    version = payload.version,
                    parentVersion = payload.parentVersion,
                    payloadClass = payload.payloadClass,
                    payloadJson = payload.payloadJson,
                    sourceRef = payload.sourceRef,
                    contentSha = payload.contentSha,
                    commitEventId = event.eventId,
                    commitSeq = event.seq,
                )
                val previous = items[payload.itemId]
                if (previous != null) {
                    check(revisions[payload.itemId]?.any { it.version == revision.version } != true) {
                        "duplicate source version ${payload.itemId}@${payload.version}"
                    }
                } else {
                    check(payload.version == 1) {
                        "first commit of ${payload.itemId} must be version 1, got ${payload.version}"
                    }
                }
                if (payload.parentVersion != null) {
                    check(revisions[payload.itemId]?.any { it.version == payload.parentVersion } == true) {
                        "parent version ${payload.parentVersion} of ${payload.itemId} is unknown"
                    }
                }
                revisions.getOrPut(payload.itemId) {
                    if (previous != null) mutableListOf(previous) else mutableListOf()
                }.add(revision)
                items[payload.itemId] = revision
            }
            is DomainEvent.DerivationRecorded -> {
                check(derivations.none { it.derivationId == payload.derivationId }) {
                    "duplicate derivation ${payload.derivationId}"
                }
                derivations += Derivation(
                    derivationId = payload.derivationId,
                    kind = payload.kind,
                    algorithmVersion = payload.algorithmVersion,
                    parameterFingerprint = payload.parameterFingerprint,
                    resultJson = payload.resultJson,
                    directSources = payload.directSources,
                    recordedSeq = event.seq,
                    recordedEventId = event.eventId,
                )
            }
            is DomainEvent.DecisionRecorded -> {
                decisions[payload.decisionId] = Decision(
                    decisionId = payload.decisionId,
                    subject = payload.subject,
                    verdict = payload.verdict,
                    rationale = payload.rationale,
                    candidates = payload.candidates,
                    rejectedReason = payload.rejectedReason,
                    effective = payload.effective,
                    directSources = payload.directSources,
                    recordedSeq = event.seq,
                )
            }
            is DomainEvent.DecisionRevoked -> {
                decisions[payload.decisionId]?.let { decisions[payload.decisionId] = it.copy(effective = false) }
            }
            is DomainEvent.ConflictRecorded -> {
                conflicts[payload.conflictId] = Conflict(
                    conflictId = payload.conflictId,
                    decisionA = payload.decisionA,
                    decisionB = payload.decisionB,
                    subject = payload.subject,
                    description = payload.description,
                    resolved = false,
                    resolution = null,
                )
            }
            is DomainEvent.ConflictResolved -> {
                conflicts[payload.conflictId]?.let {
                    conflicts[payload.conflictId] = it.copy(resolved = true, resolution = payload.resolution)
                }
            }
            is DomainEvent.DiagnosticRecorded -> {
                diagnostics[payload.diagnosticId] = Diagnostic(
                    diagnosticId = payload.diagnosticId,
                    severity = payload.severity,
                    code = payload.code,
                    subject = payload.subject,
                    message = payload.message,
                    directSources = payload.directSources,
                )
            }
            is DomainEvent.ImportRecorded, is DomainEvent.ExportRecorded -> Unit
        }
    }

    fun snapshot(): ProjectView = ProjectView(
        projectCode = projectCode,
        title = title,
        events = events.toList(),
        items = LinkedHashMap(items),
        revisions = revisions.mapValues { it.value.toList() },
        derivations = derivations.toList(),
        decisions = decisions.values.toList(),
        conflicts = conflicts.values.toList(),
        diagnostics = diagnostics.values.toList(),
    )

    companion object {
        fun replay(events: List<StoredEvent>, upToSeqInclusive: Long? = null): ProjectView {
            val projector = Projector()
            events.takeWhile { upToSeqInclusive == null || it.seq <= upToSeqInclusive }.forEach(projector::apply)
            return projector.snapshot()
        }
    }
}

object PayloadCodec {
    inline fun <reified T> encode(payload: T): String =
        kotlinx.serialization.json.Json.encodeToString(kotlinx.serialization.serializer<T>(), payload)
    inline fun <reified T> decode(json: String): T = canonicalJson.decodeFromString(json)

    fun decodeCurve(revision: ItemRevision): CalibrationCurveSpec =
        decode<CalibrationCurveSpec>(revision.payloadJson)

    fun decodeMeasurement(revision: ItemRevision): MeasurementSpec =
        decode<MeasurementSpec>(revision.payloadJson)

    fun decodeReservoir(revision: ItemRevision): ReservoirSpec =
        decode<ReservoirSpec>(revision.payloadJson)

    fun decodeBinding(revision: ItemRevision): OffsetBindingSpec =
        decode<OffsetBindingSpec>(revision.payloadJson)

    fun decodeEpoch(revision: ItemRevision): EpochSystemSpec =
        decode<EpochSystemSpec>(revision.payloadJson)

    fun decodeSequence(revision: ItemRevision): SequenceConstraintSpec =
        decode<SequenceConstraintSpec>(revision.payloadJson)

    fun decodeExclusion(revision: ItemRevision): ExclusionSpec =
        decode<ExclusionSpec>(revision.payloadJson)
}

/** A derivation is stale (superseded by a newer input revision) but remains viewable. */
fun ProjectView.isStale(derivation: Derivation): Boolean =
    derivation.directSources.any { ref ->
        val itemId = revisions.entries.firstOrNull { entry -> entry.value.any { it.commitEventId == ref.eventId } }?.key
        itemId != null && items[itemId]?.commitEventId != ref.eventId
    }

fun derivationIdKind(kind: String): String =
    if (kind == DerivationKinds.CALIBRATION) IdKinds.CALIBRATION else IdKinds.ORDERING
