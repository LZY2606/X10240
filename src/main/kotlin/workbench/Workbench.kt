package workbench

import kotlinx.serialization.json.*

interface Clock { fun now(): Long }

class VirtualClock(private var t: Long = 1_700_000_000_000L, private val step: Long = 1000L) : Clock {
    override fun now(): Long { val v = t; t += step; return v }
}

object SystemClock : Clock { override fun now(): Long = System.currentTimeMillis() }

data class DerivedView(val record: DerivedRecord, val status: DerivedStatus)

data class DecisionView(
    val eventId: String, val derivedId: String, val accept: Boolean,
    val reviewer: String, val note: String, val undone: Boolean, val chosenByResolution: Boolean
)

data class Snapshot(
    val seq: Long,
    val inputs: List<InputRecord>,
    val derived: List<DerivedView>,
    val decisions: List<DecisionView>,
    val events: List<EventRecord>
)

class Workbench(val store: Store, val clock: Clock = SystemClock) {

    // ---------- inputs ----------

    fun submitInput(kind: InputKind, streamId: String, payload: JsonObject, provenance: String): InputRecord {
        val existing = store.inputs().filter { it.streamId == streamId }
        val version = (existing.maxOfOrNull { it.version } ?: 0) + 1
        val parent = existing.maxByOrNull { it.version }?.id
        val seq = store.nextSeq()
        val rec = InputRecord("$streamId@v$version", streamId, version, parent, kind, payload, provenance, seq)
        store.insertInput(rec)
        store.appendEvent(
            if (version == 1) EventType.INPUT_SUBMITTED else EventType.INPUT_REVISED,
            buildJsonObject {
                put("inputId", rec.id); put("streamId", streamId); put("kind", kind.name)
                put("version", version); put("parentId", parent?.let { JsonPrimitive(it) } ?: JsonNull)
                put("provenance", provenance)
            },
            clock.now()
        )
        return rec
    }

    fun latestInput(streamId: String, atSeq: Long = Long.MAX_VALUE): InputRecord? =
        store.inputs(atSeq).filter { it.streamId == streamId }.maxByOrNull { it.version }

    // ---------- calibration ----------

    fun calibrate(measurementStreamId: String, curveStreamId: String, eraStreamId: String, reservoirStreamId: String? = null): DerivedRecord {
        val mRec = latestInput(measurementStreamId) ?: error("no measurement stream $measurementStreamId")
        val cRec = latestInput(curveStreamId) ?: error("no curve stream $curveStreamId")
        val eRec = latestInput(eraStreamId) ?: error("no era stream $eraStreamId")
        val m = JsonFmt.decodeFromJsonElement(MeasurementPayload.serializer(), mRec.payload)
        val c = Curve(JsonFmt.decodeFromJsonElement(CurvePayload.serializer(), cRec.payload))
        val rRec = (reservoirStreamId ?: m.reservoirStreamId)?.let { latestInput(it) }
        val r = rRec?.let { JsonFmt.decodeFromJsonElement(ReservoirPayload.serializer(), it.payload) }

        val excluded = store.inputs().any {
            it.kind == InputKind.EXCLUSION &&
                JsonFmt.decodeFromJsonElement(ExclusionPayload.serializer(), it.payload).let { ex ->
                    ex.measurementStreamId == measurementStreamId && ex.excluded
                }
        }

        val effMean = m.ageBp - (r?.deltaR ?: 0.0)
        val approxCal = invertApprox(c, effMean)
        val gridStart = maxOf(0, approxCal - 600)
        val gridEnd = approxCal + 600
        val result = Calibration.calibrate(m, c, r, gridStart, gridEnd)

        val sourceIds = listOfNotNull(mRec.id, cRec.id, eRec.id, rRec?.id)
        val fp = Calibration.fingerprint(listOf(ALGO_VERSION) + sourceIds + listOf(
            m.ageBp.toString(), m.sigma.toString(), r?.deltaR?.toString(), r?.deltaRSigma?.toString(),
            gridStart.toString(), gridEnd.toString()
        ))
        val payload = CalibrationPayload(
            mRec.id, cRec.id, rRec?.id, eRec.id,
            result.gridStartBp, result.gridEndBp, result.gridStep,
            result.densities, result.peaks, result.intervals, result.diagnostics, excluded
        )
        val rec = DerivedRecord(
            "drv-cal-$fp", DerivedKind.CALIBRATION, ALGO_VERSION, fp, sourceIds,
            JsonFmt.encodeToJsonElement(CalibrationPayload.serializer(), payload).jsonObject,
            store.nextSeq()
        )
        store.insertDerived(rec)
        store.appendEvent(EventType.CALIBRATION_COMPUTED, buildJsonObject {
            put("derivedId", rec.id); put("measurement", mRec.id); put("curve", cRec.id)
            put("reservoir", rRec?.id?.let { JsonPrimitive(it) } ?: JsonNull)
            put("algoVersion", ALGO_VERSION); put("fingerprint", fp)
            put("diagnostics", JsonArray(result.diagnostics.map { JsonPrimitive(it) }))
        }, clock.now())
        return rec
    }

