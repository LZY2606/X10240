package org.chronolab.carbon.workbench

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.chronolab.carbon.calib.CalibrationConfig
import org.chronolab.carbon.calib.CalibrationCurve
import org.chronolab.carbon.calib.CalibrationEngine
import org.chronolab.carbon.calib.CalCurvePoint
import org.chronolab.carbon.calib.ReservoirComponent
import org.chronolab.carbon.calib.ReservoirModel
import org.chronolab.carbon.calib.SyntheticCurves
import org.chronolab.carbon.model.ALGO_CALIBRATION
import org.chronolab.carbon.model.ALGO_SEQUENCE
import org.chronolab.carbon.model.ArchiveCaseResult
import org.chronolab.carbon.model.CalibrationInterval
import org.chronolab.carbon.model.CalibrationResult
import org.chronolab.carbon.model.CandidateAdjustment
import org.chronolab.carbon.model.DecisionRecord
import org.chronolab.carbon.model.DecisionVerb
import org.chronolab.carbon.model.Diagnostic
import org.chronolab.carbon.model.ERA_CALBP
import org.chronolab.carbon.model.EvArchiveRecorded
import org.chronolab.carbon.model.EvBranchCreated
import org.chronolab.carbon.model.EvCalibrationComputed
import org.chronolab.carbon.model.EvDecision
import org.chronolab.carbon.model.EvDecisionUndone
import org.chronolab.carbon.model.EvNote
import org.chronolab.carbon.model.EvSequenceComputed
import org.chronolab.carbon.model.EvSourceCommitted
import org.chronolab.carbon.model.FeasibleChoice
import org.chronolab.carbon.model.Origin
import org.chronolab.carbon.model.ProjectEvent
import org.chronolab.carbon.model.Provenance
import org.chronolab.carbon.model.SequenceAssessment
import org.chronolab.carbon.model.SequenceLink
import org.chronolab.carbon.model.SequenceStatus
import org.chronolab.carbon.model.SourceKind
import org.chronolab.carbon.model.SourceNode
import org.chronolab.carbon.model.SourceRef
import org.chronolab.carbon.store.EventStore
import org.chronolab.carbon.store.ProjectState
import org.chronolab.carbon.store.Projection
import org.chronolab.carbon.store.VirtualClock
import org.chronolab.carbon.model.Branch

/**
 * 工作台：所有变更都以事件追加。派生结果带算法版本/直接来源/参数指纹。
 * 局部输入改动只令依赖它的派生变为 STALE（旧结果仍可查看），随后按需重算。
 */
