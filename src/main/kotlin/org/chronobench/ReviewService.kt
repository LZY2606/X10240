package org.chronobench

import kotlinx.serialization.builtins.ListSerializer

/**
 * 审阅/派生服务：校准触发、多偏移假设、层序、决定、stale 判定与时间旅行。
 */
class ReviewService(val projects: ProjectService, private val clock: Clock = SystemClock()) {
    private val db get() = projects.db

    private fun latest(id: String, projectId: String) = projects.latestVersion(projectId, id)

    /** 对一个测量，按给定偏移假设（null=无偏移）做一次校准并持久化派生结果。 */
    fun calibrate(projectId: String, measurementId: String, offsetId: String?, actor: String): CalibrationResult {
        val mv = requireLatest(projectId, measurementId, SourceKind.MEASUREMENT)
        val m = projects.payload(mv, MeasurementPayload.serializer())
        val cv = requireLatest(projectId, m.curveId, SourceKind.CURVE)
        val curve = projects.payload(cv, CurvePayload.serializer())
        val era = projects.latestByKind(projectId, SourceKind.ERA_SYSTEM).firstOrNull()
            ?: throw ImportException(listOf("缺少年代系统 ERA_SYSTEM，无法把 calBP 映射到 BCE/CE"))
        val (ov, off) = if (offsetId == null) null to null else {
            val v = requireLatest(projectId, offsetId, SourceKind.OFFSET_HYPOTHESIS)
            v to projects.payload(v, OffsetHypothesisPayload.serializer())
        }
        val inp = CalibrationEngine.Inputs(
            curve = curve, curveId = cv.id, curveVersion = cv.version,
            measurement = m, measurementVersion = mv.version,
            offset = off, offsetId = ov?.id, offsetVersion = ov?.version,
            eraSystemId = era.id, eraSystemVersion = era.version
        )
        val at = clock().nowMillis()
        val result = CalibrationEngine.calibrate(projectId, inp, IdGen.derivation(projectId), at)
        db.insertDerivation("CALIBRATION", result)
        db.appendEvent(EventRecord(0, IdGen.event(projectId, 0), projectId, "CALIBRATION_DERIVED", actor, at,
            targets = listOf(measurementId),
            inputs = result.directSources,
            outputs = listOf(result.derivationId),
            detail = projects.json.parseToJsonElement(
                """{"fingerprint":"${result.paramFingerprint}","algorithm":"${result.algorithmVersion}",
                   |"modeCount":${result.modeCount},"multimodal":${result.multimodal},
                   |"offset":"${offsetId ?: "none"}"}""".trimMargin()),
            summary = "校准 $measurementId (offset=${offsetId ?: "无"})：${result.modeCount} 个候选区间"))
        return result
    }

    /** 对一个测量重算全部适用偏移假设（全局 + 目标该样本），返回当前视图。 */
    fun calibrateAllHypotheses(projectId: String, measurementId: String, actor: String): List<CalibrationResult> {
        val mv = requireLatest(projectId, measurementId, SourceKind.MEASUREMENT)
        val m = projects.payload(mv, MeasurementPayload.serializer())
        val offsets = projects.latestByKind(projectId, SourceKind.OFFSET_HYPOTHESIS)
            .map { projects.payload(it, OffsetHypothesisPayload.serializer()) to it }
            .filter { it.first.targetSampleCode == null || it.first.targetSampleCode == m.sampleCode }
        val out = mutableListOf(calibrate(projectId, measurementId, null, actor))
        for ((_, ov) in offsets) out += calibrate(projectId, measurementId, ov.id, actor)
        return out
    }

    fun analyzeSequence(projectId: String, linkId: String, actor: String,
                        calBySample: Map<String, CalibrationResult>? = null): SequenceResult {
        val lv = requireLatest(projectId, linkId, SourceKind.SEQUENCE_LINK)
        val link = projects.payload(lv, SequenceLinkPayload.serializer())
        val olderCal = calBySample?.get(link.olderSampleCode) ?: currentCalibration(projectId, link.olderSampleCode)
        val youngerCal = calBySample?.get(link.youngerSampleCode) ?: currentCalibration(projectId, link.youngerSampleCode)
        val at = clock().nowMillis()
        val r = SequenceAnalyzer.analyze(projectId, link, lv.id, lv.version, olderCal, youngerCal,
            IdGen.derivation(projectId), at)
        db.insertSequenceDerivation(r)
        val a = r.links.first()
        val type = if (r.overallStatus == SequenceResult.CONFLICT) "SEQUENCE_CONFLICT_DETECTED" else "SEQUENCE_ASSESSED"
        db.appendEvent(EventRecord(0, IdGen.event(projectId, 0), projectId, type, actor, at,
            targets = listOf(linkId),
            inputs = r.directSources,
            outputs = listOf(r.derivationId),
            detail = projects.json.parseToJsonElement(
                """{"status":"${a.status}","probability":${a.probability},"legalPairs":${a.legalCombinations.size},
                   |"suggestions":${projects.json.encodeToString(kotlinx.serialization.builtins.ListSerializer(kotlinx.serialization.serializers.String.serializer()), a.suggestedAdjustments)}}""".trimMargin()),
            summary = "层序 $linkId: ${a.status} (P=${"%.4f".format(a.probability)})" +
                if (a.status == LinkAssessment.VIOLATED) "，生成 ${a.suggestedAdjustments.size} 条候选调整（不改写测量）" else ""))
        return r
    }