    private fun invertApprox(curve: Curve, c14Age: Double): Int =
        curve.points.minBy { kotlin.math.abs(it.c14Age - c14Age) }.calBp

    // ---------- stratigraphy ----------

    fun checkStratigraphy(stratStreamId: String): List<DerivedRecord> {
        val sRec = latestInput(stratStreamId) ?: error("no stratigraphy stream $stratStreamId")
        val strat = JsonFmt.decodeFromJsonElement(StratigraphyPayload.serializer(), sRec.payload)
        val cals = store.derived().filter { it.kind == DerivedKind.CALIBRATION }
            .map { it to JsonFmt.decodeFromJsonElement(CalibrationPayload.serializer(), it.payload) }
        // latest calibration per measurement stream
        val byStream = mutableMapOf<String, Pair<DerivedRecord, CalibrationPayload>>()
        for ((rec, cal) in cals) {
            val mStream = cal.measurementId.substringBefore("@")
            val cur = byStream[mStream]
            if (cur == null || rec.createdSeq > cur.first.createdSeq) byStream[mStream] = rec to cal
        }
        val calMap = byStream.mapValues { it.value.second }
        val conflicts = Stratigraphy.findConflicts(strat.constraints, calMap)
        val out = mutableListOf<DerivedRecord>()
        for ((constraint, oIv, yIv) in conflicts) {
            val olderCal = byStream.getValue(constraint.olderStreamId)
            val youngerCal = byStream.getValue(constraint.youngerStreamId)
            val srcIds = listOf(sRec.id, olderCal.first.id, youngerCal.first.id)
            val fp = Calibration.fingerprint(listOf("strat-1.0.0") + srcIds + listOf(constraint.olderStreamId, constraint.youngerStreamId))
            val conflictPayload = StratConflictPayload(
                constraint, oIv, yIv,
                "older sample ${constraint.olderStreamId} 95.4% interval ends at ${oIv.toBp} calBP but younger ${constraint.youngerStreamId} starts at ${yIv.fromBp} calBP"
            )
            val conflictRec = DerivedRecord(
                "drv-conflict-$fp", DerivedKind.STRAT_CONFLICT, "strat-1.0.0", fp, srcIds,
                JsonFmt.encodeToJsonElement(StratConflictPayload.serializer(), conflictPayload).jsonObject,
                store.nextSeq()
            )
            store.insertDerived(conflictRec)
            out += conflictRec
            for ((strategy, candPayload) in Stratigraphy.candidatesFor(conflictRec.id, constraint, olderCal.second, youngerCal.second)) {
                val cfp = Calibration.fingerprint(listOf(conflictRec.id, strategy))
                val candRec = DerivedRecord(
                    "drv-cand-$cfp", DerivedKind.STRAT_CANDIDATE, "strat-1.0.0", cfp,
                    srcIds, JsonFmt.encodeToJsonElement(StratCandidatePayload.serializer(), candPayload).jsonObject,
                    store.nextSeq()
                )
                store.insertDerived(candRec)
                out += candRec
            }
        }
        store.appendEvent(EventType.STRAT_CHECKED, buildJsonObject {
            put("stratigraphy", sRec.id)
            put("conflicts", JsonArray(out.filter { it.kind == DerivedKind.STRAT_CONFLICT }.map { JsonPrimitive(it.id) }))
            put("candidates", JsonArray(out.filter { it.kind == DerivedKind.STRAT_CANDIDATE }.map { JsonPrimitive(it.id) }))
        }, clock.now())
        return out
    }

    // ---------- offset comparison ----------

