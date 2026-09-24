package workbench

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class Workbench(val store: Store) {

    fun state(upTo: Long = Long.MAX_VALUE): ProjectState = Projections.replay(store.events(upTo))

    private fun emit(type: String, payload: JsonObject): StoredEvent = store.append(type, payload)

    fun importMeasurement(rawJson: String): Result<Measurement> = runCatching {
        val m = try {
            json.decodeFromString(Measurement.serializer(), rawJson)
        } catch (ex: Exception) {
            throw IllegalArgumentException("无法解析测量记录: ${ex.message}")
        }
        validateMeasurement(m)
        val st = state()
        val existing = st.measurements[m.sampleId]
        val stored = if (existing == null) {
            m.copy(version = 1, parentVersion = null, raw = rawJson)
        } else {
            m.copy(version = existing.version + 1, parentVersion = existing.version, raw = rawJson)
        }
        val type = if (existing == null) "measurement.imported" else "measurement.revised"
        emit(type, buildJsonObject { put("measurement", json.encodeToJsonElement(Measurement.serializer(), stored)) })
        stored
    }

    private fun validateMeasurement(m: Measurement) {
        require(m.sampleId.isNotBlank()) { "sampleId 不能为空" }
        require(m.labCode.isNotBlank()) { "labCode 不能为空" }
        require(m.sigma > 0) { "sigma 必须为正" }
        require(m.c14Age > -50000 && m.c14Age < 100000) { "c14Age 超出合理范围" }
        require(m.sourceRef.isNotBlank()) { "sourceRef 不能为空: 每条测量必须带来源" }
    }

    fun registerCurve(rawJson: String): Result<CurveData> = runCatching {
        val c = try {
            json.decodeFromString(CurveData.serializer(), rawJson)
        } catch (ex: Exception) {
            throw IllegalArgumentException("无法解析校准曲线: ${ex.message}")
        }
        require(c.curveId.isNotBlank()) { "curveId 不能为空" }
        require(c.version.isNotBlank()) { "version 不能为空" }
        require(c.points.size >= 2) { "曲线至少需要两个点" }
        require(c.reference.isNotBlank()) { "曲线必须带 reference 来源" }
        emit("curve.registered", buildJsonObject { put("curve", json.encodeToJsonElement(CurveData.serializer(), c)) })
        c
    }

    fun assumeOffset(rawJson: String): Result<OffsetAssumption> = runCatching {
        val o = try {
            json.decodeFromString(OffsetAssumption.serializer(), rawJson)
        } catch (ex: Exception) {
            throw IllegalArgumentException("无法解析储库偏移假设: ${ex.message}")
        }
        require(o.offsetId.isNotBlank()) { "offsetId 不能为空" }
        require(o.deltaRSigma >= 0) { "deltaRSigma 不能为负" }
        require(o.sourceRef.isNotBlank()) { "偏移假设必须带 sourceRef 来源" }
        val st = state()
        val existing = st.offsets[o.offsetId]
        val stored = if (existing == null) o.copy(version = 1, parentVersion = null)
        else o.copy(version = existing.version + 1, parentVersion = existing.version)
        val type = if (existing == null) "offset.assumed" else "offset.revised"
        emit(type, buildJsonObject { put("offset", json.encodeToJsonElement(OffsetAssumption.serializer(), stored)) })
        stored
    }

    fun addConstraint(rawJson: String): Result<SequenceConstraint> = runCatching {
        val c = try {
            json.decodeFromString(SequenceConstraint.serializer(), rawJson)
        } catch (ex: Exception) {
            throw IllegalArgumentException("无法解析层序约束: ${ex.message}")
        }
        require(c.constraintId.isNotBlank()) { "constraintId 不能为空" }
        require(c.olderSampleId != c.youngerSampleId) { "约束两端不能是同一样品" }
        require(c.sourceRef.isNotBlank()) { "层序约束必须带 sourceRef 来源" }
        emit("sequence.constraint_added", buildJsonObject { put("constraint", json.encodeToJsonElement(SequenceConstraint.serializer(), c)) })
        c
    }

    fun computeCalibration(sampleId: String, offsetId: String? = null): Result<CalibrationResult> = runCatching {
        val st = state()
        val m = st.measurements[sampleId] ?: throw IllegalArgumentException("未知样品: $sampleId")
        val curve = st.curves.values.filter { it.curveId == "intcal-shim" }.maxByOrNull { it.version }
            ?: st.curves.values.maxByOrNull { it.version }
            ?: throw IllegalStateException("尚未注册任何校准曲线")
        val offset = offsetId?.let { st.offsets[it] ?: throw IllegalArgumentException("未知偏移假设: $it") }
        val (grid, density) = Calibration.calibrate(
            curve, m.c14Age, m.sigma,
            offset?.deltaR ?: 0.0, offset?.deltaRSigma ?: 0.0
        )
        val intervals = Calibration.intervals(grid, density)
        val fp = Calibration.fingerprint(
            Calibration.ALGORITHM, curve.key(), m.sampleId, m.version, m.c14Age, m.sigma,
            offset?.offsetId ?: "none", offset?.version ?: 0, offset?.deltaR ?: 0.0, offset?.deltaRSigma ?: 0.0,
            Calibration.GRID_STEP
        )
        val sourceEvents = st.events.filter { e ->
            (e.type.startsWith("measurement.") && e.payload["measurement"]!!.jsonObject["sampleId"]?.jsonPrimitive?.content == sampleId) ||
                (e.type == "curve.registered" && e.payload["curve"]!!.jsonObject["version"]?.jsonPrimitive?.content == curve.version) ||
                (offset != null && e.type.startsWith("offset.") && e.payload["offset"]!!.jsonObject["offsetId"]?.jsonPrimitive?.content == offset.offsetId)
        }.map { it.id }
        val cal = CalibrationResult(
            calibrationId = "cal-" + Calibration.shortHash(fp),
            sampleId = sampleId,
            measurementVersion = m.version,
            curveId = curve.curveId,
            curveVersion = curve.version,
            offsetId = offset?.offsetId,
            offsetVersion = offset?.version,
            algorithm = Calibration.ALGORITHM,
            paramFingerprint = fp,
            gridStart = grid.first(),
            gridStep = Calibration.GRID_STEP,
            density = density,
            intervals = intervals,
            sourceEventIds = sourceEvents,
            note = "multimodal distribution retained; not collapsed to a mean"
        )
        emit("calibration.computed", buildJsonObject { put("calibration", json.encodeToJsonElement(CalibrationResult.serializer(), cal)) })
        detectConflicts()
        cal
    }

    fun detectConflicts(): List<Conflict> {
        val st = state()
        val found = mutableListOf<Conflict>()
        for (c in st.constraints) {
            val calA = st.latestCalibration(c.olderSampleId) ?: continue
            val calB = st.latestCalibration(c.youngerSampleId) ?: continue
            if (st.isStale(calA) || st.isStale(calB)) continue
            val p = Calibration.probabilityOlderThan(calA.grid(), calA.density, calB.grid(), calB.density)
            if (p < 0.5) {
                val conflictId = "conf-" + Calibration.shortHash(c.constraintId, calA.calibrationId, calB.calibrationId)
                if (st.conflicts.containsKey(conflictId)) continue
                val conflict = Conflict(conflictId, c.constraintId, c.olderSampleId, c.youngerSampleId, p)
                emit("conflict.detected", buildJsonObject { put("conflict", json.encodeToJsonElement(Conflict.serializer(), conflict)) })
                generateCandidates(conflict, st)
                found.add(conflict)
            }
        }
        return found
    }

    private fun generateCandidates(conflict: Conflict, st: ProjectState) {
        val kinds = mutableListOf(
            Triple("outlier-older", "将 ${conflict.olderSampleId} 标记为离群候选(不改写测量, 仅标记)", mapOf("sample" to conflict.olderSampleId)),
            Triple("outlier-younger", "将 ${conflict.youngerSampleId} 标记为离群候选(不改写测量, 仅标记)", mapOf("sample" to conflict.youngerSampleId)),
            Triple("relax-constraint", "把约束 ${conflict.constraintId} 视为不确定并保留全部候选", mapOf("constraint" to conflict.constraintId))
        )
        val hasOffset = listOf(conflict.olderSampleId, conflict.youngerSampleId)
            .mapNotNull { st.latestCalibration(it)?.offsetId }.isNotEmpty()
        if (hasOffset) {
            kinds.add(Triple("offset-adjust", "在 ΔR 不确定度内调整储库偏移假设并重算", mapOf("within" to "deltaRSigma")))
        }
        for ((kind, desc, params) in kinds) {
            val candidateId = "cand-" + Calibration.shortHash(conflict.conflictId, kind)
            if (st.candidates.containsKey(candidateId)) continue
            val cand = Candidate(candidateId, conflict.conflictId, kind, desc, params)
            emit("candidate.generated", buildJsonObject { put("candidate", json.encodeToJsonElement(Candidate.serializer(), cand)) })
        }
    }

    fun decideInterval(calibrationId: String, intervalIndex: Int, accept: Boolean, actor: String, rationale: String): Result<Decision> = runCatching {
        val st = state()
        val cal = st.calibrations[calibrationId] ?: throw IllegalArgumentException("未知校准结果: $calibrationId")
        require(cal.intervals.any { it.index == intervalIndex }) { "区间 $intervalIndex 不存在于 $calibrationId" }
        require(actor.isNotBlank()) { "裁定必须记录审阅者" }
        val seqNext = (st.events.maxOfOrNull { it.seq } ?: 0) + 1
        val d = Decision(
            decisionId = "dec-" + Calibration.shortHash(calibrationId, intervalIndex, accept, actor, seqNext),
            kind = if (accept) "interval-accept" else "interval-reject",
            targetId = "$calibrationId#$intervalIndex",
            actor = actor,
            rationale = rationale
        )
        emit("decision.made", buildJsonObject { put("decision", json.encodeToJsonElement(Decision.serializer(), d)) })
        d
    }

    fun decideCandidate(candidateId: String, accept: Boolean, actor: String, rationale: String): Result<Decision> = runCatching {
        val st = state()
        require(st.candidates.containsKey(candidateId)) { "未知候选: $candidateId" }
        require(actor.isNotBlank()) { "裁定必须记录审阅者" }
        val seqNext = (st.events.maxOfOrNull { it.seq } ?: 0) + 1
        val d = Decision(
            decisionId = "dec-" + Calibration.shortHash(candidateId, accept, actor, seqNext),
            kind = if (accept) "candidate-accept" else "candidate-reject",
            targetId = candidateId,
            actor = actor,
            rationale = rationale
        )
        emit("decision.made", buildJsonObject { put("decision", json.encodeToJsonElement(Decision.serializer(), d)) })
        d
    }

    fun undoDecision(targetEventSeq: Long, actor: String, rationale: String): Result<Unit> = runCatching {
        val st = state()
        val target = st.events.firstOrNull { it.seq == targetEventSeq }
            ?: throw IllegalArgumentException("未知事件序号: $targetEventSeq")
        require(target.type == "decision.made") { "只有裁定事件可以撤销, 实际类型: ${target.type}" }
        emit("decision.undone", buildJsonObject {
            put("targetEventSeq", targetEventSeq)
            put("actor", actor)
            put("rationale", rationale)
        })
    }

    fun integrationEvidence(calibrationId: String, intervalIndex: Int): Result<IntegrationEvidence> = runCatching {
        val st = state()
        val cal = st.calibrations[calibrationId] ?: throw IllegalArgumentException("未知校准结果: $calibrationId")
        val interval = cal.intervals.firstOrNull { it.index == intervalIndex }
            ?: throw IllegalArgumentException("区间 $intervalIndex 不存在")
        Calibration.integrate(cal.grid(), cal.density, interval.fromCalBP, interval.toCalBP, calibrationId, intervalIndex, interval.level)
    }

    fun export(): String = store.exportJson()

    fun importProject(exportJsonText: String): Result<Int> = runCatching {
        store.replaceWith(exportJsonText)
    }
}
