package org.chrono.store

import org.chrono.calc.CalibrationConfig
import org.chrono.calc.CalibrationCurve
import org.chrono.calc.CalibrationEngine
import org.chrono.calc.CalibrationResult
import org.chrono.calc.OrderingEngine
import org.chrono.calc.OrderingResult
import org.chrono.model.CalibrationCurveSpec
import org.chrono.model.DomainEvent.DecisionRecorded
import org.chrono.model.DomainEvent.DecisionRevoked
import org.chrono.model.DomainEvent
import org.chrono.model.DomainEvent.DerivationRecorded
import org.chrono.model.EpochSystemSpec
import org.chrono.model.ExclusionSpec
import org.chrono.model.Hashing
import org.chrono.model.IdKinds
import org.chrono.model.MeasurementSpec
import org.chrono.model.OffsetBindingSpec
import org.chrono.model.ProvenanceRef
import org.chrono.model.ReservoirSpec
import org.chrono.model.SequenceConstraintSpec
import org.chrono.model.StoredEvent
import org.chrono.model.canonicalJson
import org.chrono.model.nowIso
import org.chrono.timex.CClock

class ServiceFailure(message: String) : RuntimeException(message)

data class SourceCommitInput(
    val itemId: String,
    val kind: String,
    val payloadJson: String,
    val payloadClass: String,
    val sourceRef: String,
    val note: String? = null,
    val actor: String = "reviewer",
)