    fun compareOffsets(measurementStreamId: String, curveStreamId: String, eraStreamId: String, reservoirStreamIds: List<String?>): DerivedRecord {
        val rows = reservoirStreamIds.map { rsId ->
            val cal = calibrate(measurementStreamId, curveStreamId, eraStreamId, rsId)
            val calPayload = JsonFmt.decodeFromJsonElement(CalibrationPayload.serializer(), cal.payload)
            val rRec = rsId?.let { latestInput(it) }
            val r = rRec?.let { JsonFmt.decodeFromJsonElement(ReservoirPayload.serializer(), it.payload) }
            OffsetComparisonRow(
                rRec?.id ?: "none", r?.deltaR ?: 0.0, r?.deltaRSigma ?: 0.0, cal.id, calPayload.intervals
            )
        }
        val mRec = latestInput(measurementStreamId)!!
        val srcIds = listOf(mRec.id) + rows.map { it.calibrationId }
        val fp = Calibration.fingerprint(listOf("offsetcmp-1.0.0") + srcIds)
        val rec = DerivedRecord(
            "drv-offcmp-$fp", DerivedKind.OFFSET_COMPARISON, "offsetcmp-1.0.0", fp, srcIds,
            JsonFmt.encodeToJsonElement(OffsetComparisonPayload.serializer(), OffsetComparisonPayload(mRec.id, rows)).jsonObject,
            store.nextSeq()
        )
        store.insertDerived(rec)
        return rec
    }

    // ---------- decisions / undo ----------

    fun decide(derivedId: String, accept: Boolean, reviewer: String, note: String): EventRecord =
        store.appendEvent(
            if (accept) EventType.DECISION_ACCEPT else EventType.DECISION_REJECT,
            buildJsonObject {
                put("derivedId", derivedId); put("reviewer", reviewer); put("note", note)
            }, clock.now()
        )

    fun resolveConflict(derivedId: String, chosenEventId: String, reviewer: String, note: String): EventRecord =
        store.appendEvent(EventType.CONFLICT_RESOLVED, buildJsonObject {
            put("derivedId", derivedId); put("chosenEventId", chosenEventId)
            put("reviewer", reviewer); put("note", note)
        }, clock.now())

    fun adjustParameters(streamId: String, kind: InputKind, payload: JsonObject, provenance: String, reviewer: String): InputRecord {
        val rec = submitInput(kind, streamId, payload, provenance)
        store.appendEvent(EventType.PARAM_ADJUSTED, buildJsonObject {
            put("inputId", rec.id); put("reviewer", reviewer)
        }, clock.now())
        return rec
    }

    fun undo(): EventRecord? {
        val events = store.events()
        val undoneIds = events.filter { it.type == EventType.UNDO }
            .mapNotNull { it.payload["undoneEventId"]?.jsonPrimitive?.content }.toSet()
        val target = events.lastOrNull {
            it.type in setOf(EventType.DECISION_ACCEPT, EventType.DECISION_REJECT, EventType.PARAM_ADJUSTED, EventType.CONFLICT_RESOLVED) && it.id !in undoneIds
        } ?: return null
        return store.appendEvent(EventType.UNDO, buildJsonObject {
            put("undoneEventId", target.id); put("undoneType", target.type.name)
        }, clock.now())
    }

    // ---------- snapshot / replay ----------

    fun snapshot(atSeq: Long = Long.MAX_VALUE): Snapshot {
        val inputs = store.inputs(atSeq)
        val latestByStream = inputs.groupBy { it.streamId }.mapValues { e -> e.value.maxBy { it.version } }
        val derived = store.derived(atSeq).map { rec ->
            val stale = rec.sourceIds.any { sid ->
                val stream = sid.substringBefore("@")
                val ver = sid.substringAfter("@v").toInt()
                (latestByStream[stream]?.version ?: 0) > ver
            }
            DerivedView(rec, if (stale) DerivedStatus.STALE else DerivedStatus.CURRENT)
        }
        val events = store.events(atSeq)
        val undoneIds = events.filter { it.type == EventType.UNDO }
            .mapNotNull { it.payload["undoneEventId"]?.jsonPrimitive?.content }.toSet()
        val resolutions = events.filter { it.type == EventType.CONFLICT_RESOLVED && it.id !in undoneIds }
            .mapNotNull { ev ->
                val d = ev.payload["derivedId"]?.jsonPrimitive?.content
                val c = ev.payload["chosenEventId"]?.jsonPrimitive?.content
                if (d != null && c != null) d to c else null
            }.toMap()
        val decisions = events.filter {
            it.type in setOf(EventType.DECISION_ACCEPT, EventType.DECISION_REJECT)
        }.map { ev ->
            val derivedId = ev.payload["derivedId"]!!.jsonPrimitive.content
            DecisionView(
                ev.id, derivedId, ev.type == EventType.DECISION_ACCEPT,
                ev.payload["reviewer"]?.jsonPrimitive?.content ?: "",
                ev.payload["note"]?.jsonPrimitive?.content ?: "",
                undone = ev.id in undoneIds,
                chosenByResolution = resolutions[derivedId] == ev.id
            )
        }
        return Snapshot(
            atSeq,
            latestByStream.values.sortedBy { it.createdSeq },
            derived.sortedBy { it.record.createdSeq },
            decisions.filter { !it.undone },
            events
        )
    }

