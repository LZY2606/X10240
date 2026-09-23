package gsb

import kotlinx.serialization.json.*

class WorkbenchException(msg: String) : Exception(msg)

class Workbench(val store: Store) {

    fun latestCalibrationFor(measureStream: String, atSeq: Long = Long.MAX_VALUE): DerivedRecord? {
        val stale = store.staleIds(atSeq)
        return store.allDerived()
            .filter { it.kind == "calibration" && store.derivedSeq(it.id) <= atSeq }
            .filter { it.deps.any { d -> d.streamId == measureStream } }
            .filter { it.id !in stale }
            .maxByOrNull { store.derivedSeq(it.id) }
    }

    fun latestStratAssessmentFor(constraintStream: String, atSeq: Long = Long.MAX_VALUE): DerivedRecord? {
        val stale = store.staleIds(atSeq)
        return store.allDerived()
            .filter { it.kind == "stratAssessment" && store.derivedSeq(it.id) <= atSeq }
            .filter { it.deps.any { d -> d.streamId == constraintStream } }
            .filter { it.id !in stale }
            .maxByOrNull { store.derivedSeq(it.id) }
    }

    private fun headPayload(stream: String): Pair<InputRecord, JsonObject> {
        val rec = store.headOf(stream) ?: throw WorkbenchException("输入流 $stream 不存在")
        return rec to rec.payload
    }

    /** 校准：解析四个直接来源（测量、曲线版本、偏移分布、年代系统），产出多峰分布。 */
    fun calibrate(measureStream: String, curveStream: String, offsetStream: String, eraStream: String): DerivedRecord {
        val (mRec, mP) = headPayload(measureStream)
        val (cRec, cP) = headPayload(curveStream)
        val (oRec, oP) = headPayload(offsetStream)
        val (eRec, eP) = headPayload(eraStream)
        val m = JsonFmt.decodeFromJsonElement<Measurement>(mP)
        if (m.diagnostic != null || m.c14AgeBP == null || m.sigmaBP == null)
            throw WorkbenchException("测量 ${m.labCode} 无法校准：${m.diagnostic ?: "缺少数值"}（原始表示保留于输入记录）")
        val cref = JsonFmt.decodeFromJsonElement<CurveRef>(cP)
        val curve = Curves.get(cref.curveId, cref.curveVersion)
            ?: throw WorkbenchException("未知曲线 ${cref.curveId}:${cref.curveVersion}")
        val offset = JsonFmt.decodeFromJsonElement<OffsetDistribution>(oP)
        val era = JsonFmt.decodeFromJsonElement<EraSystem>(eP)
        val res = Calib.calibrate(curve, m.c14AgeBP, m.sigmaBP, offset)
        val result = buildJsonObject {
            put("calibration", res.toJson())
            put("labCode", m.labCode); put("rawMeasurement", m.raw)
            put("c14AgeBP", m.c14AgeBP); put("sigmaBP", m.sigmaBP); put("unit", m.unit)
            put("curveId", curve.curveId); put("curveVersion", curve.version)
            put("offset", JsonFmt.encodeToJsonElement(offset))
            put("eraSystem", era.system); put("measureStream", measureStream)
        }
        return store.recordDerived("calibration", ALGO_CALIB,
            listOf(Dep(measureStream, mRec.id), Dep(curveStream, cRec.id), Dep(offsetStream, oRec.id), Dep(eraStream, eRec.id)),
            result)
    }

    /** 层序评估：冲突时产生候选调整，绝不改写测量。 */
    fun assessStrat(constraintStream: String): DerivedRecord {
        val (sRec, sP) = headPayload(constraintStream)
        val sc = JsonFmt.decodeFromJsonElement<StratConstraint>(sP)
        val olderCal = latestCalibrationFor(sc.olderStream)
            ?: throw WorkbenchException("${sc.olderStream} 尚无有效校准结果")
        val youngerCal = latestCalibrationFor(sc.youngerStream)
            ?: throw WorkbenchException("${sc.youngerStream} 尚无有效校准结果")
        val olderRes = CalibResult.fromJson(olderCal.result["calibration"]!!.jsonObject)
        val youngerRes = CalibResult.fromJson(youngerCal.result["calibration"]!!.jsonObject)
        val olderOffset = JsonFmt.decodeFromJsonElement<OffsetDistribution>(olderCal.result["offset"]!!)
        val olderAge = olderCal.result["c14AgeBP"]!!.jsonPrimitive.double
        val olderSigma = olderCal.result["sigmaBP"]!!.jsonPrimitive.double
        val curve = Curves.get(olderCal.result["curveId"]!!.jsonPrimitive.content,
            olderCal.result["curveVersion"]!!.jsonPrimitive.content)!!
        val assess = Strat.assess(sRec.id, sc.olderStream, olderRes, olderCal.id,
            sc.youngerStream, youngerRes, youngerCal.id, olderOffset, olderAge, olderSigma, curve)
        val deps = (olderCal.deps + youngerCal.deps + Dep(constraintStream, sRec.id)).distinctBy { it.streamId }
        return store.recordDerived("stratAssessment", ALGO_STRAT, deps, assess.toJson())
    }

    fun resolveTarget(target: String): String = when {
        target.startsWith("calib:") -> latestCalibrationFor(target.removePrefix("calib:"))?.id
            ?: throw WorkbenchException("目标 $target 无有效校准")
        target.startsWith("strat:") -> latestStratAssessmentFor(target.removePrefix("strat:"))?.id
            ?: throw WorkbenchException("目标 $target 无有效层序评估")
        else -> target
    }

    fun adjudicate(target: String, verdict: String, rationale: String): Event {
        require(verdict in listOf("accept", "reject", "adjust")) { "verdict 必须是 accept/reject/adjust" }
        val resolved = resolveTarget(target)
        return store.appendEvent(Ev.ADJUDICATION, buildJsonObject {
            put("target", resolved); put("targetRef", target)
            put("verdict", verdict); put("rationale", rationale)
        })
    }

    fun resolveConflict(target: String, chosen: String, note: String): Event {
        val resolved = resolveTarget(target)
        val actualChosen = if (chosen.startsWith("verdict:")) {
            val v = chosen.removePrefix("verdict:")
            store.events().filter { it.type == Ev.ADJUDICATION &&
                it.payload["target"]?.jsonPrimitive?.content == resolved &&
                it.payload["verdict"]?.jsonPrimitive?.content == v }.lastOrNull()?.seq?.toString()
                ?: throw WorkbenchException("目标上没有 verdict=$v 的裁定")
        } else chosen
        return store.appendEvent(Ev.CONFLICT_RESOLVED, buildJsonObject {
            put("target", resolved); put("chosen", actualChosen); put("note", note)
        })
    }

    fun undoResolution(target: String): Event {
        val resolved = resolveTarget(target)
        val lastRes = store.events().filter {
            it.type == Ev.CONFLICT_RESOLVED && it.payload["target"]?.jsonPrimitive?.content == resolved
        }.lastOrNull() ?: throw WorkbenchException("目标 $target 没有可撤销的冲突解决")
        return store.appendEvent(Ev.CONFLICT_UNDONE, buildJsonObject {
            put("target", resolved); put("resolutionSeq", lastRes.seq)
        })
    }
}