    // ---------------- decisions ----------------
    fun decideInterval(projectId: String, measurementId: String, intervalId: String,
                       accept: Boolean, reason: String, actor: String): EventRecord {
        val scope = "INTERVAL:$measurementId"
        val target = intervalId
        val at = clock().nowMillis()
        val decision = if (accept) "ACCEPTED" else "REJECTED"
        val ev = EventRecord(0, IdGen.event(projectId, 0), projectId,
            if (accept) "INTERVAL_ACCEPTED" else "INTERVAL_REJECTED", actor, at,
            targets = listOf("$measurementId/$intervalId"),
            detail = projects.json.parseToJsonElement("""{"reason":"${reason.replace("\"", "\\\"")}","decision":"$decision"}"""),
            summary = "${if (accept) "接受" else "否定"}区间 $measurementId/$intervalId：$reason")
        val seq = db.appendEvent(ev)
        db.upsertDecision(projectId, scope, target, decision, reason, seq)
        return ev.copy(seq = seq)
    }

    /** 撤销一次区间决定（历史保留撤销事件，不删除旧事件）。 */
    fun revertDecision(projectId: String, measurementId: String, intervalId: String, actor: String): EventRecord {
        val scope = "INTERVAL:$measurementId"
        val at = clock().nowMillis()
        db.deleteDecision(projectId, scope, intervalId)
        val ev = EventRecord(0, IdGen.event(projectId, 0), projectId, "DECISION_REVERTED", actor, at,
            targets = listOf("$measurementId/$intervalId"),
            summary = "撤销对 $measurementId/$intervalId 的人工裁定")
        val seq = db.appendEvent(ev)
        return ev.copy(seq = seq)
    }

    fun currentDecisions(projectId: String): Map<String, Triple<String, String?, Long>> =
        db.listDecisions(projectId, "INTERVAL:%")

    // ---------------- current view with stale + exclusions ----------------
    fun allCalibrationDerivations(projectId: String): List<CalibrationResult> =
        db.listDerivations(projectId, "CALIBRATION")
            .map { projects.json.decodeFromString(CalibrationResult.serializer(), it) }

    /**
     * 当前校准：取每个 (测量,偏移) 最新一次派生，应用排除/人工决定，
     * 并依据当前来源版本/参数指纹标记 current/stale。stale 仍可查看，不能冒充当前。
     */
    fun currentCalibrations(projectId: String): List<CalibrationResult> {
        val latest = allCalibrationDerivations(projectId)
            .groupBy { it.measurementId to (it.offsetId ?: "none") }
            .mapValues { it.value.maxBy { r -> r.computedAt } }
            .values
        val decisions = db.listDecisionsByPrefix(projectId, "INTERVAL:")
        return latest.map { r -> applyDecisions(r, decisions, projectId) }
    }

    fun currentCalibration(projectId: String, measurementId: String, offsetId: String? = null): CalibrationResult? {
        val same = currentCalibrations(projectId).filter {
            it.measurementId == measurementId && (it.offsetId ?: "none") == (offsetId ?: "none")
        }
        return same.maxByOrNull { it.computedAt }
    }

    private fun applyDecisions(r: CalibrationResult,
                               decisions: Map<String, Triple<String, String?, Long>>,
                               projectId: String): CalibrationResult {
        val prefix = "INTERVAL:${r.measurementId}/"
        val marked = r.intervals.map { iv ->
            val d = decisions[prefix + iv.intervalId]
            when (d?.first) {
                "REJECTED" -> iv.copy(excluded = true, exclusionReason = d.second)
                else -> iv
            }
        }
        val isStale = isStale(r, projectId)
        return r.copy(intervals = marked, status = if (isStale) CalibrationResult.STALE else CalibrationResult.CURRENT)
    }

    /**
     * stale 判定：任何直接来源当前版本高于派生时版本，或用同样输入重算的指纹不一致。
     */
    fun isStale(r: CalibrationResult, projectId: String): Boolean {
        val mv = projects.latestVersion(projectId, r.measurementId)
        if (mv == null || mv.version != r.measurementVersion) return true
        val cv = projects.latestVersion(projectId, r.curveId)
        if (cv == null || cv.version != r.curveVersion) return true
        if (r.offsetId != null) {
            val ov = projects.latestVersion(projectId, r.offsetId)
            if (ov == null || ov.version != (r.offsetVersion ?: -1)) return true
        }
        val era = projects.latestVersion(projectId, r.eraSystemId)
        if (era == null || era.version != r.eraSystemVersion) return true
        val recomputed = recomputeFingerprint(r, projectId)
        return recomputed != null && recomputed != r.paramFingerprint
    }

    private fun recomputeFingerprint(r: CalibrationResult, projectId: String): String? {
        val mv = db.loadSource(r.measurementId, r.measurementVersion) ?: return null
        val cv = db.loadSource(r.curveId, r.curveVersion) ?: return null
        val ev = db.loadSource(r.eraSystemId, r.eraSystemVersion) ?: return null
        val m = projects.payloadOrNull(mv, MeasurementPayload.serializer()) ?: return null
        val curve = projects.payloadOrNull(cv, CurvePayload.serializer()) ?: return null
        val (ov, off) = if (r.offsetId == null) null to null else {
            val o = db.loadSource(r.offsetId, r.offsetVersion ?: 0) ?: return null to null
            o to projects.payloadOrNull(o, OffsetHypothesisPayload.serializer())
        }
        val inp = CalibrationEngine.Inputs(
            curve, cv.id, cv.version, m, mv.version, off, ov?.id, ov?.version, ev.id, ev.version
        )
        val fresh = CalibrationEngine.calibrate(projectId, inp, r.derivationId, r.computedAt)
        return fresh.paramFingerprint
    }

    private fun requireLatest(projectId: String, id: String, kind: SourceKind): SourceVersion {
        val v = projects.latestVersion(projectId, id)
            ?: throw ImportException(listOf("来源 $id 不存在"))
        if (v.kind != kind) throw ImportException(listOf("来源 $id 类型为 ${v.kind}，期望 $kind"))
        return v
    }
}
