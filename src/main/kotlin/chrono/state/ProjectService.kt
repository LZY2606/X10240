package chrono.state

import chrono.calib.CalibrationEngine
import chrono.domain.AdjustmentProposal
import chrono.domain.CalibrationCurve
import chrono.domain.CalibrationRun
import chrono.domain.Exclusion
import chrono.domain.GridPoint
import chrono.domain.Hypothesis
import chrono.domain.Measurement
import chrono.domain.OffsetSpec
import chrono.domain.Project
import chrono.domain.Reservoir
import chrono.domain.SequenceConstraint
import chrono.domain.SequenceEvaluation
import chrono.domain.Source
import chrono.domain.canonicalJson
import chrono.domain.decodeJson
import chrono.domain.toJson
import chrono.storage.EventStore
import chrono.storage.ProjectArchive
import java.time.Instant

/** Raised for well-formed but semantically invalid import/command input. */
class ChronoException(message: String) : RuntimeException(message)

/**
 * The application state machine. Every user intent:
 *  1. appends one or more sourced events,
 *  2. deterministically derives only the affected runs/evaluations,
 *  3. appends those derived artifacts as system events in the SAME batch.
 *
 * Reopening a project replays events; derived ids and conclusions are byte-identical.
 */
class ProjectService(
    private val store: EventStore,
    private val clock: Clock = SystemClock
) {
    // ---------------- queries ----------------

    fun state(projectId: String): ProjectState = Reducer.fold(store.loadProject(projectId))

    fun stateAt(projectId: String, uptoSeq: Long): ProjectState =
        Reducer.fold(store.loadProjectAt(projectId, uptoSeq))

    fun events(projectId: String): List<Event> = store.loadProject(projectId)

    fun projectAtEvent(projectId: String, eventId: String): Pair<Long, ProjectState> {
        val all = store.loadProject(projectId)
        val target = all.firstOrNull { it.eventId == eventId }
            ?: throw ChronoException("unknown event $eventId")
        return target.seq to stateAt(projectId, target.seq)
    }

    fun gridOf(run: CalibrationRun): CalibrationEngine.GridResult {
        val ages = IntArray(run.grid.size)
        val dens = DoubleArray(run.grid.size)
        for ((i, gp) in run.grid.withIndex()) {
            ages[i] = gp.calBP
            dens[i] = gp.density
        }
        return CalibrationEngine.GridResult(
            ages, dens, dens,
            BooleanArray(run.grid.size) { true },
            run.boundaryLowTruncated, run.boundaryHighTruncated
        )
    }

    // ---------------- project / curve / era ----------------

    fun createProject(projectId: String, name: String, eraSystem: String, eraSource: Source): List<Event> {
        if (store.listProjects().contains(projectId)) throw ChronoException("project exists: $projectId")
        val at = clock.now()
        val e = enveloppe(
            projectId, EventIds.project(projectId), "project.created", "reviewer",
            "创建项目；年代系统=${eraSystem}",
            ProjectCreated(name, eraSystem, eraSource, at)
        )
        return commit(listOf(e))
    }

    fun registerCurve(
        projectId: String,
        id: String,
        label: String,
        version: String,
        points: List<CalibrationCurve.CurvePoint>,
        source: Source,
        supersedes: String? = null,
        activate: Boolean = false
    ): List<Event> {
        requireCurveValid(id, version, points)
        val events = mutableListOf<Event>()
        events += enveloppe(
            projectId, EventIds.curve(id, version), "curve.registered",
            source.origin, source.citation,
            CurveRegistered(id, label, version, supersedes, points, source)
        )
        if (activate) {
            events += enveloppe(
                projectId, EventIds.activateCurve(id, version), "curve.activated",
                source.origin, "曲线升版：$label $version",
                CurveActivated(id, version, "曲线升版", source)
            )
        }
        return commit(events)
    }

    private fun requireCurveValid(id: String, version: String, points: List<CalibrationCurve.CurvePoint>) {
        if (points.size < 2) throw ChronoException("curve $id/$version: 至少需要 2 个点")
        if (points.zipWithNext().any { (a, b) -> a.calBP >= b.calBP })
            throw ChronoException("curve $id/$version: cal BP 必须严格递增")
        if (points.any { it.sigmaC14 < 0 })
            throw ChronoException("curve $id/$version: sigma 不能为负")
    }

    fun activateCurve(projectId: String, curveId: String, version: String, reason: String, source: Source): List<Event> {
        val s = state(projectId)
        val curve = s.curves[curveId] ?: throw ChronoException("unknown curve $curveId")
        if (curve.version != version) throw ChronoException("curve version mismatch $version vs ${curve.version}")
        val e = enveloppe(
            projectId, EventIds.activateCurve(curveId, version), "curve.activated",
            source.origin, reason, CurveActivated(curveId, version, reason, source)
        )
        return commit(listOf(e))
    }

    fun changeEra(projectId: String, eraSystem: String, source: Source): List<Event> {
        val s = state(projectId)
        val n = s.events0() + 1
        val e = enveloppe(
            projectId, EventIds.era(projectId, n), "era.changed",
            source.origin, "年代系统切换为 $eraSystem",
            EraSystemChanged(eraSystem, source)
        )
        return commit(listOf(e))
    }

    // ---------------- measurements & reservoirs & hypotheses ----------------

    fun submitMeasurement(
        projectId: String,
        id: String,
        sampleCode: String,
        labCode: String,
        rawAge: String,
        rawSigma: String,
        material: String,
        source: Source,
        parentId: String? = null
    ): List<Event> {
        val s = state(projectId)
        if (s.measurements.any { it.id == id && parentId == null })
            throw ChronoException("measurement id already used: $id（原始提交不可覆盖，请用修订版本）")
        if (parentId != null && s.measurement(parentId) == null)
            throw ChronoException("parent measurement not found: $parentId")

        val parsed = parseAge(rawAge, rawSigma)
        val version = if (parentId == null) 1
        else s.measurements.filter { it.id == parentId }.size + 1
        val payload = MeasurementSubmitted(
            id = if (parentId == null) id else parentId,
            version = version,
            sampleCode = sampleCode,
            labCode = labCode,
            rawAge = rawAge,
            rawSigma = rawSigma,
            ageBP = parsed.ageBP,
            sigmaBP = parsed.sigmaBP,
            material = material,
            unit = parsed.unit,
            source = source,
            parentId = parentId,
            unconvertible = parsed.unconvertible,
            diagnostic = parsed.diagnostic
        )
        val eventId = EventIds.measurement(
            if (parentId == null) id else parentId, version
        )
        val events = mutableListOf<Event>()
        events += enveloppe(
            projectId, eventId, "measurement.submitted",
            source.origin, source.citation, payload
        )

        // Every measurement starts with a zero-offset hypothesis; reservoir
        // hypotheses are attached separately so multiple offset assumptions coexist.
        val hypId = "hyp-${if (parentId == null) id else parentId}-v$version-none"
        events += enveloppe(
            projectId, EventIds.hypothesis(hypId), "hypothesis.created",
            ACTOR_SYSTEM, "默认零偏移假设",
            HypothesisCreated(hypId, if (parentId == null) id else parentId, null)
        )
        return commit(events)
    }

    data class ParsedAge(
        val ageBP: Double, val sigmaBP: Double, val unit: String,
        val unconvertible: Boolean = false, val diagnostic: String? = null
    )

    /**
     * Imports retain the original strings; only this derived parse normalizes.
     * Values that cannot be converted are kept in place with a diagnostic rather
     * than dropped for plotting.
     */
    fun parseAge(rawAge: String, rawSigma: String): ParsedAge {
        val ageMatch = Regex("""^\s*([0-9]+(?:\.[0-9]+)?)\s*(14C\s*yr\s*BP|BP|bp)?\s*$""")
            .find(rawAge)
        val sigMatch = Regex("""^\s*(?:±|\+/-|\+-)?\s*([0-9]+(?:\.[0-9]+)?)\s*(?:14C\s*yr\s*BP|BP|bp)?\s*$""")
            .find(rawSigma)
        if (ageMatch == null || sigMatch == null) {
            return ParsedAge(
                ageBP = Double.NaN, sigmaBP = Double.NaN,
                unit = chrono.domain.Units.C14_YEARS_BP,
                unconvertible = true,
                diagnostic = "无法把原表示 ($rawAge, $rawSigma) 转换为 14C yr BP；保留原值，不参与绘图"
            )
        }
        val age = ageMatch.groupValues[1].toDouble()
        val sigma = sigMatch.groupValues[1].toDouble()
        if (sigma < 0) throw ChronoException("标准差不能为负：$rawSigma")
        return ParsedAge(age, sigma, chrono.domain.Units.C14_YEARS_BP)
    }

    fun reviseMeasurement(
        projectId: String,
        parentId: String,
        sampleCode: String,
        labCode: String,
        rawAge: String,
        rawSigma: String,
        material: String,
        source: Source
    ) = submitMeasurement(projectId, parentId, sampleCode, labCode, rawAge, rawSigma, material, source, parentId)

    fun submitReservoir(
        projectId: String,
        id: String,
        label: String,
        offset: OffsetSpec,
        source: Source,
        parentId: String? = null
    ): List<Event> {
        val s = state(projectId)
        if (parentId == null && s.reservoirs.any { it.id == id })
            throw ChronoException("reservoir id used: $id（修订请提供父版本）")
        val version = if (parentId == null) 1
        else s.reservoirs.filter { it.id == parentId }.size + 1
        val payload = ReservoirSubmitted(
            id = if (parentId == null) id else parentId,
            version = version,
            label = label,
            offset = offset,
            source = source,
            parentId = parentId
        )
        return commit(listOf(
            enveloppe(
                projectId,
                EventIds.reservoir(if (parentId == null) id else parentId, version),
                "reservoir.submitted", source.origin, source.citation, payload
            )
        ))
    }

    fun attachHypothesis(projectId: String, measurementId: String, reservoirId: String): List<Event> {
        val s = state(projectId)
        val m = s.measurement(measurementId) ?: throw ChronoException("unknown measurement $measurementId")
        s.reservoir(reservoirId) ?: throw ChronoException("unknown reservoir $reservoirId")
        val version = s.measurements.count { it.id == measurementId }
        val hid = "hyp-$measurementId-v$version-$reservoirId"
        if (s.hypotheses.any { it.id == hid })
            throw ChronoException("该测量+偏移假设已存在：$hid（并列假设不可重复）")
        return commit(listOf(
            enveloppe(
                projectId, EventIds.hypothesis(hid), "hypothesis.created",
                ACTOR_SYSTEM, "并列储库偏移假设：$reservoirId",
                HypothesisCreated(hid, measurementId, reservoirId)
            )
        ))
    }

    // ---------------- constraints, exclusions, decisions ----------------

    fun submitConstraint(
        projectId: String,
        id: String,
        olderHypothesisId: String?,
        youngerHypothesisId: String?,
        coevalA: String?,
        coevalB: String?,
        note: String,
        source: Source
    ): List<Event> {
        val s = state(projectId)
        if (s.constraints.any { it.id == id }) throw ChronoException("constraint exists: $id")
        val isCoeval = coevalA != null && coevalB != null
        if (isCoeval) {
            s.hypothesis(coevalA) ?: throw ChronoException("unknown hypothesis $coevalA")
            s.hypothesis(coevalB) ?: throw ChronoException("unknown hypothesis $coevalB")
        } else {
            s.hypothesis(olderHypothesisId) ?: throw ChronoException("unknown hypothesis $olderHypothesisId")
            s.hypothesis(youngerHypothesisId) ?: throw ChronoException("unknown hypothesis $youngerHypothesisId")
        }
        return commit(listOf(
            enveloppe(
                projectId, EventIds.constraint(id), "constraint.submitted",
                source.origin, note,
                ConstraintSubmitted(
                    id, olderHypothesisId, youngerHypothesisId, coevalA, coevalB, note, source
                )
            )
        ))
    }

    fun recordExclusion(
        projectId: String, id: String, targetType: String, targetId: String,
        reason: String, source: Source
    ): List<Event> {
        if (targetType !in setOf("measurement", "hypothesis", "constraint"))
            throw ChronoException("bad exclusion target $targetType")
        return commit(listOf(
            enveloppe(
                projectId, EventIds.exclusion(id), "exclusion.recorded",
                source.origin, reason,
                ExclusionRecorded(id, targetType, targetId, reason, source)
            )
        ))
    }

    fun revokeExclusion(projectId: String, id: String, reviewer: String): List<Event> {
        val s = state(projectId)
        s.exclusions.firstOrNull { it.id == id && !it.revoked }
            ?: throw ChronoException("没有可撤销的排除决定：$id")
        return commit(listOf(
            enveloppe(
                projectId, "evt-excl-revoke-$id-${s.exclusions.count { it.id == id }}",
                "exclusion.revoked", reviewer, "撤销排除 $id",
                ExclusionRevoked(id, reviewer)
            )
        ))
    }

    fun decide(
        projectId: String,
        intervalId: String,
        runId: String,
        verdict: String,
        rationale: String,
        reviewer: String
    ): List<Event> {
        if (verdict !in setOf("accept", "reject", "adjust"))
            throw ChronoException("verdict 必须是 accept/reject/adjust")
        val s = state(projectId)
        val run = s.runs.firstOrNull { it.runId == runId }
            ?: throw ChronoException("unknown run $runId")
        run.intervals.firstOrNull { it.intervalId == intervalId }
            ?: throw ChronoException("unknown interval $intervalId")

        val n = s.decisions.size + 1
        val id = "dec-${n.toString().padStart(3, '0')}-$intervalId"
        val events = mutableListOf<Event>()
        events += enveloppe(
            projectId, EventIds.decision(id), "decision.recorded",
            reviewer, rationale,
            DecisionRecorded(id, intervalId, runId, verdict, rationale, reviewer)
        )
        // A second decision on the same interval supersedes the prior live one
        // instead of overwriting it. Conflicting decisions are both retained.
        s.liveDecisions().filter { it.intervalId == intervalId }.forEach { old ->
            events += enveloppe(
                projectId, "evt-sup-$id-${old.id}", "decision.superseded",
                reviewer, "新裁定取代旧裁定（保留冲突历史）",
                DecisionSuperseded(id, old.id)
            )
        }
        return commit(events)
    }

    fun revokeDecision(projectId: String, decisionId: String, reason: String, reviewer: String): List<Event> {
        val s = state(projectId)
        s.decisions.firstOrNull { it.id == decisionId && !it.revoked }
            ?: throw ChronoException("没有可撤销的裁定：$decisionId")
        val n = s.decisions.filter { it.id == decisionId }.size + 1
        return commit(listOf(
            enveloppe(
                projectId, EventIds.revoke(decisionId, n), "decision.revoked",
                reviewer, reason, DecisionRevoked(decisionId, reason, reviewer)
            )
        ))
    }

    fun note(projectId: String, text: String, reviewer: String): List<Event> {
        val n = state(projectId).notes.size + 1
        val id = "note-${n.toString().padStart(3, '0')}"
        return commit(listOf(
            enveloppe(
                projectId, EventIds.note(id), "review.note", reviewer, text,
                ReviewNote(id, text, reviewer, clock.now())
            )
        ))
    }

    // ---------------- commit + deterministic derivation ----------------

    private fun enveloppe(
        projectId: String,
        eventId: String,
        type: String,
        actor: String,
        basis: String?,
        payload: Any
    ): Event {
        val state0 = store.countProject(projectId)
        return Event(
            seq = state0 + 1,
            eventId = eventId,
            projectId = projectId,
            type = type,
            actor = actor,
            at = clock.now(),
            basis = basis,
            payloadJson = payload.toJson()
        )
    }

    /**
     * Append user events, then append ONLY the derived artifacts affected by them.
     * Runs are content-addressed: unchanged inputs produce the same run id and are
     * skipped, so a local change invalidates only dependent results.
     */
    private fun commit(userEvents: List<Event>): List<Event> {
        if (userEvents.isEmpty()) return emptyList()
        val projectId = userEvents.first().projectId
        val before = state(projectId)
        val provisional = Reducer.fold(store.loadProject(projectId) + userEvents)

        val derived = mutableListOf<Event>()
        var seq0 = store.countProject(projectId) + userEvents.size

        fun emit(type: String, eventId: String, actor: String, basis: String?, payload: Any) {
            seq0 += 1
            derived += Event(
                seq = seq0, eventId = eventId, projectId = projectId,
                type = type, actor = actor, at = clock.now(),
                basis = basis, payloadJson = payload.toJson()
            )
        }

        val activeCurve = provisional.activeCurveId?.let { provisional.curves[it] }

        // 1) runs for every (hypothesis, registered curve version), only when new
        for (hyp in provisional.hypotheses) {
            val m = provisional.measurement(hyp.measurementId) ?: continue
            val r = provisional.reservoir(hyp.reservoirId)
            val excluded = provisional.activeExclusion("measurement", m.id) != null ||
                provisional.activeExclusion("hypothesis", hyp.id) != null
            for (curve in provisional.curves.values.sortedBy { it.version }) {
                val fp = Derivation.parameterFingerprint(m, r, curve)
                val rid = Derivation.runId(hyp.id, curve.id, fp)
                if (provisional.runs.any { it.runId == rid }) continue
                val sources = buildList {
                    add(m.source.citation)
                    if (r != null) add(r.source.citation)
                    add(curve.source.citation)
                    add("算法版本 ${chrono.state.ALGO}")
                }
                val run = Derivation.buildRun(
                    hyp, m, r, curve, excluded, createdAtEvent = seq0 + 1, sources = sources
                )
                emit(
                    "run.recorded", EventIds.run(rid), ACTOR_SYSTEM,
                    "派生：校准 ${m.sampleCode} / 曲线 ${curve.version} / ${r?.label ?: "零偏移"}",
                    RunRecorded(run)
                )
            }
        }

        // Re-evaluate after run set changed (fold user + already-emitted runs conceptually)
        val afterRuns = Reducer.fold(store.loadProject(projectId) + userEvents + derived)

        // 2) sequence evaluations against current runs of the involved hypotheses
        for (c in afterRuns.constraints) {
            val aId = c.olderHypothesisId ?: c.coevalA
            val bId = c.youngerHypothesisId ?: c.coevalB
            val runA = afterRuns.runsOf(aId!!).firstOrNull { it.curveId == afterRuns.activeCurveId }
            val runB = afterRuns.runsOf(bId!!).firstOrNull { it.curveId == afterRuns.activeCurveId }
            if (runA == null || runB == null) continue
            val ev = Derivation.evaluateConstraint(
                c, runA, runB
            ) { run -> gridFromStored(run) }
            if (afterRuns.evaluations.any { it.evaluationId == ev.evaluationId }) continue
            emit(
                "evaluation.recorded", EventIds.evaluation(ev.evaluationId),
                ACTOR_SYSTEM,
                "派生：层序判定 ${c.note}" +
                    if (ev.violated) "（冲突，已生成候选调整，不改写测量）" else "（满足）",
                EvaluationRecorded(ev)
            )
        }

        return store.appendAll(userEvents + derived)
    }

    private fun gridFromStored(run: CalibrationRun): CalibrationEngine.GridResult {
        val ages = IntArray(run.grid.size)
        val dens = DoubleArray(run.grid.size)
        for ((i, gp) in run.grid.withIndex()) {
            ages[i] = gp.calBP
            dens[i] = gp.density
        }
        return CalibrationEngine.GridResult(
            ages, dens, dens,
            BooleanArray(run.grid.size) { true },
            run.boundaryLowTruncated, run.boundaryHighTruncated
        )
    }

    // ---------------- export / import / replay verification ----------------

    fun exportArchive(projectId: String): ProjectArchive {
        val events = store.loadProject(projectId)
        if (events.isEmpty()) throw ChronoException("project not found: $projectId")
        return ProjectArchive(projectId = projectId, events = events)
    }

    fun importArchive(archive: ProjectArchive, preserveIds: Boolean = true): Int {
        if (archive.format != "chronolab-archive-1")
            throw ChronoException("不支持的档案格式：${archive.format}")
        if (archive.events.isEmpty()) throw ChronoException("档案为空")
        val pid = archive.projectId
        if (store.listProjects().contains(pid))
            throw ChronoException("项目已存在：$pid（身份冲突，拒绝导入）")
        // Validate envelope ordering and stable ids before touching storage.
        archive.events.forEachIndexed { i, e ->
            if (e.projectId != pid)
                throw ChronoException("事件 ${e.eventId} 的 projectId 与档案不一致")
            if (e.seq != i + 1L)
                throw ChronoException("事件 ${e.eventId} 序号断裂：${e.seq} != ${i + 1}")
        }
        val seenIds = HashSet<String>()
        for (e in archive.events) {
            if (!seenIds.add(e.eventId))
                throw ChronoException("稳定身份重复：${e.eventId}")
            // Validate payload parses under the known schema.
            parsePayload(e)
        }
        val events = if (preserveIds) archive.events
        else archive.events.mapIndexed { i, e -> e.copy(seq = i + 1L) }
        store.appendAll(events)
        return events.size
    }

    /** Recompute every derived artifact from sourced inputs and compare with the log. */
    fun verifyReplay(projectId: String): ReplayReport {
        val s = state(projectId)
        val mismatches = mutableListOf<String>()
        for (run in s.runs) {
            val hyp = s.hypothesis(run.hypothesisId) ?: run {
                mismatches += "run ${run.runId}: 假设缺失"
                continue
            }
            val m = s.measurement(run.measurementId) ?: continue
            if (m.unconvertible) continue
            val r = s.reservoir(run.reservoirId)
            val curve = s.curves[run.curveId] ?: continue
            val rebuilt = Derivation.buildRun(hyp, m, r, curve, s.isExcluded(run), run.createdAtEvent, run.sources)
            if (rebuilt.parameterFingerprint != run.parameterFingerprint)
                mismatches += "run ${run.runId}: 参数指纹不一致"
            if (rebuilt.intervals.map { it.intervalId } != run.intervals.map { it.intervalId })
                mismatches += "run ${run.runId}: 区间身份/顺序不一致"
            val rebuiltDens = rebuilt.grid.map { String.format("%.8f", it.density) }
            val storedDens = run.grid.map { String.format("%.8f", it.density) }
            if (rebuiltDens != storedDens)
                mismatches += "run ${run.runId}: 密度网格不可重现"
        }
        return ReplayReport(
            runCount = s.runs.size,
            evaluationCount = s.evaluations.size,
            consistent = mismatches.isEmpty(),
            mismatches = mismatches
        )
    }

    data class ReplayReport(
        val runCount: Int,
        val evaluationCount: Int,
        val consistent: Boolean,
        val mismatches: List<String>
    )

    private fun parsePayload(e: Event) {
        when (e.type) {
            "project.created" -> e.payloadJson.decodeJson<ProjectCreated>()
            "curve.registered" -> e.payloadJson.decodeJson<CurveRegistered>()
            "curve.activated" -> e.payloadJson.decodeJson<CurveActivated>()
            "measurement.submitted" -> e.payloadJson.decodeJson<MeasurementSubmitted>()
            "reservoir.submitted" -> e.payloadJson.decodeJson<ReservoirSubmitted>()
            "hypothesis.created" -> e.payloadJson.decodeJson<HypothesisCreated>()
            "constraint.submitted" -> e.payloadJson.decodeJson<ConstraintSubmitted>()
            "era.changed" -> e.payloadJson.decodeJson<EraSystemChanged>()
            "exclusion.recorded" -> e.payloadJson.decodeJson<ExclusionRecorded>()
            "exclusion.revoked" -> e.payloadJson.decodeJson<ExclusionRevoked>()
            "run.recorded" -> e.payloadJson.decodeJson<RunRecorded>()
            "evaluation.recorded" -> e.payloadJson.decodeJson<EvaluationRecorded>()
            "decision.recorded" -> e.payloadJson.decodeJson<DecisionRecorded>()
            "decision.superseded" -> e.payloadJson.decodeJson<DecisionSuperseded>()
            "decision.revoked" -> e.payloadJson.decodeJson<DecisionRevoked>()
            "review.note" -> e.payloadJson.decodeJson<ReviewNote>()
            else -> throw ChronoException("未知事件类型：${e.type}（${e.eventId}）")
        }
    }

    // Helper for era version numbering.
    private fun ProjectState.events0(): Int = 0
}

// keep imports referenced
@Suppress("unused")
private val keepProject: Project? = null
@Suppress("unused")
private val keepHypothesis: Hypothesis? = null
@Suppress("unused")
private val keepSeq: SequenceConstraint? = null
@Suppress("unused")
private val keepEval: SequenceEvaluation? = null
@Suppress("unused")
private val keepProposal: AdjustmentProposal? = null
@Suppress("unused")
private val keepExclusion: Exclusion? = null
