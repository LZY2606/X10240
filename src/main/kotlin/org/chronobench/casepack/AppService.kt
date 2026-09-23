package org.chronobench.casepack

import org.chronobench.db.EventStore
import org.chronobench.domain.CalibrationEngine
import org.chronobench.domain.CalibrationRequest
import org.chronobench.domain.EraSystem
import org.chronobench.domain.MeasurementRef
import org.chronobench.domain.SequenceConstraint
import org.chronobench.domain.SequenceEngine
import org.chronobench.domain.SequenceKind
import org.chronobench.events.CalibrationRunRecorded
import org.chronobench.events.CandidateRuled
import org.chronobench.events.ConflictDetected
import org.chronobench.events.CurveRegistered
import org.chronobench.events.CurveSuperseded
import org.chronobench.events.DecisionReversed
import org.chronobench.events.Event
import org.chronobench.events.ExclusionDecisionSubmitted
import org.chronobench.events.Identity
import org.chronobench.events.JudgmentRecorded
import org.chronobench.events.MeasurementSubmitted
import org.chronobench.events.ProjectCreated
import org.chronobench.events.ProjectState
import org.chronobench.events.ReservoirHypothesisSubmitted
import org.chronobench.events.SequenceConstraintSubmitted
import org.chronobench.events.SequenceEvaluationRecorded
import org.chronobench.events.SourceRef
import org.chronobench.events.SubmittedValue
import org.chronobench.events.foldEvents

class AppService(private val store: EventStore) {

    fun state(projectId: String): ProjectState =
        foldEvents(store.load(projectId), BundledCurves.all().associateBy { "${it.id}@${it.version}" })

    fun stateAt(projectId: String, upToSeqInclusive: Int): ProjectState {
        val all = store.load(projectId)
        return foldEvents(
            all.filter { it.seq <= upToSeqInclusive },
            BundledCurves.all().associateBy { "${it.id}@${it.version}" }
        )
    }

    fun createProject(projectId: String, name: String, eraSystem: EraSystem, source: SourceRef, seed: Long): ProjectCreated {
        store.assertProjectAbsent(projectId)
        return store.append(projectId, null) { id, seq, at ->
            ProjectCreated(id, projectId, seq, at, null, name, eraSystem, source, seed)
        }
    }

    fun registerCurve(projectId: String, curveId: String, version: String, causation: String? = null): CurveRegistered {
        val curve = BundledCurves.require(curveId, version)
        val state = state(projectId)
        if (state.curves.containsKey("${curve.id}@${curve.version}")) {
            return state.events.filterIsInstance<CurveRegistered>()
                .first { it.curveId == curveId && it.version == version }
        }
        return store.append(projectId, causation) { id, seq, at ->
            CurveRegistered(
                id, projectId, seq, at, causation,
                curve.id, curve.version, curve.fingerprint, curve.knots.size,
                curve.minCalBP, curve.maxCalBP,
                SourceRef("calibration-curve", curve.source, "${curve.displayName} [${curve.fingerprint}]"),
                curve.supersedes
            )
        }
    }

    fun supersedeCurve(projectId: String, newVersion: String, causation: String? = null): CurveSuperseded {
        val state = state(projectId)
        val currentId = state.preferredCurveId ?: error("no curve registered yet")
        val oldVersion = state.preferredCurveVersion!!
        registerCurve(projectId, currentId, newVersion, causation)
        return store.append(projectId, causation) { id, seq, at ->
            CurveSuperseded(id, projectId, seq, at, causation, currentId, oldVersion, currentId, newVersion)
        }
    }

    fun submitMeasurement(
        projectId: String,
        measurementId: String,
        parentMeasurementId: String?,
        labCode: String,
        material: String,
        ageRaw: String,
        sigmaRaw: String,
        source: SourceRef,
        note: String = "",
        causation: String? = null
    ): MeasurementSubmitted {
        val age = ageRaw.toDoubleOrNull() ?: throw IllegalArgumentException("invalid 14C age '$ageRaw' (expected number, years BP)")
        val sigma = sigmaRaw.toDoubleOrNull() ?: throw IllegalArgumentException("invalid sigma '$sigmaRaw' (expected number, 1-sigma years)")
        require(sigma > 0.0) { "measurement sigma must be > 0" }
        return store.append(projectId, causation) { id, seq, at ->
            MeasurementSubmitted(
                id, projectId, seq, at, causation,
                measurementId, parentMeasurementId, labCode, material,
                SubmittedValue(ageRaw, age, "14C years BP", "radiocarbon age (Libby half-life, BP=before 1950)"),
                SubmittedValue(sigmaRaw, sigma, "14C years BP (1 sigma)", "laboratory 1-sigma uncertainty"),
                source, note
            )
        }
    }

    fun submitReservoir(
        projectId: String,
        hypothesisId: String,
        parentHypothesisId: String?,
        label: String,
        deltaRRaw: String,
        deltaRSigmaRaw: String,
        source: SourceRef,
        causation: String? = null
    ): ReservoirHypothesisSubmitted {
        val d = deltaRRaw.toDoubleOrNull() ?: throw IllegalArgumentException("invalid reservoir dR '$deltaRRaw'")
        val ds = deltaRSigmaRaw.toDoubleOrNull() ?: throw IllegalArgumentException("invalid reservoir sigma '$deltaRSigmaRaw'")
        require(ds >= 0.0) { "reservoir sigma must be >= 0" }
        return store.append(projectId, causation) { id, seq, at ->
            ReservoirHypothesisSubmitted(
                id, projectId, seq, at, causation,
                hypothesisId, parentHypothesisId,
                SubmittedValue(deltaRRaw, d, "14C years BP", "marine reservoir correction DeltaR"),
                SubmittedValue(deltaRSigmaRaw, ds, "14C years BP (1 sigma)", "DeltaR uncertainty"),
                source, label
            )
        }
    }

