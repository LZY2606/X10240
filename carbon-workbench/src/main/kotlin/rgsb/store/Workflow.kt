package rgsb.store

import rgsb.domain.CalibrationEngine
import rgsb.domain.Hashing
import rgsb.domain.SequenceChecker
import rgsb.ingest.CasePackValidator
import rgsb.model.CalibrationResult
import rgsb.model.CasePack
import rgsb.model.DecisionOutcome
import rgsb.model.DecisionRecord
import rgsb.model.EventPayload
import rgsb.model.ImportDiagnostic
import rgsb.model.ImportResult
import rgsb.model.IntervalEvidence
import rgsb.model.ProjectBundle
import rgsb.model.ViewSnapshot
import kotlinx.serialization.json.JsonElement

/**
 * Application workflow. All mutations go through the append-only event store; every
 * derived result is connected to direct sources. A local source change only invalidates
 * results that depend on it (then keeps the stale rows readable).
 */
class Workflow(
    val store: EventStore,
    val engine: CalibrationEngine = CalibrationEngine(seed = 0L),
    private val clock: VClock = SystemClock,
) {
    private val checker = SequenceChecker { clock.now().toString() }

    // ---------- import ----------

    fun importCasePack(packText: String, name: String = "imported-project"): ImportResult {
        val (pack, diagnostics) = CasePackValidator.parsePack(packText)
        val hasErrors = diagnostics.any { it.severity == "ERROR" }
        if (pack == null || hasErrors) {
            return ImportResult(false, store.meta("projectId") ?: "", diagnostics, emptyList())
        }
        val created = mutableListOf<String>()
        store.meta("projectId") ?: run {
            val ev = store.append(EventPayload.ProjectCreated(pack.curveIdOrFirst(), name, engine.seed))
            store.setMeta("projectId", ev.eventId)
            created += ev.eventId
        }
        val era = store.append(EventPayload.EraDefined(pack.era)); created += era.eventId
        for (curve in pack.curves) {
            created += store.append(EventPayload.CurveInstalled(curve)).eventId
        }
        for (m in pack.measurements) created += store.append(EventPayload.MeasurementSubmitted(m)).eventId
        for (o in pack.offsets) created += store.append(EventPayload.OffsetSubmitted(o)).eventId
        for (s in pack.sequences) created += store.append(EventPayload.SequenceSubmitted(s)).eventId
        for (e in pack.exclusions) created += store.append(EventPayload.ExclusionSubmitted(e)).eventId
        store.append(EventPayload.Annotation(
            "baseline imported from '${pack.packName}' ${pack.packVersion}: measurements, curve version(s), offset distributions, era, sequence and exclusions each carry their own source"
        ))
        return ImportResult(true, store.meta("projectId") ?: "", diagnostics, created)
    }

    private fun CasePack.curveIdOrFirst(): String = curves.firstOrNull()?.id ?: "unknown-project"

    // ---------- views ----------

    fun currentView(branch: String = EventStore.MAIN): ViewSnapshot {
        val tip = store.tip(branch) ?: error("branch $branch has no events")
        return viewAt(tip, branch)
    }

    fun viewAt(eventId: String, branch: String = EventStore.MAIN): ViewSnapshot {
        val events = store.eventsUntil(eventId)
        return Reducer.snapshot(events, store.branches().associate { it.branch to it.tipEventId }, eventId, branch)
    }

    fun headEventId(branch: String = EventStore.MAIN): String? = store.tip(branch)

    // ---------- calibration ----------

    /**
     * Runs a calibration. If an identical parameter fingerprint already exists (same
     * measurement rev, curve version, offset rev and algorithm version), the existing
     * stable result id is returned: fixed versions reproduce byte-identical output.
     */
    fun calibrate(
        measurementId: String,
        curveId: String,
        offsetId: String? = null,
        branch: String = EventStore.MAIN,
    ): CalibrationResult {
        val view = currentView(branch)
        val measurement = view.measurements.first { it.id == measurementId }
        val curve = view.curves.first { it.id == curveId }
        val offset = offsetId?.let { id -> view.offsets.first { it.id == id } }
        val resolved = engine.resolveMeasurement(measurement)
        val fingerprint = engine.parameterFingerprint(measurement, resolved, curve, offset)
        val existing = view.results.firstOrNull { it.parameterFingerprint == fingerprint && it.status != "STALE" }
        if (existing != null) return existing

        val status = if (resolved.unconverted.isNotEmpty()) "UNKNOWN" else "CURRENT"
        val inputs = CalibrationEngine.CalibrationInputs(
            labAge = resolved.age,
            labSigma = resolved.sigma,
            components = engine.components(offset),
        )
        val posterior = if (status == "CURRENT") engine.posterior(curve, inputs)
        else CalibrationEngine.Posterior(emptyList(), emptyList(), emptyList(), 0.0, 0, false, 0..0)

        val directSources = buildList {
            add(measurement.age.source); add(measurement.sigma.source)
            add(curve.source); offset?.source?.let { add(it) }
        }
        val baseId = "cal-${measurementId}@v${measurement.rev}-${curveId}@${curve.version}" +
            (offset?.let { "-${it.id}@v${it.rev}" } ?: "-nooffset")
        val resultId = "$baseId-${Hashing.sha256Hex(fingerprint).take(10)}"
        val intervals = buildIntervals(resultId, posterior)
        val result = CalibrationResult(
            resultId = resultId,
            measurementId = measurementId,
            curveId = curveId,
            curveVersion = curve.version,
            offsetId = offsetId,
            algorithmVersion = engine.algorithmVersion,
            parameterFingerprint = fingerprint,
            directSources = directSources,
            gridStepYears = engine.gridStepYears,
            grid = posterior.cells,
            intervals = intervals,
            multiModal = posterior.modeCount > 1,
            modeCount = posterior.modeCount,
            normalizationConstant = posterior.normalizationConstant,
            crossesBceCe = posterior.crossesBceCe,
            status = status,
            unknownReason = if (resolved.unconverted.isNotEmpty()) "unconverted source values: ${resolved.unconverted.joinToString()}" else null,
            computationEventId = "",
        )
        val produced = store.append(EventPayload.ComputationProduced(result), branch = branch)
        return result.copy(computationEventId = produced.eventId)
    }

    private fun buildIntervals(resultId: String, posterior: CalibrationEngine.Posterior): List<IntervalEvidence> {
        val out = mutableListOf<IntervalEvidence>()
        for ((level, set) in listOf(0.68 to posterior.intervals68, 0.95 to posterior.intervals95)) {
            set.forEachIndexed { index, (range, prob) ->
                val cells = posterior.cells.filter { it.year in range }
                out += IntervalEvidence(
                    intervalId = CalibrationEngine.intervalId(resultId, level, index),
                    yearFrom = range.first,
                    yearTo = range.last,
                    probability = prob,
                    hpdLevel = level,
                    sumUnnormalizedWeights = cells.sumOf { it.unnormalized },
                    gridCells = cells.size,
                    integrandFingerprint = Hashing.sha256Hex(
                        cells.joinToString(";") { "${it.year}:${"%.12g".format(it.unnormalized)}" }
                    ),
                )
            }
        }
        return out
    }

    fun runSequenceChecks(branch: String = EventStore.MAIN) {
        val view = currentView(branch)
        val verdicts = checker.check(view.sequences, view.results, view.offsets.map { it.id })
        store.append(EventPayload.SequenceCheckProduced(verdicts), branch = branch)
    }

    // ---------- invalidation ----------

    /**
     * Invalidate only results that directly depend on a changed source record. The stale
     * CalibrationResult rows remain in the log (marked STALE) and can still be opened.
     */
    fun invalidateDependingOn(sourceRecordId: String, branch: String = EventStore.MAIN): List<String> {
        val view = currentView(branch)
        val affected = view.results.filter { r ->
            r.status == "CURRENT" && r.resultId.contains(sourceRecordId)
        }.map { it.resultId }
        if (affected.isNotEmpty()) {
            val cause = store.append(EventPayload.Annotation("source record $sourceRecordId revised"), branch = branch)
            store.append(EventPayload.MarkStale(affected, cause.eventId), branch = branch)
        }
        return affected
    }

    // ---------- decisions ----------

    fun decide(
        targetResultId: String,
        outcome: DecisionOutcome,
        note: String,
        branch: String = EventStore.MAIN,
    ): DecisionRecord {
        val view = currentView(branch)
        require(view.results.any { it.resultId == targetResultId }) { "unknown result $targetResultId" }
        val conflicts = mutableListOf<String>()
        for (d in view.decisions.filter { it.supersededBy == null }) {
            if (d.targetResultId == targetResultId) {
                val conflicting = when {
                    d.outcome is DecisionOutcome.Accepted && outcome is DecisionOutcome.Rejected -> true
                    d.outcome is DecisionOutcome.Rejected && outcome is DecisionOutcome.Accepted -> true
                    else -> false
                }
                if (conflicting) conflicts += d.decisionId
            }
        }
        val decisionId = "dec-${Hashing.sha256Hex(targetResultId + outcome.toString() + note + clock.now()).take(12)}"
        val record = DecisionRecord(
            decisionId = decisionId,
            eventId = "",
            targetResultId = targetResultId,
            outcome = outcome,
            note = note,
            conflictsWith = conflicts,
        )
        val made = store.append(EventPayload.DecisionMade(record), branch = branch)
        return record.copy(eventId = made.eventId)
    }

    fun revokeDecision(decisionId: String, reason: String, branch: String = EventStore.MAIN) {
        val view = currentView(branch)
        require(view.decisions.any { it.decisionId == decisionId }) { "unknown decision $decisionId" }
        store.append(EventPayload.DecisionRevoked(decisionId, reason), branch = branch)
    }

    // ---------- branching / history ----------

    fun branchAt(eventId: String, name: String) = store.createBranch(name, eventId)

    fun exportBundle(name: String = "export"): ProjectBundle {
        val seed = currentView().let { 0L }
        return store.exportBundle(name, seed)
    }

    fun importBundle(bundle: ProjectBundle) = store.replaceWith(bundle)

    fun diagnosticsFor(packText: String): List<ImportDiagnostic> = CasePackValidator.parsePack(packText).second
}
