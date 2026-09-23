package gsb.store

import gsb.calib.Calibrator
import gsb.case.RcBench
import gsb.model.*
import gsb.seq.SequenceChecker
import gsb.util.Ids

class ProjectService(
    private val store: EventStore,
    private val clock: VirtualClock,
    val projectId: String,
    private val projectName: String,
    private val actor: String = "reviewer"
) {
    private var seqCounter = store.nextSeq()

    private class Ticket(val eventId: String, val seq: Long, val time: String)
    private fun ticket(): Ticket = Ticket(nextId("ev"), seqCounter, clock.nowIso())
    private fun <E : DomainEvent> emit(t: Ticket, ev: E): E {
        check(ev.eventId == t.eventId && ev.seq == t.seq && ev.virtualTime == t.time)
        store.append(ev); seqCounter++; return ev
    }

    fun state(atTip: Boolean = true): ProjectState = Fold.fold(store.allEvents(), atTip)

    /** Replay view at a specific event: restores dependency versions + visible
     *  conclusions of that moment, never the newest recomputation. */
    fun stateAtEvent(eventId: String): ProjectState {
        val all = store.allEvents()
        val cut = all.indexOfFirst { it.eventId == eventId }
        require(cut >= 0) { "event $eventId not found" }
        return Fold.fold(all.subList(0, cut + 1), atTip = false)
    }

    fun history(): List<DomainEvent> = store.allEvents()

    /** Import a parsed .rcbench batch. Every fragment carries its own SourceRef. */
    fun importParsed(p: RcBench.Parsed): List<String> {
        val ids = mutableListOf<String>()
        for (c in p.curves) {
            val id = Ids.sid("curve", "${c.family}|${c.version}")
            val t = ticket()
            emit(t, CurveRegistered(t.eventId, t.seq, t.time, actor,
                CalibrationCurve(id, c.version, null, t.eventId,
                    c.anchor, c.step, c.c14, c.sig, c.ref)))
            ids += id
        }
        for (m in p.measurements) {
            val id = Ids.sid("meas", "${m.code}|${m.age.raw}|${m.sigma.raw}")
            val t = ticket()
            emit(t, MeasurementSubmitted(t.eventId, t.seq, t.time, actor,
                Measurement(id, m.code, m.context, m.age, m.sigma, m.deltaR,
                    m.curveVersion, m.era, null, null, m.ref)))
            ids += id
        }
        for (o in p.offsets) {
            val mid = state().currentMeasurementByCode[o.code]
                ?: throw IllegalArgumentException("OFFSET references unknown measurement ${o.code}")
            val id = Ids.sid("offset", "$mid|${o.name}|${o.scenarios}")
            val t = ticket()
            emit(t, OffsetSubmitted(t.eventId, t.seq, t.time, actor,
                OffsetHypothesis(id, mid, o.name,
                    o.scenarios.map { OffsetScenario(it.first, it.second, it.third) },
                    null, o.ref)))
            ids += id
        }
        for (r in p.orders) {
            val id = Ids.sid("order", "${r.younger}|${r.older}|${r.why}")
            val t = ticket()
            emit(t, ConstraintSubmitted(t.eventId, t.seq, t.time, actor,
                SequenceConstraint(id, r.younger, r.older, r.why, r.ref)))
            ids += id
        }
        for (x in p.excludes) {
            val id = Ids.sid("exclude", "${x.code}|${x.reason}")
            val t = ticket()
            emit(t, ExclusionRecorded(t.eventId, t.seq, t.time, actor,
                ExclusionDecision(id, x.code, ExcludeStatus.EXCLUDED, x.reason, null, x.ref)))
            ids += id
        }
        return ids
    }

    /** A revision is a new immutable version linked to its parent; the original
     *  submitted value is never overwritten. */
    fun reviseMeasurement(
        code: String, age: Quantity, sigma: Quantity, deltaR: Quantity?,
        @Suppress("UNUSED_PARAMETER") reason: String, source: SourceRef
    ): String {
        val s = state()
        val parentId = s.currentMeasurementByCode[code]
            ?: throw IllegalArgumentException("unknown measurement $code")
        val parent = s.measurements[parentId]!!
        val id = Ids.sid("meas", "$code|${age.raw}|${sigma.raw}|rev|$parentId")
        val t = ticket()
        emit(t, MeasurementSubmitted(t.eventId, t.seq, t.time, actor,
            Measurement(id, code, parent.context, age, sigma, deltaR,
                parent.curveVersion, parent.eraSystem, parentId, null, source)))
        return id
    }

    fun addOffsetHypothesis(code: String, name: String, scenarios: List<OffsetScenario>,
                            source: SourceRef, parentOffsetId: String? = null): String {
        val s = state()
        val mid = s.currentMeasurementByCode[code]
            ?: throw IllegalArgumentException("unknown measurement $code")
        val id = Ids.sid("offset",
            "$mid|$name|${scenarios.joinToString("/") { "${it.mean}/${it.sigma}/${it.weight}" }}|${s.offsets.size}")
        val t = ticket()
        emit(t, OffsetSubmitted(t.eventId, t.seq, t.time, actor,
            OffsetHypothesis(id, mid, name, scenarios, parentOffsetId, source)))
        return id
    }

    fun registerCurve(c: CalibrationCurve) {
        val t = ticket()
        emit(t, CurveRegistered(t.eventId, t.seq, t.time, actor, c))
    }

    fun computeCalibration(code: String, offsetName: String, curveVersion: String? = null): CalibrationResult {
        val s = state()
        val m = s.measurements[s.currentMeasurementByCode[code]]!!
        require(m.c14Age.value != null && m.c14Sigma.value != null) {
            "measurement $code has unconvertible quantities: ${m.c14Age.diagnostics + m.c14Sigma.diagnostics}"
        }
        val hyp = s.offsets.values.last { it.measurementId == m.id && it.name == offsetName }
        val curve = pickCurve(s, m, curveVersion)
        val p = Calibrator.posterior(m, curve, hyp)
        val hpds = Calibrator.hpdIntervals(p)
        val fp = Calibrator.fingerprint(m, curve, hyp)
        val resultId = Ids.sid("calib", fp)
        val t = ticket()
        val result = CalibrationResult(
            id = resultId, measurementId = m.id, measurementCode = code,
            algorithmVersion = Calibrator.ALGORITHM_VERSION,
            curveId = curve.id, curveVersion = curve.version,
            offsetHypothesisId = hyp.id,
            gridAnchorBP = p.gridBP.first(), gridStepBP = Calibrator.GRID_STEP_BP,
            posterior = p.density, hpds = hpds,
            multiModal = Calibrator.isMultiModal(p),
            status = ResultStatus.CURRENT, paramFingerprint = fp,
            directInputs = listOf(m.id, curve.id, hyp.id),
            producedByEvent = t.eventId, virtualTime = t.time
        )
        val evidence = IntervalEvidence(
            resultId = resultId, intervalIndex = -1,
            fromCalBP = p.gridBP.first(), toCalBP = p.gridBP.last(),
            probability = 1.0,
            quadrature = "trapezoidal@grid=${Calibrator.GRID_STEP_BP}|fp=$fp",
            cells = p.density.size
        )
        emit(t, CalibrationComputed(t.eventId, t.seq, t.time, actor, result, evidence))
        return result
    }

    fun analyzeSequence(constraintId: String): SequenceAnalysis {
        val s = state()
        val c = s.constraints[constraintId]!!
        val younger = s.resultFor(c.youngerCode)
            ?: throw IllegalStateException("no current calibration for ${c.youngerCode}")
        val older = s.resultFor(c.olderCode)
            ?: throw IllegalStateException("no current calibration for ${c.olderCode}")
        val t = ticket()
        val a = SequenceChecker.analyze(c, younger, older, t.eventId, t.time)
        emit(t, SequenceAnalyzed(t.eventId, t.seq, t.time, actor, a))
        return a
    }

    fun recordReview(
        verdict: Verdict, targetResultId: String? = null, targetAnalysisId: String? = null,
        intervalIndex: Int? = null, chosenParameterSet: String? = null, note: String,
        source: SourceRef, parentDecisionId: String? = null
    ): ReviewDecision {
        val id = Ids.sid("review",
            "$verdict|$targetResultId|$targetAnalysisId|$intervalIndex|$chosenParameterSet|$note|${history().size}")
        val d = ReviewDecision(id, verdict, targetResultId, targetAnalysisId, intervalIndex,
            chosenParameterSet, note, null, parentDecisionId, source)
        val t = ticket()
        emit(t, ReviewRecorded(t.eventId, t.seq, t.time, actor, d))
        return d
    }

    fun undoDecision(decisionId: String) {
        val reviewEvent = history().find { it is ReviewRecorded && it.decision.id == decisionId }
            ?: throw IllegalArgumentException("decision $decisionId not found")
        val t = ticket()
        emit(t, DecisionUndone(t.eventId, t.seq, t.time, actor, decisionId, reviewEvent.eventId))
    }

    private fun pickCurve(s: ProjectState, m: Measurement, curveVersion: String?): CalibrationCurve {
        val want = curveVersion ?: m.curveVersion
        return s.curves.values.firstOrNull { it.version == want }
            ?: throw IllegalArgumentException("curve version '$want' not registered")
    }

    private fun nextId(prefix: String) =
        Ids.sid(prefix, "${store.eventCount()}|${clock.peekIso()}|$actor|$seqCounter")
}
