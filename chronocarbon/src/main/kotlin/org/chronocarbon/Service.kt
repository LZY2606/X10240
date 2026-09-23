package org.chronocarbon

import kotlinx.serialization.Serializable

class ValidationException(message: String) : RuntimeException(message)

@Serializable
data class StalenessNotice(val stale: Boolean, val reason: String? = null)

/**
 * Application service. Every mutating call appends an event; derivations are computed
 * deterministically and stored with algorithm version, fingerprint and direct sources.
 */
class CarbonService(
    private val store: EventStore,
    private val clock: Clock,
    private val actor: String = "reviewer"
) {
    val projectId: String get() = store.projectId

    fun state(atSeq: Long? = null): ProjectState = Project.reduce(store.events(atSeq)).state

    private fun append(type: String, event: DomainEvent, note: String = ""): EventEnvelope {
        val at = clock.now().toString()
        val envelope = EventEnvelope(
            seq = store.nextSeq(),
            eventId = event.eventId,
            type = type,
            at = at,
            actor = event.actor,
            payloadJson = JsonCodec.encode(event)
        )
        store.append(envelope)
        return envelope
    }

    fun createProject(name: String): EventEnvelope {
        require(store.events().isEmpty()) { "project already exists" }
        val id = "proj-" + Hashing.sha256Hex(name + clock.now()).take(10)
        return append(
            "project.created",
            DomainEvent.ProjectCreated(stableId("evt", "create$name$id"), actor, "create project", id, name)
        )
    }

    fun registerCurve(curve: CalibrationCurve, setActive: Boolean = true): EventEnvelope {
        val state = state()
        if (state.curves.containsKey(curve.version))
            throw ValidationException("curve version already registered: ${curve.version}")
        return append(
            "curve.registered",
            DomainEvent.CurveRegistered(stableId("evt", "curve${curve.version}"), actor, "register ${curve.version}", curve, setActive)
        )
    }

    fun upgradeCurve(newCurve: CalibrationCurve, note: String): EventEnvelope {
        val state = state()
        val previous = state.activeCurveVersion
            ?: throw ValidationException("cannot upgrade before a baseline curve is active")
        require(newCurve.successorOf == previous) {
            "curve upgrade chain mismatch: ${newCurve.version} declares successorOf=${newCurve.successorOf}, active=$previous"
        }
        if (state.curves.containsKey(newCurve.version))
            throw ValidationException("curve version already registered: ${newCurve.version}")
        val envelope = append(
            "curve.upgraded",
            DomainEvent.CurveUpgraded(stableId("evt", "upgrade${newCurve.version}"), actor, note, newCurve, previous)
        )
        markCalibrationsStaleForCurve(previous, newCurve.version)
        return envelope
    }

    fun submitMeasurement(measurement: Measurement): EventEnvelope {
        val state = state()
        if (state.measurements.containsKey(measurement.id) && measurement.parentId == null)
            throw ValidationException("measurement id already exists; revisions require parentId: ${measurement.id}")
        val envelope = append(
            "measurement.submitted",
            DomainEvent.MeasurementSubmitted(stableId("evt", "meas${measurement.id}"), actor, "submit ${measurement.code}", measurement)
        )
        measurement.parentId?.let { parentId ->
            // Local invalidation: only the revised family's calibrations and sequence rows expire.
            store.markCalibrationsStale { record ->
                record.directSources.contains("measurement:$parentId")
            }
            invalidateSequenceForMeasurements(listOf(parentId, measurement.id))
        }
        return envelope
    }

    fun submitOffsetHypothesis(hypothesis: OffsetHypothesis): EventEnvelope {
        val state = state()
        require(state.measurements.containsKey(hypothesis.measurementId)) { "unknown measurement ${hypothesis.measurementId}" }
        val envelope = append(
            "offset.submitted",
            DomainEvent.OffsetHypothesisSubmitted(
                stableId("evt", "hyp${hypothesis.id}"), actor, "reservoir hypothesis ${hypothesis.name}", hypothesis
            )
        )
        // Only calibrations using this measurement's active hypothesis become stale.
        store.markCalibrationsStale { record ->
            record.directSources.contains("hypothesis:" + (hypothesis.parentId ?: previousActiveHypothesisId(hypothesis.measurementId))) ||
                record.directSources.contains("hypothesis:" + hypothesis.id)
        }
        invalidateSequenceForMeasurements(listOf(hypothesis.measurementId))
        return envelope
    }

    private fun previousActiveHypothesisId(measurementId: String): String? =
        state().hypotheses.values.lastOrNull { it.measurementId == measurementId }?.id

    fun submitConstraint(constraint: SequenceConstraint): EventEnvelope {
        val state = state()
        require(state.measurements.containsKey(constraint.olderMeasurementId)) { "unknown older measurement" }
        require(state.measurements.containsKey(constraint.youngerMeasurementId)) { "unknown younger measurement" }
        return append(
            "constraint.submitted",
            DomainEvent.ConstraintSubmitted(stableId("evt", "con${constraint.id}"), actor, constraint.rationale, constraint)
        )
    }

    fun recordDecision(decision: Decision): EventEnvelope {
        val state = state()
        require(state.measurements.containsKey(decision.targetMeasurementId)) { "unknown measurement" }
        val envelope = append(
            "decision.recorded",
            DomainEvent.DecisionRecorded(stableId("evt", "dec${decision.id}"), actor, decision.reason, decision)
        )
        invalidateSequenceForMeasurements(listOf(decision.targetMeasurementId))
        return envelope
    }

    fun requestCalibration(request: CalibrationRequest): DerivationRecord {
        val state = state()
        val measurement = state.measurements[request.measurementId]
            ?: throw ValidationException("unknown measurement ${request.measurementId}")
        val curve = state.curves[request.curveVersion]
            ?: throw ValidationException("unknown curve version ${request.curveVersion}")
        if (!measurement.valid) throw ValidationException("measurement ${measurement.id} carries unconvertible input; kept as diagnostic")
        val result = Calibrator.calibrate(measurement, curve, request)
        val hypothesisId = state.activeHypothesis(request.measurementId)?.id
        val sources = buildList {
            add("measurement:${measurement.id}")
            add("curve:${curve.version}")
            hypothesisId?.let { add("hypothesis:$it") }
            add("algorithm:${ALGORITHM_VERSION}")
        }
        val record = DerivationRecord(
            id = stableId("der", "cal${request.fingerprint()}${store.events().size}"),
            eventSeq = store.nextSeq(),
            kind = "calibration",
            requestFingerprint = request.fingerprint(),
            algorithmVersion = ALGORITHM_VERSION,
            directSources = sources,
            resultJson = JsonCodec.encode(result)
        )
        val envelope = append(
            "calibration.requested",
            DomainEvent.CalibrationRequested(stableId("evt", "cal${request.fingerprint()}"), actor, "calibrate ${measurement.code}", request)
        )
        return store.saveDerivation(record.copy(eventSeq = envelope.seq))
    }

    fun latestCalibration(measurementId: String, curveVersion: String? = null): DerivationRecord? {
        val state = state()
        val version = curveVersion ?: state.activeCurveVersion
        return store.derivations("calibration")
            .filter { it.resultJson.contains(measurementId) && it.resultJson.contains(version ?: "") }
            .maxByOrNull { it.eventSeq }
    }

    fun analyzeSequences(seed: Long = 7321L): SequenceAnalysis {
        val state = state()
        val evidence = ArrayList<OrderingEvidence>()
        val candidateIds = ArrayList<String>()
        val seq = store.nextSeq()
        val analysisId = stableId("ana", "seq$seq$seed")

        for (constraint in state.activeConstraints()) {
            val olderResult = currentCalibrationResult(constraint.olderMeasurementId)
            val youngerResult = currentCalibrationResult(constraint.youngerMeasurementId)
            if (olderResult == null || youngerResult == null) {
                evidence += OrderingEvidence(
                    constraint.id, constraint.olderMeasurementId, constraint.youngerMeasurementId,
                    Double.NaN, "unknown", "calibration missing for one side"
                )
                continue
            }
            val p = Project(state).orderingProbability(olderResult, youngerResult)
            val (status, message) = when {
                p >= 0.80 -> "consistent" to "stratigraphy supported (P=%.3f)".fmt(p)
                p <= 0.20 -> "contradicted" to "stratigraphy contradicted (P=%.3f)".fmt(p)
                else -> "weak" to "ordering indeterminate (P=%.3f)".fmt(p)
            }
            evidence += OrderingEvidence(constraint.id, constraint.olderMeasurementId, constraint.youngerMeasurementId, p, status, message)

            if (status == "contradicted") {
                candidateIds += proposeCandidates(analysisId, constraint, seq)
            }
        }
        val analysis = SequenceAnalysis(analysisId, evidence, candidateIds, seq)
        append(
            "sequence.analyzed",
            DomainEvent.SequenceAnalyzed(stableId("evt", analysisId), actor, "sequence analysis seed=$seed", analysis)
        )
        val record = DerivationRecord(
            id = stableId("der", "seq$analysisId"),
            eventSeq = seq,
            kind = "sequence",
            requestFingerprint = Hashing.sha256Hex("sequence|$seed|${evidence.joinToString { it.constraintId }}"),
            algorithmVersion = ALGORITHM_VERSION,
            directSources = state.activeConstraints().flatMap { listOf("constraint:${it.id}", "measurement:${it.olderMeasurementId}", "measurement:${it.youngerMeasurementId}") }
                .distinct(),
            resultJson = JsonCodec.encode(analysis)
        )
        store.saveDerivation(record)
        return analysis
    }

    private fun proposeCandidates(analysisId: String, constraint: SequenceConstraint, seq: Long): List<String> {
        val created = ArrayList<String>()
        val kinds = listOf(
            "change_offset" to "Re-examine reservoir offset on ${constraint.olderMeasurementId} or ${constraint.youngerMeasurementId}; raw measurement is not edited.",
            "add_exclusion" to "Consider excluding one sample (e.g. intrusion/bioturbation) via a human decision.",
            "relax_constraint" to "Relax or revise the claimed stratigraphic relation ${constraint.id}.",
            "recheck_measurement" to "Send sample for re-measurement; the lab value remains authoritative until a new version is submitted."
        )
        for ((kind, description) in kinds) {
            val id = stableId("cand", "$analysisId${constraint.id}$kind")
            if (state().candidates.containsKey(id)) continue
            val candidate = AdjustmentCandidate(
                id = id,
                analysisId = analysisId,
                kind = kind,
                subjectId = constraint.id,
                description = description,
                createdAtEventSeq = seq
            )
            store.saveCandidate(candidate)
            created += id
        }
        return created
    }

    /** Resolve a candidate. Accepting change_offset/add_exclusion creates a *new* input version. */
    fun resolveCandidate(
        candidateId: String,
        accept: Boolean,
        rejectionReason: String? = null,
        newOffset: OffsetHypothesis? = null,
        newExclusion: Decision? = null
    ): List<EventEnvelope> {
        val state = state()
        val candidate = store.candidate(candidateId)
            ?: throw ValidationException("unknown candidate $candidateId")
        val followUps = ArrayList<EventEnvelope>()
        if (accept) {
            when (candidate.kind) {
                "change_offset" -> {
                    val hypothesis = newOffset
                        ?: throw ValidationException("accepting change_offset requires a new offset hypothesis")
                    followUps += submitOffsetHypothesis(hypothesis)
                }
                "add_exclusion" -> {
                    val decision = newExclusion
                        ?: throw ValidationException("accepting add_exclusion requires a decision")
                    followUps += recordDecision(decision)
                }
                else -> Unit
            }
        }
        val resolveEvent = append(
            "candidate.resolved",
            DomainEvent.CandidateResolved(
                eventId = stableId("evt", "resolve$candidateId$accept${clock.now()}"),
                actor = actor,
                note = if (accept) "accepted ${candidate.kind}" else "rejected: ${rejectionReason ?: "no reason recorded"}",
                candidateId = candidateId,
                accept = accept,
                rejection = rejectionReason?.let { Rejection("reviewer_rejected", it) },
                followUpEventIds = followUps.map { it.eventId }
            )
        )
        store.resolveCandidate(candidateId, accept, resolveEvent.eventId, rejectionReason)
        return followUps + resolveEvent
    }

    fun undo(eventId: String, note: String = "undo"): EventEnvelope {
        val target = store.event(eventId) ?: throw ValidationException("unknown event $eventId")
        if (target.undone) throw ValidationException("event already undone")
        store.markUndone(eventId)
        val envelope = append(
            "event.undone",
            DomainEvent.EventUndone(stableId("evt", "undo$eventId${clock.now()}"), actor, note, eventId)
        )
        // Recompute staleness for everything the undone event fed.
        store.markCalibrationsStale { true }
        store.markSequenceStale { true }
        return envelope
    }

    private fun currentCalibrationResult(measurementId: String): CalibrationResult? {
        val state = state()
        val version = state.activeCurveVersion ?: return null
        val record = latestCalibration(measurementId, version) ?: return null
        return runCatching { JsonCodec.decode<CalibrationResult>(record.resultJson) }.getOrNull()
    }

    private fun invalidateSequenceForMeasurements(measurementIds: List<String>) {
        store.markSequenceStale { record ->
            val text = record.resultJson
            measurementIds.any { text.contains("\"olderMeasurementId\":\"$it\"") || text.contains("\"youngerMeasurementId\":\"$it\"") }
        }
    }

    private fun markCalibrationsStaleForCurve(oldVersion: String, newVersion: String) {
        store.markCalibrationsStale { record ->
            record.directSources.contains("curve:$oldVersion") && !record.stale
        }
    }

    private fun String.fmt(v: Double) = this.format(v)
}