    fun submitConstraint(
        projectId: String,
        constraintId: String,
        parentConstraintId: String?,
        label: String,
        orderedMeasurementIds: List<String>,
        kind: SequenceKind,
        minGapYears: Double,
        source: SourceRef,
        causation: String? = null
    ): SequenceConstraintSubmitted = store.append(projectId, causation) { id, seq, at ->
        SequenceConstraintSubmitted(
            id, projectId, seq, at, causation,
            constraintId, parentConstraintId, label, orderedMeasurementIds, kind, minGapYears, source
        )
    }

    fun submitExclusion(
        projectId: String,
        decisionId: String,
        targetMeasurementId: String,
        excluded: Boolean,
        reason: String,
        source: SourceRef,
        causation: String? = null
    ): ExclusionDecisionSubmitted = store.append(projectId, causation) { id, seq, at ->
        ExclusionDecisionSubmitted(
            id, projectId, seq, at, causation,
            decisionId, targetMeasurementId, excluded, reason, source
        )
    }

    fun runCalibration(
        projectId: String,
        measurementId: String,
        hypothesisId: String,
        scenarioTags: List<String>,
        causation: String? = null
    ): CalibrationRunRecorded {
        val s = state(projectId)
        val m = s.measurements.getValue(measurementId)
        val h = s.reservoirs.getValue(hypothesisId)
        val curve = s.preferredCurve() ?: error("register a curve before calibrating")
        val req = CalibrationRequest(
            measurement = MeasurementRef(m.measurementId, m.c14AgeBP, m.sigmaBP),
            curveId = curve.id,
            curveVersion = curve.version,
            curveFingerprint = curve.fingerprint,
            reservoir = h.hypothesis()
        )
        val result = CalibrationEngine.calibrate(req, curve)
        val runId = "run-" + Identity.sha256Short(
            "cal|${m.measurementId}|${h.hypothesisId}|${curve.fingerprint}|${result.parameterFingerprint.fingerprint}", 14
        )
        return store.append(projectId, causation) { id, seq, at ->
            CalibrationRunRecorded(
                id, projectId, seq, at, causation,
                runId, measurementId, hypothesisId, curve.id, curve.version, scenarioTags, result
            )
        }
    }

    fun evaluateSequence(
        projectId: String,
        constraintId: String,
        runIds: List<String>,
        causation: String? = null
    ): SequenceEvaluationRecorded {
        val s = state(projectId)
        val constraintRec = s.constraints.getValue(constraintId)
        val runs = runIds.map { s.runs.getValue(it) }
        val evaluation = SequenceEngine.evaluate(
            constraintRec.constraint,
            runs.map { it.result }
        )
        val evaluationId = "seqev-" + Identity.sha256Short(
            "seq|$constraintId|${runIds.joinToString(",")}|${evaluation.evidence.normalizationConstant}", 14
        )
        return store.append(projectId, causation) { id, seq, at ->
            SequenceEvaluationRecorded(id, projectId, seq, at, causation, evaluationId, constraintId, runIds, evaluation)
        }
    }

    fun ruleCandidate(
        projectId: String,
        candidateKind: String,
        candidateStableId: String,
        targetRunId: String?,
        targetIntervalId: String?,
        decision: String,
        reason: String,
        parameterRevision: Map<String, String> = emptyMap(),
        causation: String? = null
    ): CandidateRuled {
        require(decision in setOf("ACCEPT", "REJECT", "ADJUST")) { "decision must be ACCEPT/REJECT/ADJUST" }
        return store.append(projectId, causation) { id, seq, at ->
            val rulingId = "rul-" + Identity.sha256Short(
                "ruling|$candidateKind|$candidateStableId|$decision|$reason|$seq", 14
            )
            CandidateRuled(
                id, projectId, seq, at, causation,
                rulingId, candidateKind, candidateStableId, targetRunId, targetIntervalId,
                decision, reason, parameterRevision
            )
        }
    }

    /**
     * Record a conflicting human decision as a first-class, unresolved object (e.g. rejecting
     * an interval that a prior acceptance already covers). Nothing is silently overwritten.
     */
    fun recordConflict(
        projectId: String,
        betweenEventIds: List<String>,
        subject: String,
        note: String,
        causation: String? = null
    ): ConflictDetected = store.append(projectId, causation) { id, seq, at ->
        val conflictId = "conf-" + Identity.sha256Short("conflict|${betweenEventIds.joinToString()}|$subject|$seq", 14)
        ConflictDetected(id, projectId, seq, at, causation, conflictId, betweenEventIds, subject, "OPEN", note)
    }

    fun reverseDecision(projectId: String, reversedEventId: String, reason: String, causation: String? = null): DecisionReversed =
        store.append(projectId, causation) { id, seq, at ->
            val reversalId = "rev-" + Identity.sha256Short("reverse|$reversedEventId|$reason|$seq", 14)
            DecisionReversed(id, projectId, seq, at, causation, reversalId, reversedEventId, reason)
        }

    fun recordJudgment(
        projectId: String,
        code: String,
        subject: String,
        holds: Boolean,
        basis: String,
        evidenceRefs: List<String>,
        causation: String? = null
    ): JudgmentRecorded = store.append(projectId, causation) { id, seq, at ->
        val jid = "jdg-" + Identity.sha256Short("judgment|$code|$subject|$holds|$seq", 14)
        JudgmentRecorded(id, projectId, seq, at, causation, jid, code, subject, holds, basis, evidenceRefs)
    }

    fun loadEvent(projectId: String, eventId: String): Event? = store.load(projectId).firstOrNull { it.eventId == eventId }
}