    // ---------- export / import ----------

    fun export(): JsonObject {
        val ev = store.appendEvent(EventType.EXPORTED, buildJsonObject { put("by", "workbench") }, clock.now())
        return buildJsonObject {
            put("format", "rcwb-archive/1")
            put("exportedAtEvent", ev.id)
            putJsonArray("inputs") {
                store.inputs().forEach { r ->
                    add(buildJsonObject {
                        put("id", r.id); put("streamId", r.streamId); put("version", r.version)
                        put("parentId", r.parentId?.let { JsonPrimitive(it) } ?: JsonNull)
                        put("kind", r.kind.name); put("payload", r.payload)
                        put("provenance", r.provenance); put("createdSeq", r.createdSeq)
                    })
                }
            }
            putJsonArray("derived") {
                store.derived().forEach { r ->
                    add(buildJsonObject {
                        put("id", r.id); put("kind", r.kind.name); put("algoVersion", r.algoVersion)
                        put("paramFingerprint", r.paramFingerprint)
                        putJsonArray("sourceIds") { r.sourceIds.forEach { add(JsonPrimitive(it)) } }
                        put("payload", r.payload); put("createdSeq", r.createdSeq)
                    })
                }
            }
            putJsonArray("events") {
                store.events().forEach { e ->
                    add(buildJsonObject {
                        put("seq", e.seq); put("id", e.id); put("type", e.type.name)
                        put("payload", e.payload); put("vtime", e.vtime)
                    })
                }
            }
        }
    }

    fun import(archive: JsonObject) {
        require(archive["format"]?.jsonPrimitive?.content == "rcwb-archive/1") { "unsupported archive format" }
        store.clearAll()
        for (el in archive["inputs"]!!.jsonArray) {
            val o = el.jsonObject
            store.insertInput(InputRecord(
                o["id"]!!.jsonPrimitive.content, o["streamId"]!!.jsonPrimitive.content,
                o["version"]!!.jsonPrimitive.int,
                o["parentId"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content,
                InputKind.valueOf(o["kind"]!!.jsonPrimitive.content),
                o["payload"]!!.jsonObject, o["provenance"]!!.jsonPrimitive.content,
                o["createdSeq"]!!.jsonPrimitive.long
            ))
        }
        for (el in archive["derived"]!!.jsonArray) {
            val o = el.jsonObject
            store.insertDerived(DerivedRecord(
                o["id"]!!.jsonPrimitive.content, DerivedKind.valueOf(o["kind"]!!.jsonPrimitive.content),
                o["algoVersion"]!!.jsonPrimitive.content, o["paramFingerprint"]!!.jsonPrimitive.content,
                o["sourceIds"]!!.jsonArray.map { it.jsonPrimitive.content },
                o["payload"]!!.jsonObject, o["createdSeq"]!!.jsonPrimitive.long
            ))
        }
        for (el in archive["events"]!!.jsonArray) {
            val o = el.jsonObject
            store.insertEventAt(EventRecord(
                o["seq"]!!.jsonPrimitive.long, o["id"]!!.jsonPrimitive.content,
                EventType.valueOf(o["type"]!!.jsonPrimitive.content),
                o["payload"]!!.jsonObject, o["vtime"]!!.jsonPrimitive.long
            ))
        }
        store.appendEvent(EventType.IMPORTED, buildJsonObject {
            put("inputs", archive["inputs"]!!.jsonArray.size)
            put("derived", archive["derived"]!!.jsonArray.size)
            put("events", archive["events"]!!.jsonArray.size)
        }, clock.now())
    }
}