class Workbench(
    val store: EventStore,
    val clock: VirtualClock = VirtualClock(),
    val seed: Long = 20260924L,
    val gridStep: Double = 1.0
) {
    val json = Json { prettyPrint = false; ignoreUnknownKeys = true; encodeDefaults = true }

    fun state(branchId: String = "main", upToSeq: Long? = null): ProjectState {
        val events = store.eventsOnBranch(branchId, upToSeq)
        return Projection.project(events, store.listBranches())
    }

    fun currentEventSeq(branchId: String = "main"): Long =
        store.branch(branchId)?.headSeq ?: 0L

    // ---------- 来源提交（不可变，版本链） ----------

    fun commitSource(
        logicalId: String,
        kind: SourceKind,
        eraSystem: String,
        origin: Origin,
        fields: JsonObject,
        rawRepresentations: Map<String, String> = emptyMap(),
        diagnostics: List<Diagnostic> = emptyList(),
        branchId: String = "main"
    ): SourceNode {
        val prior = store.eventsOnBranch(branchId)
            .mapNotNull { ev -> if (ev.type == "SourceCommitted") json.decodeFromString<EvSourceCommitted>(ev.payloadJson).source else null }
            .filter { it.logicalId == logicalId }
        val version = (prior.maxOfOrNull { it.version } ?: 0) + 1
        val node = SourceNode(
            nodeId = "$logicalId@v$version",
            logicalId = logicalId, kind = kind, version = version,
            parentNodeId = prior.lastOrNull()?.nodeId,
            eraSystem = eraSystem, origin = origin, fields = fields,
            rawRepresentations = rawRepresentations, diagnostics = diagnostics
        )
        val basis = buildString {
            append("{\"immutable\":true,\"priorVersion\":")
            append(version - 1)
            append(",\"rule\":\"source-append-only/1.0.0\"}")
        }
        store.append(branchId, "SourceCommitted", json.encodeToString(EvSourceCommitted.serializer(), EvSourceCommitted(node)), clock.nowIso(), basis)
        clock.advanceSeconds(1)
        return node
    }

    // ---------- 曲线读取 ----------

    fun curveFor(state: ProjectState, curveVersion: String?): Pair<CalibrationCurve, SourceNode> {
        val curveSources = state.curves
        val src = (curveVersion?.let { v -> curveSources.firstOrNull { it.fields["version"]?.jsonPrimitive?.content == v } }
            ?: curveSources.lastOrNull())
            ?: error("项目中还没有校准曲线来源")
        return decodeCurve(src) to src
    }

    @Suppress("UNCHECKED_CAST")
    fun decodeCurve(src: SourceNode): CalibrationCurve {
        val pts = src.fields["points"]!!.jsonArray.map { p ->
            val o = p.jsonObject
            CalCurvePoint(o["calBP"]!!.jsonPrimitive.content.toInt(),
                o["radiocarbonBP"]!!.jsonPrimitive.content.toDouble(),
                o["sigma"]!!.jsonPrimitive.content.toDouble())
        }
        return CalibrationCurve(
            curveId = src.fields["curveId"]!!.jsonPrimitive.content,
            version = src.fields["version"]!!.jsonPrimitive.content,
            points = pts,
            description = src.fields["description"]?.jsonPrimitive?.content ?: ""
        )
    }

    fun reservoirFor(state: ProjectState, reservoirLogicalId: String?): Pair<ReservoirModel, SourceNode?> {
        if (reservoirLogicalId == null) return ReservoirModel.NONE to null
        val src = state.source(reservoirLogicalId) ?: return ReservoirModel.NONE to null
        return decodeReservoir(src) to src
    }

    fun decodeReservoir(src: SourceNode): ReservoirModel {
        val comps = src.fields["components"]!!.jsonArray.map { c ->
            val o = c.jsonObject
            ReservoirComponent(
                o["meanYears"]!!.jsonPrimitive.content.toDouble(),
                o["sigmaYears"]!!.jsonPrimitive.content.toDouble(),
                o["weight"]!!.jsonPrimitive.content.toDouble()
            )
        }
        return ReservoirModel(
            src.logicalId,
            src.fields["label"]?.jsonPrimitive?.content ?: src.logicalId,
            (src.fields["marineReservoirYears"]?.jsonPrimitive?.content?.toDouble()) ?: 0.0,
            comps
        )
    }

    // ---------- 校准派生 ----------

    /**
     * 计算单个测量的校准。返回新的版本化派生；若输入指纹与当前版本一致则直接返回当前结果。
     * 当来源发生变化时，调用方先通过 [markStaleDerivations] 标记旧结果过期。
     */
    fun calibrate(
        measurementLogicalId: String,
        curveVersion: String? = null,
        reservoirLogicalId: String? = null,
        branchId: String = "main",
        gridStart: Double? = null,
        gridEnd: Double? = null,
        stepOverride: Double? = null
    ): CalibrationResult {
        val state = state(branchId)
        val meas = state.source(measurementLogicalId) ?: error("测量来源不存在: $measurementLogicalId")
        val (curve, curveSrc) = curveFor(state, curveVersion)
        val (reservoir, reservoirSrc) = reservoirFor(state, reservoirLogicalId)
        val age = meas.fields["ageBP"]!!.jsonPrimitive.content.toDouble()
        val sigma = meas.fields["sigmaBP"]!!.jsonPrimitive.content.toDouble()
        val step = stepOverride ?: gridStep
        val start = gridStart ?: (curve.minCalBP.toDouble())
        val end = gridEnd ?: (curve.maxCalBP.toDouble())
        val cfg = CalibrationConfig(start, end, step, 4.0, seed)

        val directSources = listOf(
            SourceRef(meas.nodeId, meas.version, "measurement"),
            SourceRef(curveSrc.nodeId, curveSrc.version, "calibrationCurve")
        ) + listOfNotNull(reservoirSrc?.let { SourceRef(it.nodeId, it.version, "reservoirModel") })

        val paramFingerprint = Fingerprint.short(
            listOf(
                age, sigma, curve.version, reservoir.reservoirId,
                reservoir.marineReservoirYears,
                reservoir.components.joinToString(",") { "${it.meanYears}/${it.sigmaYears}/${it.weight}" },
                start, end, step, seed, ALGO_CALIBRATION
            ).joinToString("|")
        )
        val existing = state.currentCalibration(measurementLogicalId)?.calibration
        if (existing != null && existing.provenance.parameterFingerprint == paramFingerprint) {
            return existing
        }

        val out = CalibrationEngine.calibrate(age, sigma, curve, reservoir, cfg, "cal-$measurementLogicalId")
            ?: error("测量 $age ± $sigma BP 在曲线域内无后验支撑")

        val version = (state.calibrations[measurementLogicalId]?.maxOf { it.version } ?: 0) + 1
        val calId = "cal-$measurementLogicalId@v$version"
        val rename = { id: String -> id.replace("cal-$measurementLogicalId", calId) }
        val result = CalibrationResult(
            calibrationId = calId,
            measurementNodeId = meas.nodeId,
            curveVersion = curve.version,
            reservoirNodeId = reservoirSrc?.nodeId,
            gridStartCalBP = start, gridStepYears = step, gridEndCalBP = end,
            supportSigma = 4.0,
            density = out.density.toList(),
            totalMass = out.totalMass,
            intervals68 = out.intervals68.map { it.copy(intervalId = rename(it.intervalId)) },
            intervals95 = out.intervals95.map { it.copy(intervalId = rename(it.intervalId)) },
            modes = out.modes.map { it.copy(modeId = rename(it.modeId)) },
            multimodal = out.multimodal,
            tiedIntervals = out.tiedIntervals,
            crossesBceCe = out.crossesBceCe,
            provenance = Provenance(
                algorithmVersion = ALGO_CALIBRATION,
                directSources = directSources,
                parameterFingerprint = paramFingerprint,
                seed = seed,
                computedAtVirtualTime = clock.nowIso()
            )
        )
        store.append(branchId, "CalibrationComputed",
            json.encodeToString(EvCalibrationComputed.serializer(), EvCalibrationComputed(result, "CURRENT")),
            clock.nowIso(),
            json.encodeToString(CalibrationResult.serializer(), result))
        clock.advanceSeconds(1)
        return result
    }

    /**
     * 局部失效：来源节点变化后，仅把直接或间接依赖它的当前派生标记为 STALE。
     * STALE 节点保留可见，但界面不得将其当作当前结论。
     */
    fun markStaleDerivations(changedSourceNodeId: String, branchId: String = "main"): List<String> {
        val state = state(branchId)
        val stale = mutableListOf<String>()
        val calibMap = state.calibrations
        calibMap.forEach { (_, versions) ->
            val cur = versions.lastOrNull() ?: return@forEach
            if (cur.status != "CURRENT") return@forEach
            val refs = cur.calibration?.provenance?.directSources ?: return@forEach
            if (refs.any { it.nodeId == changedSourceNodeId }) {
                appendStale(cur.calibration!!, "CALIBRATION", branchId)
                stale += cur.nodeId
            }
        }
        state.assessments.forEach { (_, versions) ->
            val cur = versions.lastOrNull() ?: return@forEach
            if (cur.status != "CURRENT") return@forEach
            val assessmentRefs = cur.assessment?.provenance?.directSources.orEmpty()
            // 评估直接依赖各校准节点；再沿校准的直接来源判断是否触及本次变化
            val touches = assessmentRefs.any { it.nodeId in stale } || assessmentRefs.any { ref ->
                val calLogical = ref.nodeId.removePrefix("cal-").substringBefore("@v")
                val calNode = state.currentCalibration(calLogical)?.calibration
                calNode?.provenance?.directSources?.any { it.nodeId == changedSourceNodeId } == true
            }
            if (touches) {
                appendStale(cur.assessment!!, "SEQUENCE_ASSESSMENT", branchId)
                stale += cur.nodeId
            }
        }
        return stale
    }

    private fun appendStale(result: CalibrationResult, @Suppress("UNUSED_PARAMETER") type: String, branchId: String) {
        val ds = org.chronolab.carbon.model.EvDerivedStatus(
            result.calibrationId, "CALIBRATION", "STALE",
            "direct source changed; historical calibration retained and viewable",
            result.provenance.directSources.joinToString(",") { it.nodeId })
        store.append(branchId, "DerivedStatus",
            json.encodeToString(org.chronolab.carbon.model.EvDerivedStatus.serializer(), ds),
            clock.nowIso(), "{\"statusRule\":\"stale-not-current/1.0.0\"}")
        clock.advanceSeconds(1)
    }

    private fun appendStale(assessment: SequenceAssessment, @Suppress("UNUSED_PARAMETER") type: String, branchId: String) {
        val ds = org.chronolab.carbon.model.EvDerivedStatus(
            assessment.assessmentId, "SEQUENCE_ASSESSMENT", "STALE",
            "dependency changed; historical assessment retained and viewable",
            assessment.provenance.directSources.joinToString(",") { it.nodeId })
        store.append(branchId, "DerivedStatus",
            json.encodeToString(org.chronolab.carbon.model.EvDerivedStatus.serializer(), ds),
            clock.nowIso(), "{\"statusRule\":\"stale-not-current/1.0.0\"}")
        clock.advanceSeconds(1)
    }

    // ---------- 层序可行性 ----------

    fun decodeSequenceLinks(src: SourceNode): List<SequenceLink> =
        src.fields["links"]!!.jsonArray.mapIndexed { i, l ->
            val o = l.jsonObject
            SequenceLink(
                linkId = o["linkId"]?.jsonPrimitive?.content ?: "link-$i",
                olderLogicalId = o["older"]!!.jsonPrimitive.content,
                youngerLogicalId = o["younger"]!!.jsonPrimitive.content,
                justification = o["justification"]?.jsonPrimitive?.content ?: ""
            )
        }

    /**
     * 基于各测量当前校准的 95% HPD 区间集合，检查“老于”层序是否存在合法赋值。
     * 冲突时产生候选调整（放宽关系/排除/复测），绝不改写测量值本身。
     * 无法唯一决定时保留全部合法候选组合。
     */
    fun assessSequence(sequenceLogicalId: String, branchId: String = "main"): SequenceAssessment {
        val state = state(branchId)
        val linkSrc = state.source(sequenceLogicalId) ?: error("层序来源不存在: $sequenceLogicalId")
        val links = decodeSequenceLinks(linkSrc)
        val excluded = state.currentSources()
            .filter { it.kind == SourceKind.EXCLUSION }
            .flatMap { ex -> ex.fields["excludedLogicalIds"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList() }
            .toSet()
        val activeLinks = links.filter { it.olderLogicalId !in excluded && it.youngerLogicalId !in excluded }
        val ordered = linkedOrder(activeLinks)

        val intervalsBySample = linkedMapOf<String, List<CalibrationInterval>>()
        var unknown = false
        for (id in ordered) {
            val cal = state.currentCalibration(id)
            val c = cal?.calibration
            if (c == null || cal.status != "CURRENT") { unknown = true; continue }
            intervalsBySample[id] = c.intervals95
        }

        // 每个样本可选取的区间；枚举可行赋值（小规模枚举，保持全部合法候选）
        val feasible = mutableListOf<FeasibleChoice>()
        val violated = mutableListOf<String>()
        if (!unknown && ordered.all { it in intervalsBySample }) {
            enumerateChoices(ordered, activeLinks, intervalsBySample, feasible)
            for (link in activeLinks) {
                val olderMin = intervalsBySample[link.olderLogicalId]!!.minOf { it.startCalBP }
                val youngerMax = intervalsBySample[link.youngerLogicalId]!!.maxOf { it.endCalBP }
                // 老年样本必须在整体上早于（calBP 大于）年轻样本；若 95% 集合完全不允许则记为违反
                if (olderMin >= youngerMax) violated += link.linkId
            }
        }

        val status = when {
            unknown -> SequenceStatus.UNKNOWN
            feasible.isEmpty() -> SequenceStatus.CONFLICT
            else -> SequenceStatus.FEASIBLE
        }
        val candidates = if (status == SequenceStatus.CONFLICT) buildAdjustments(activeLinks, ordered, intervalsBySample) else emptyList()

        val version = (state.assessments[sequenceLogicalId]?.maxOf { it.version } ?: 0) + 1
        val assessId = "$sequenceLogicalId@v$version"
        val directSources = buildList {
            add(SourceRef(linkSrc.nodeId, linkSrc.version, "sequenceLinks"))
            ordered.forEach { id ->
                state.currentCalibration(id)?.let { add(SourceRef(it.nodeId, it.version, "calibration")) }
            }
            state.currentSources().filter { it.kind == SourceKind.EXCLUSION }.forEach {
                add(SourceRef(it.nodeId, it.version, "exclusion"))
            }
        }
        val fp = Fingerprint.short(
            assessId + directSources.joinToString(",") { "${it.nodeId}:${it.version}" } + ALGO_SEQUENCE + seed
        )
        val assessment = SequenceAssessment(
            assessmentId = assessId,
            orderedOldToYoung = ordered,
            links = activeLinks,
            status = status,
            feasibleChoices = feasible,
            violatedLinkIds = violated,
            candidateAdjustments = candidates,
            excludedSampleIds = excluded.toList(),
            provenance = Provenance(ALGO_SEQUENCE, directSources, fp, seed, clock.nowIso())
        )
        store.append(branchId, "SequenceComputed",
            json.encodeToString(EvSequenceComputed.serializer(), EvSequenceComputed(assessment, "CURRENT")),
            clock.nowIso(), json.encodeToString(SequenceAssessment.serializer(), assessment))
        clock.advanceSeconds(1)
        return assessment
    }

    private fun linkedOrder(links: List<SequenceLink>): List<String> {
        val youngerSet = links.map { it.youngerLogicalId }.toSet()
        val adjacency = links.groupBy { it.olderLogicalId }
        val roots = links.flatMap { listOf(it.olderLogicalId, it.youngerLogicalId) }.distinct().filter { it !in youngerSet }
        val seen = linkedSetOf<String>()
        fun dfs(node: String) {
            if (node in seen) return
            seen += node
            adjacency[node]?.forEach { dfs(it.youngerLogicalId) }
        }
        roots.forEach(::dfs)
        links.flatMap { listOf(it.olderLogicalId, it.youngerLogicalId) }.forEach { seen.add(it) }
        return seen.toList()
    }

    private fun enumerateChoices(
        ordered: List<String>,
        links: List<SequenceLink>,
        intervals: Map<String, List<CalibrationInterval>>,
        out: MutableList<FeasibleChoice>,
        chosen: MutableMap<String, String> = mutableMapOf(),
        idx: Int = 0
    ) {
        if (out.size >= 64) return // 固定上限，确定性保留前 64 个合法候选
        if (idx == ordered.size) {
            val ok = links.all { link ->
                val a = intervals[link.olderLogicalId]!!.first { it.intervalId == chosen[link.olderLogicalId] }
                val b = intervals[link.youngerLogicalId]!!.first { it.intervalId == chosen[link.youngerLogicalId] }
                // 区间存在非空先后关系：老区间起点 > 新区间终点
                a.startCalBP > b.endCalBP
            }
            if (ok) out += FeasibleChoice(
                "choice-${out.size}", chosen.toMap(),
                if (out.isEmpty()) "枚举得到的合法层序赋值" else "并列合法候选之一"
            )
            return
        }
        val sample = ordered[idx]
        for (iv in intervals[sample].orEmpty()) {
            chosen[sample] = iv.intervalId
            enumerateChoices(ordered, links, intervals, out, chosen, idx + 1)
        }
        chosen.remove(sample)
    }

    private fun buildAdjustments(
        links: List<SequenceLink>,
        ordered: List<String>,
        intervals: Map<String, List<CalibrationInterval>>
    ): List<CandidateAdjustment> = buildList {
        links.forEach { link ->
            val olderMin = intervals[link.olderLogicalId]?.minOfOrNull { it.startCalBP }
            val youngerMax = intervals[link.youngerLogicalId]?.maxOfOrNull { it.endCalBP }
            if (olderMin != null && youngerMax != null && olderMin <= youngerMax) {
                add(CandidateAdjustment(
                    "cand-relax-${link.linkId}", "RELAX_LINK",
                    "层序关系 ${link.linkId}（${link.olderLogicalId} 老于 ${link.youngerLogicalId}）在当前 95% 区间下不可同时满足，建议复核关系证据",
                    link.linkId, rewritesMeasurement = false
                ))
                add(CandidateAdjustment(
                    "cand-revisit-${link.youngerLogicalId}", "REVISIT_MEASUREMENT",
                    "建议复测或核对 ${link.youngerLogicalId} 的测量/储库假设（不自动改写原测量）",
                    link.youngerLogicalId, rewritesMeasurement = false
                ))
            }
        }
    }

    // ---------- 人工裁定（事件化，可撤销） ----------

    fun decide(
        verb: DecisionVerb,
        targetNodeId: String,
        reason: String,
        actor: String,
        targetIntervalId: String? = null,
        branchId: String = "main"
    ): DecisionRecord {
        val seq = (state(branchId).decisions.size + 1)
        val id = "dec-${"%04d".format(seq)}-${Fingerprint.short(verb.name + targetNodeId + reason + clock.nowIso()).take(6)}"
        val record = DecisionRecord(id, verb, targetNodeId, targetIntervalId, reason, actor, clock.nowIso())
        store.append(branchId, "Decision", json.encodeToString(EvDecision.serializer(), EvDecision(record)), clock.nowIso(),
            "{\"humanDecision\":true,\"rewritesSource\":false}")
        clock.advanceSeconds(1)
        return record
    }

    fun undoDecision(decisionId: String, reason: String, branchId: String = "main"): ProjectEvent {
        val current = state(branchId).decisions.firstOrNull { it.decisionId == decisionId }
            ?: error("裁定不存在: $decisionId")
        require(!current.undone) { "该裁定已撤销" }
        val undoId = "undo-${decisionId}"
        val ev = store.append(branchId, "DecisionUndone",
            json.encodeToString(EvDecisionUndone.serializer(), EvDecisionUndone(decisionId, undoId, reason)),
            clock.nowIso(), "{\"undo\":true,\"targetDecision\":$decisionId}")
        clock.advanceSeconds(1)
        return ev
    }

    fun note(text: String, actor: String, refNodeId: String? = null, branchId: String = "main"): ProjectEvent {
        val ev = store.append(branchId, "Note",
            json.encodeToString(EvNote.serializer(), EvNote(text, actor, refNodeId)), clock.nowIso())
        clock.advanceSeconds(1)
        return ev
    }

    // ---------- 分支（时间旅行 / 反例变体） ----------

    fun createBranch(label: String, parentBranchId: String, forkedFromEventId: String): Branch {
        val id = "br-" + Fingerprint.short(label + parentBranchId + forkedFromEventId).take(8)
        val parentEvents = store.eventsOnBranch(parentBranchId)
        val forkSeq = parentEvents.first { it.eventId == forkedFromEventId }.seq
        val branch = Branch(id, label, parentBranchId, forkedFromEventId, headSeq = 0L)
        store.upsertBranch(branch, withEventSeq = forkSeq)
        store.append(id, "BranchCreated",
            json.encodeToString(EvBranchCreated.serializer(), EvBranchCreated(branch)), clock.nowIso())
        clock.advanceSeconds(1)
        return branch
    }

    fun recordArchive(result: ArchiveCaseResult, branchId: String = "main") {
        store.append(branchId, "ArchiveRecorded",
            json.encodeToString(EvArchiveRecorded.serializer(), EvArchiveRecorded(result)), clock.nowIso(),
            "{\"machineReadableJudgement\":true}")
        clock.advanceSeconds(1)
    }

}
