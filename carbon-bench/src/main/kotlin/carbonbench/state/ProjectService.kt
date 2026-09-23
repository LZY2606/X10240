package carbonbench.state

import carbonbench.curves.BuiltInCurves
import carbonbench.domain.CalibrationResult
import carbonbench.domain.DatingSystem
import carbonbench.domain.Event
import carbonbench.domain.ExclusionDecision
import carbonbench.domain.IntervalDecision
import carbonbench.domain.MeasurementVersion
import carbonbench.domain.OffsetComponent
import carbonbench.domain.Project
import carbonbench.domain.Quantity
import carbonbench.domain.ReservoirOffset
import carbonbench.domain.SequenceAnalysis
import carbonbench.domain.SequenceConstraint
import carbonbench.engine.CalibrationEngine
import carbonbench.engine.CalibrationRequest
import carbonbench.engine.SequenceEngine
import carbonbench.time.BenchClock
import carbonbench.util.CanonicalJson
import carbonbench.util.Hashing
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class ValidationException(message: String) : RuntimeException(message)

class ProjectService(
    private val store: EventStore,
    private val clock: BenchClock,
    private val calEngine: CalibrationEngine = CalibrationEngine(),
    private val seqEngine: SequenceEngine = SequenceEngine()
) {
    private fun nowIso(): String = clock.now().toString()

    private fun load(projectId: String): Snapshot = ProjectState.replay(store.listEvents(projectId))

    private fun append(projectId: String, type: String, payload: JsonObject, parent: String? = null, retracts: String? = null): Event {
        val seq = store.nextSeq(projectId)
        val id = "evt-${projectId}-$seq"
        val event = Event(
            id = id,
            projectId = projectId,
            seq = seq,
            type = type,
            atTime = nowIso(),
            actor = "reviewer",
            payload = payload,
            parentEventId = parent,
            retractsEventId = retracts
        )
        store.appendEvent(event)
        return event
    }

    private inline fun <reified T> payloadOf(value: T): JsonObject {
        val element = CanonicalJson.toElement(value)
        return element as? JsonObject
            ?: throw ValidationException("事件负载必须是对象")
    }

    private fun requireProject(snap: Snapshot): Project =
        snap.project ?: throw ValidationException("项目尚未创建")

    fun createProject(cmd: CreateProjectCmd, proposedId: String? = null): Pair<String, Event> {
        val id = proposedId ?: ("prj-" + Hashing.shortFingerprint(cmd.name + clock.now()).take(10))
        if (store.projectExists(id)) throw ValidationException("项目已存在：$id")
        val event = append(
            id,
            EventTypes.PROJECT_CREATED,
            payloadOf(
                Project(
                    id = id,
                    name = cmd.name,
                    casePackId = cmd.casePackId,
                    casePackVersion = cmd.casePackVersion,
                    datingSystem = cmd.datingSystem,
                    createdAt = nowIso()
                )
            )
        )
        return id to event
    }

    fun view(projectId: String): Snapshot {
        val snap = load(projectId)
        requireProject(snap)
        return snap
    }

    fun viewAt(projectId: String, eventId: String): Snapshot {
        val all = store.listEvents(projectId)
        val target = all.firstOrNull { it.id == eventId } ?: throw ValidationException("事件不存在：$eventId")
        val prefix = all.filter { it.seq <= target.seq }
        return ProjectState.replay(prefix)
    }

    fun events(projectId: String): List<Event> = store.listEvents(projectId)

    fun submitMeasurement(projectId: String, cmd: SubmitMeasurementCmd): Event {
        val snap = load(projectId); requireProject(snap)
        if (snap.measurements.any { it.labCode == cmd.labCode && it.version == 1 })
            throw ValidationException("实验室编号 ${cmd.labCode} 已存在；修订请使用新版本，不可覆盖原始输入")
        validateQuantity(cmd.ageRaw, cmd.ageValue, cmd.ageUnit, "14C", "年龄")
        validateQuantity(cmd.sigmaRaw, cmd.sigmaValue, cmd.sigmaUnit, "14C", "标准差")
        if (cmd.sigmaValue != null && cmd.sigmaValue <= 0.0)
            throw ValidationException("标准差必须为正数（保留原始输入于 raw 字段）")
        val version = 1
        val id = "meas-${cmd.labCode}-v$version"
        val mv = MeasurementVersion(
            id = id, projectId = projectId, labCode = cmd.labCode,
            parentId = null, version = version,
            age = Quantity(cmd.ageValue, null, cmd.ageUnit, cmd.ageRaw, cmd.diagnostics),
            sigma = Quantity(cmd.sigmaValue, null, cmd.sigmaUnit, cmd.sigmaRaw, cmd.diagnostics),
            sourceRef = cmd.sourceRef, active = true, createdAt = nowIso()
        )
        return append(projectId, EventTypes.MEASUREMENT_SUBMITTED, payloadOf(mv))
    }

    fun reviseMeasurement(projectId: String, cmd: ReviseMeasurementCmd): List<Event> {
        val snap = load(projectId); requireProject(snap)
        val current = snap.activeMeasurement(cmd.labCode)
            ?: throw ValidationException("没有可修订的活动测量：${cmd.labCode}")
        validateQuantity(cmd.ageRaw, cmd.ageValue, current.age.unit, "14C", "年龄")
        validateQuantity(cmd.sigmaRaw, cmd.sigmaValue, current.sigma.unit, "14C", "标准差")
        val newVersion = current.version + 1
        val id = "meas-${cmd.labCode}-v$newVersion"
        val revised = MeasurementVersion(
            id = id, projectId = projectId, labCode = cmd.labCode,
            parentId = current.id, version = newVersion,
            age = Quantity(cmd.ageValue, null, current.age.unit, cmd.ageRaw),
            sigma = Quantity(cmd.sigmaValue, null, current.sigma.unit, cmd.sigmaRaw),
            sourceRef = cmd.sourceRef, active = true, createdAt = nowIso()
        )
        val out = mutableListOf<Event>()
        val revisedEvent = append(projectId, EventTypes.MEASUREMENT_REVISED, payloadOf(revised))
        out.add(revisedEvent)
        load(projectId).calibrations
            .filter { it.measurementId == current.id && it.status == "current" }
            .forEach { out.add(invalidate(projectId, it.id, "测量 ${cmd.labCode} 修订为 $id；旧校准保留为过期结果")) }
        return out
    }

    fun recordOffset(projectId: String, cmd: RecordOffsetCmd): Event {
        val snap = load(projectId); requireProject(snap)
        if (cmd.components.isEmpty()) throw ValidationException("偏移分布至少需要一个分量")
        cmd.components.forEach {
            if (it.weight <= 0.0) throw ValidationException("偏移分量权重必须为正：${it.id}")
            if (it.sigma < 0.0) throw ValidationException("偏移分量 sigma 不能为负：${it.id}")
        }
        if (cmd.parentId != null) {
            val parent = snap.activeOffset(cmd.parentId) ?: throw ValidationException("父偏移版本不存在：${cmd.parentId}")
            val seq = snap.offsets.count { it.label == parent.label } + 1
            val id = "off-${parent.label}-v$seq"
            val off = ReservoirOffset(
                id = id, projectId = projectId, label = parent.label, kind = cmd.kind,
                components = cmd.components.map { OffsetComponent(it.id, it.weight, it.mean, it.sigma, it.basis) },
                sourceRef = cmd.sourceRef, parentId = parent.id, active = true, createdAt = nowIso()
            )
            return append(projectId, EventTypes.OFFSET_REVISED, payloadOf(off))
        }
        val seq = snap.offsets.count { it.label == cmd.label } + 1
        val id = if (seq == 1) "off-${cmd.label}-v1" else "off-${cmd.label}-v$seq"
        val off = ReservoirOffset(
            id = id, projectId = projectId, label = cmd.label, kind = cmd.kind,
            components = cmd.components.map { OffsetComponent(it.id, it.weight, it.mean, it.sigma, it.basis) },
            sourceRef = cmd.sourceRef, parentId = null, active = true, createdAt = nowIso()
        )
        return append(projectId, EventTypes.OFFSET_RECORDED, payloadOf(off))
    }

    private fun validateQuantity(raw: String, value: Double?, unit: String, expectPrefix: String, what: String) {
        if (raw.isBlank()) throw ValidationException("$what缺少原始表示（raw）")
        if (value == null) throw ValidationException("$what无法规范化为数值：保留 raw=$raw 并附加诊断，不能进入派生计算")
        if (!unit.contains(expectPrefix)) throw ValidationException("$what单位必须显式标注（收到 $unit）")
    }

    fun assertConstraint(projectId: String, cmd: AssertConstraintCmd): Event {
        val snap = load(projectId); requireProject(snap)
        if (snap.activeMeasurement(cmd.olderLabCode) == null)
            throw ValidationException("层序约束引用了未知样本：${cmd.olderLabCode}")
        if (snap.activeMeasurement(cmd.youngerLabCode) == null)
            throw ValidationException("层序约束引用了未知样本：${cmd.youngerLabCode}")
        if (cmd.olderLabCode == cmd.youngerLabCode)
            throw ValidationException("层序约束不能自指：${cmd.olderLabCode}")
        val duplicate = snap.activeConstraints().any {
            it.olderLabCode == cmd.olderLabCode && it.youngerLabCode == cmd.youngerLabCode
        }
        if (duplicate) throw ValidationException("层序约束已存在：${cmd.olderLabCode} olderThan ${cmd.youngerLabCode}")
        val n = snap.constraints.size + 1
        val c = SequenceConstraint(
            id = "con-$n", projectId = projectId,
            olderLabCode = cmd.olderLabCode, youngerLabCode = cmd.youngerLabCode,
            sourceRef = cmd.sourceRef, status = "asserted", createdAt = nowIso()
        )
        return append(projectId, EventTypes.CONSTRAINT_ASSERTED, payloadOf(c))
    }

    fun retractConstraint(projectId: String, cmd: RetractConstraintCmd): Event {
        val snap = load(projectId); requireProject(snap)
        val c = snap.constraints.firstOrNull { it.id == cmd.constraintId }
            ?: throw ValidationException("约束不存在：${cmd.constraintId}")
        val payload = JsonObject(
            mapOf(
                "constraintId" to JsonPrimitive(c.id),
                "note" to JsonPrimitive(cmd.note),
                "atTime" to JsonPrimitive(nowIso())
            )
        )
        return append(projectId, EventTypes.CONSTRAINT_RETRACTED, payload)
    }

    fun decideExclusion(projectId: String, cmd: ExclusionCmd): Event {
        val snap = load(projectId); requireProject(snap)
        snap.activeMeasurement(cmd.labCode)
            ?: throw ValidationException("排除决定必须针对已提交样本：${cmd.labCode}")
        if (cmd.reason.isBlank()) throw ValidationException("排除决定必须写明原因")
        val n = snap.exclusions.size + 1
        val d = ExclusionDecision(
            id = "exc-$n", projectId = projectId, labCode = cmd.labCode,
            excluded = cmd.excluded, reason = cmd.reason, sourceRef = cmd.sourceRef,
            status = "active", createdAt = nowIso()
        )
        return append(projectId, EventTypes.EXCLUSION_DECIDED, payloadOf(d))
    }

    fun retractExclusion(projectId: String, decisionId: String, note: String): Event {
        val snap = load(projectId); requireProject(snap)
        val d = snap.exclusions.firstOrNull { it.id == decisionId }
            ?: throw ValidationException("排除决定不存在：$decisionId")
        val retracted = d.copy(status = "retracted")
        val event = append(projectId, EventTypes.EXCLUSION_RETRACTED, payloadOf(retracted), retracts = d.id)
        appendNote(projectId, "exclusion-retraction-note", decisionId, note)
        return event
    }

    private fun appendNote(projectId: String, kind: String, targetId: String, note: String) {
        val payload = JsonObject(
            mapOf(
                "kind" to JsonPrimitive(kind),
                "targetId" to JsonPrimitive(targetId),
                "note" to JsonPrimitive(note),
                "atTime" to JsonPrimitive(nowIso())
            )
        )
        append(projectId, EventTypes.INPUT_DIAGNOSTIC, payload)
    }

    fun calibrate(projectId: String, cmd: CalibrateCmd): Pair<Event, CalibrationResult> {
        val snap = load(projectId); requireProject(snap)
        val measurement = snap.activeMeasurement(cmd.labCode)
            ?: throw ValidationException("没有活动测量：${cmd.labCode}")
        if (cmd.offsetId != null && snap.activeOffset(cmd.offsetId) == null)
            throw ValidationException("偏移版本不存在或已被取代：${cmd.offsetId}")
        if (!BuiltInCurves.exists(cmd.curveVersion))
            throw ValidationException("曲线版本未知：${cmd.curveVersion}（固定离线版本仅 1、2）")
        val curve = BuiltInCurves.get(cmd.curveVersion)
        val n = snap.calibrations.size + 1
        val idSeed = "cal-$n"
        val request = CalibrationRequest(
            idSeed = idSeed, projectId = projectId, measurement = measurement,
            offset = cmd.offsetId?.let { snap.activeOffset(it) }, curve = curve, gridStep = cmd.gridStep
        )
        val result = calEngine.calibrate(request, nowIso())
        val event = append(projectId, EventTypes.CALIBRATION_COMPUTED, payloadOf(result))
        return event to result
    }

    fun analyzeSequence(projectId: String, cmd: AnalyzeSequenceCmd): Pair<Event, SequenceAnalysis> {
        val snap = load(projectId); requireProject(snap)
        val selected = if (cmd.calibrationIds.isEmpty())
            snap.calibrations.filter { it.status == "current" }
        else cmd.calibrationIds.map { id -> snap.calibrations.firstOrNull { it.id == id }
            ?: throw ValidationException("校准不存在：$id") }
        val n = snap.analyses.size + 1
        val id = "seq-$n"
        val analysis = seqEngine.analyze(
            id = id, projectId = projectId, calibrations = selected,
            constraints = snap.activeConstraints(), nowIso = nowIso()
        )
        val event = append(projectId, EventTypes.SEQUENCE_COMPUTED, payloadOf(analysis))
        return event to analysis
    }

    fun decideInterval(projectId: String, cmd: IntervalDecisionCmd): Event {
        val snap = load(projectId); requireProject(snap)
        val cal = snap.calibrations.firstOrNull { it.id == cmd.calibrationId }
            ?: throw ValidationException("校准不存在：${cmd.calibrationId}")
        val valid = (cal.intervals68 + cal.intervals95).any { it.id == cmd.intervalId }
        if (!valid) throw ValidationException("区间身份不存在：${cmd.intervalId}")
        if (cmd.decision !in setOf("accepted", "rejected", "parameter-adjusted"))
            throw ValidationException("裁定只能是 accepted/rejected/parameter-adjusted")
        if (cmd.decision == "parameter-adjusted" && cmd.replacementCalibrationId == null)
            throw ValidationException("参数调整必须指向新的候选校准 id")
        val n = snap.intervalDecisions.size + 1
        val d = IntervalDecision(
            id = "idec-$n", calibrationId = cmd.calibrationId, intervalId = cmd.intervalId,
            decision = cmd.decision, note = cmd.note,
            replacementCalibrationId = cmd.replacementCalibrationId,
            status = "active", decidedAt = nowIso()
        )
        return append(projectId, EventTypes.INTERVAL_DECIDED, payloadOf(d))
    }

    fun retractIntervalDecision(projectId: String, decisionId: String, note: String): Event {
        val snap = load(projectId); requireProject(snap)
        val d = snap.intervalDecisions.firstOrNull { it.id == decisionId }
            ?: throw ValidationException("区间裁定不存在：$decisionId")
        val retracted = d.copy(status = "retracted")
        val event = append(projectId, EventTypes.DECISION_RETRACTED, payloadOf(retracted), retracts = d.id)
        appendNote(projectId, "interval-decision-retraction-note", decisionId, note)
        return event
    }

    fun acceptCandidate(projectId: String, analysisId: String, candidateId: String, note: String): Event {
        val snap = load(projectId); requireProject(snap)
        val analysis = snap.analyses.firstOrNull { it.id == analysisId }
            ?: throw ValidationException("层序分析不存在：$analysisId")
        val candidate = analysis.candidates.firstOrNull { it.id == candidateId }
            ?: throw ValidationException("候选调整不属于该分析：$candidateId")
        if (candidate.accepted) throw ValidationException("候选已被采纳：$candidateId")
        val payload = JsonObject(
            mapOf(
                "analysisId" to JsonPrimitive(analysisId),
                "candidateId" to JsonPrimitive(candidateId),
                "kind" to JsonPrimitive(candidate.kind),
                "targetId" to JsonPrimitive(candidate.targetId),
                "preservesMeasurement" to JsonPrimitive(candidate.preservesMeasurement),
                "note" to JsonPrimitive(note),
                "atTime" to JsonPrimitive(nowIso())
            )
        )
        return append(projectId, EventTypes.CANDIDATE_ADJUSTMENT_ACCEPTED, payload)
    }

    fun upgradeCurve(projectId: String, cmd: CurveUpgradeCmd): Pair<Event, CalibrationResult> {
        val snap = load(projectId); requireProject(snap)
        val old = snap.calibrations.firstOrNull { it.id == cmd.calibrationId }
            ?: throw ValidationException("校准不存在：${cmd.calibrationId}")
        if (old.curveVersion == cmd.targetCurveVersion)
            throw ValidationException("升版目标版本与当前版本相同")
        val measurement = snap.measurements.firstOrNull { it.id == old.measurementId }
            ?: throw ValidationException("无法解析直接来源测量：${old.measurementId}")
        val offset = old.offsetId?.let { oid -> snap.offsets.firstOrNull { it.id == oid } }
        val curve = BuiltInCurves.get(cmd.targetCurveVersion)
        val n = snap.calibrations.size + 1
        val idSeed = "cal-$n"
        val request = CalibrationRequest(
            idSeed = idSeed, projectId = projectId, measurement = measurement,
            offset = offset, curve = curve, gridStep = old.intervals95.firstOrNull()?.integralEvidence?.gridStep ?: 2.0
        )
        val result = calEngine.calibrate(request, nowIso())
        val event = append(projectId, EventTypes.CALIBRATION_COMPUTED, payloadOf(result))
        invalidate(projectId, old.id, "曲线升版：${old.curveVersion} -> ${cmd.targetCurveVersion}，旧结果保留为过期（superseded 标记由来源链表达）")
        return event to result
    }

    fun verifyCalibration(projectId: String, calibrationId: String): Pair<Event, Boolean> {
        val snap = load(projectId); requireProject(snap)
        val stored = snap.calibrations.firstOrNull { it.id == calibrationId }
            ?: throw ValidationException("校准不存在：$calibrationId")
        val measurement = snap.measurements.first { it.id == stored.measurementId }
        val offset = stored.offsetId?.let { oid -> snap.offsets.first { it.id == oid } }
        val curve = BuiltInCurves.get(stored.curveVersion)
        if (curve.fingerprint != stored.curveFingerprint)
            throw ValidationException("曲线 ${stored.curveVersion} 指纹与记录不符，无法复现")
        val gridStep = stored.intervals95.firstOrNull()?.integralEvidence?.gridStep ?: 2.0
        val recomputed = calEngine.calibrate(
            CalibrationRequest(stored.id, projectId, measurement, offset, curve, gridStep, stored.seed),
            stored.computedAt
        )
        val sameGrid = recomputed.grid.size == stored.grid.size &&
            recomputed.grid.zip(stored.grid).all { (a, b) ->
                a.id == b.id && a.calBP == b.calBP && Math.abs(a.density - b.density) < 1e-12
            }
        val sameFp = recomputed.parameterFingerprint == stored.parameterFingerprint
        val reproducible = sameGrid && sameFp
        val payload = JsonObject(
            mapOf(
                "calibrationId" to JsonPrimitive(calibrationId),
                "reproducible" to JsonPrimitive(reproducible),
                "sameGrid" to JsonPrimitive(sameGrid),
                "sameParameterFingerprint" to JsonPrimitive(sameFp),
                "algorithmVersion" to JsonPrimitive(stored.algorithmVersion),
                "curveFingerprint" to JsonPrimitive(stored.curveFingerprint),
                "checkedAt" to JsonPrimitive(nowIso())
            )
        )
        return append(projectId, EventTypes.CALIBRATION_VERIFIED, payload) to reproducible
    }

    private fun invalidate(projectId: String, calibrationId: String, reason: String): Event {
        val payload = JsonObject(
            mapOf(
                "calibrationId" to JsonPrimitive(calibrationId),
                "reason" to JsonPrimitive(reason),
                "atTime" to JsonPrimitive(nowIso())
            )
        )
        return append(projectId, EventTypes.DERIVED_INVALIDATED, payload)
    }

    fun importEvent(event: Event): Event {
        if (store.projectExists(event.projectId) && event.seq <= store.nextSeq(event.projectId) - 1)
            throw ValidationException("导入事件序号冲突：${event.id}")
        store.appendEvent(event)
        return event
    }
}