class ProjectService(
    private val store: SqliteEventStore,
    private val clock: CClock,
    private val calibrationConfig: CalibrationConfig = CalibrationConfig(),
) {
    fun createProject(code: String, title: String, actor: String = "reviewer", note: String? = null): StoredEvent {
        if (store.count() != 0) throw ServiceFailure("project already exists in this database")
        return store.append(DomainEvent.ProjectCreated(nowIso(clock), actor, code, title, note))
    }

    fun commit(input: SourceCommitInput): StoredEvent {
        validatePayload(input.kind, input.payloadJson)
        val view = view()
        val existing = view.items[input.itemId]
        if (existing != null && existing.kind != input.kind) {
            throw ServiceFailure("item ${input.itemId} kind is immutable (${existing.kind})")
        }
        val version = (existing?.version ?: 0) + 1
        return store.append(
            DomainEvent.SourceCommitted(
                at = nowIso(clock),
                actor = input.actor,
                itemId = input.itemId,
                kind = input.kind,
                version = version,
                parentVersion = existing?.version,
                payloadClass = input.payloadClass,
                payloadJson = input.payloadJson,
                sourceRef = input.sourceRef,
                contentSha = Hashing.sha256Hex(input.payloadJson),
                note = input.note,
            ),
        )
    }

    /**
     * Recompute every calibration and ordering derivable from the CURRENT input set.
     * Re-running after a source revision records new derivation events (new ids); older
     * derivations stay in the log and are exposed as stale rather than overwritten.
     */
    fun recompute(actor: String = "engine", note: String? = null): List<StoredEvent> {
        val recorded = mutableListOf<StoredEvent>()
        val view = view()
        val curve = view.curves().maxByOrNull { it.commitSeq } ?: return recorded
        val reservoirsByCode = view.reservoirs().associate { PayloadCodec.decodeReservoir(it).reservoirCode to it }
        val exclusions = view.exclusions().map(PayloadCodec::decodeExclusion).map { it.sampleCode }.toSet()

        val calibrationResults = linkedMapOf<String, CalibrationResult>()
        view.measurements().forEach { measurementRevision ->
            val measurement = PayloadCodec.decodeMeasurement(measurementRevision)
            val bindingRevision = view.bindings().firstOrNull { revision ->
                PayloadCodec.decodeBinding(revision).sampleCode == measurement.sampleCode
            } ?: return@forEach
            val binding = PayloadCodec.decodeBinding(bindingRevision)
            val reservoirRevision = reservoirsByCode[binding.reservoirCode] ?: return@forEach
            val result = CalibrationEngine(calibrationConfig).calibrate(
                sampleCode = measurement.sampleCode,
                measuredAge = measurement.age.value,
                measuredSigma = measurement.sigma.value,
                curve = CalibrationCurve(PayloadCodec.decodeCurve(curve)),
                offset = PayloadCodec.decodeReservoir(reservoirRevision).offset,
            )
            calibrationResults[measurement.sampleCode] = result
            val sources = listOf(ref(curve), ref(measurementRevision), ref(reservoirRevision), ref(bindingRevision))
            val fingerprint = Hashing.sha256Hex(
                result.parameterFingerprint + "|" + sources.joinToString("|") { it.eventId },
            ).take(16)
            recorded += store.append(
                DerivationRecorded(
                    at = nowIso(clock),
                    actor = actor,
                    derivationId = "${IdKinds.CALIBRATION}_${result.sampleCode}_$fingerprint",
                    kind = DerivationKinds.CALIBRATION,
                    algorithmVersion = result.algorithmVersion,
                    parameterFingerprint = fingerprint,
                    resultJson = kotlinx.serialization.json.Json.encodeToString(kotlinx.serialization.serializer<CalibrationResult>(), result),
                    directSources = sources,
                    note = note,
                ),
            )
        }

        view.sequences().forEach { sequenceRevision ->
            val sequence = PayloadCodec.decodeSequence(sequenceRevision)
            val ordered = chainOrder(sequence)
            val active = ordered.mapNotNull(calibrationResults::get)
            if (active.size < 2) {
                recorded += diagnostic(
                    code = "SEQ-MISSING-MEMBERS",
                    severity = "warn",
                    subject = sequence.chainCode,
                    message = "chain needs >=2 calibrated, non-excluded members",
                    sources = listOf(ref(sequenceRevision)),
                    actor = actor,
                )
                return@forEach
            }
            val result = runCatching {
                OrderingEngine(calibrationConfig).evaluate(sequence, sequence.chainCode, active, exclusions)
            }.getOrElse { failure ->
                recorded += diagnostic(
                    code = "SEQ-UNSUPPORTED-TOPOLOGY",
                    severity = "error",
                    subject = sequence.chainCode,
                    message = failure.message ?: "sequence evaluation failed",
                    sources = listOf(ref(sequenceRevision)),
                    actor = actor,
                )
                return@forEach
            }
            val sourceRefs = listOf(ref(sequenceRevision)) + active.map { calibration ->
                val calibrationEventId = recorded.lastOrNull { recordedEvent ->
                    val payload = recordedEvent.payload
                    payload is DerivationRecorded && payload.derivationId.startsWith("${IdKinds.CALIBRATION}_${calibration.sampleCode}_")
                }?.eventId
                ProvenanceRef(calibrationEventId ?: "unresolved", DerivationKinds.CALIBRATION)
            }
            val fingerprint = Hashing.sha256Hex(
                result.algorithmVersion + "|" + sourceRefs.joinToString("|") { it.eventId },
            ).take(16)
            recorded += store.append(
                DerivationRecorded(
                    at = nowIso(clock),
                    actor = actor,
                    derivationId = "${IdKinds.ORDERING}_${sequence.chainCode}_$fingerprint",
                    kind = DerivationKinds.ORDERING,
                    algorithmVersion = result.algorithmVersion,
                    parameterFingerprint = fingerprint,
                    resultJson = kotlinx.serialization.json.Json.encodeToString(kotlinx.serialization.serializer<OrderingResult>(), result),
                    directSources = sourceRefs,
                    note = note,
                ),
            )
        }
        return recorded
    }

    private fun chainOrder(sequence: SequenceConstraintSpec): List<String> {
        val order = mutableListOf<String>()
        val youngerByOlder = sequence.edges.associate { it.olderSample to it.youngerSample }
        val youngerSet = sequence.edges.map { it.youngerSample }.toSet()
        var head = sequence.edges.first { it.olderSample !in youngerSet }.olderSample
        order += head
        while (true) {
            head = youngerByOlder[head] ?: break
            order += head
        }
        check(order.size == sequence.edges.size + 1) {
            "chain ${sequence.chainCode} is not a single connected chain"
        }
        return order
    }

    fun decide(
        subject: String,
        verdict: String,
        rationale: String,
        candidates: List<String> = emptyList(),
        rejectedReason: String? = null,
        sources: List<ProvenanceRef> = emptyList(),
        actor: String = "reviewer",
        note: String? = null,
    ): StoredEvent {
        val view = view()
        val decisionId = "${IdKinds.DECISION}_${Hashing.short("$subject|$verdict|${view.events.size}|${clock.now()}")}"
        val event = store.append(
            DecisionRecorded(
                at = nowIso(clock),
                actor = actor,
                decisionId = decisionId,
                subject = subject,
                verdict = verdict,
                rationale = rationale,
                candidates = candidates,
                rejectedReason = rejectedReason,
                directSources = sources,
                note = note,
            ),
        )
        val opposing = view.effectiveDecisions().firstOrNull { it.subject == subject && it.verdict != verdict }
        if (opposing != null) {
            val conflictId = "conflict_${Hashing.short("${opposing.decisionId}|$decisionId")}"
            store.append(
                DomainEvent.ConflictRecorded(
                    at = nowIso(clock),
                    actor = actor,
                    conflictId = conflictId,
                    decisionA = opposing.decisionId,
                    decisionB = decisionId,
                    subject = subject,
                    description = "Contradictory human decisions on $subject: '${opposing.verdict}' vs '$verdict'. Both retained; resolution requires an explicit event.",
                    note = "inputs preserved, candidates remain first-class",
                ),
            )
        }
        return event
    }

    fun revokeDecision(decisionId: String, reason: String, actor: String = "reviewer"): StoredEvent =
        store.append(DecisionRevoked(nowIso(clock), actor, decisionId, reason, "revocation appended; nothing deleted"))

    fun resolveConflict(conflictId: String, resolution: String, actor: String = "reviewer"): StoredEvent =
        store.append(DomainEvent.ConflictResolved(nowIso(clock), actor, conflictId, resolution))

    fun diagnostic(
        code: String,
        severity: String,
        subject: String,
        message: String,
        sources: List<ProvenanceRef>,
        actor: String = "engine",
    ): StoredEvent = store.append(
        DomainEvent.DiagnosticRecorded(
            at = nowIso(clock),
            actor = actor,
            diagnosticId = "${IdKinds.DIAGNOSTIC}_${Hashing.short("$code|$subject|${store.count()}")}",
            severity = severity,
            code = code,
            subject = subject,
            message = message,
            directSources = sources,
        ),
    )

    fun view(upToSeq: Long? = null): ProjectView = Projector.replay(store.loadAll(), upToSeq)

    private fun ref(revision: ItemRevision) = ProvenanceRef(revision.commitEventId, revision.kind)

    private fun validatePayload(kind: String, payloadJson: String) {
        when (kind) {
            ItemKinds.CURVE -> canonicalJson.decodeFromString<CalibrationCurveSpec>(payloadJson)
            ItemKinds.MEASUREMENT -> canonicalJson.decodeFromString<MeasurementSpec>(payloadJson)
            ItemKinds.RESERVOIR -> canonicalJson.decodeFromString<ReservoirSpec>(payloadJson)
            ItemKinds.OFFSET_BINDING -> canonicalJson.decodeFromString<OffsetBindingSpec>(payloadJson)
            ItemKinds.EPOCH -> canonicalJson.decodeFromString<EpochSystemSpec>(payloadJson)
            ItemKinds.SEQUENCE -> canonicalJson.decodeFromString<SequenceConstraintSpec>(payloadJson)
            ItemKinds.EXCLUSION -> canonicalJson.decodeFromString<ExclusionSpec>(payloadJson)
            else -> throw ServiceFailure("unknown source kind: $kind")
        }
    }
}
