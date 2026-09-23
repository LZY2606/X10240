package gsb

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.time.Instant

enum class DerivedState { CURRENT, STALE }

/** Effective status of a derived record at a given point in history. */
data class DerivedView(val record: DerivedRecord, val status: DerivedState, val staleBecause: List<String>)

data class VerdictInfo(val verdict: String, val reason: String, val actor: String, val eventSeq: Long)

/**
 * Core state machine. Raw inputs are immutable once submitted; revisions create
 * new versions with parent links. Derived values carry algorithm version,
 * parameter fingerprint and direct sources. A local input change only expires
 * results that depend on it; expired results stay viewable but never pose as current.
 */
class Engine(val store: Store, val clock: () -> Instant) {

    private fun now(): String = clock().toString()

    fun nowForEvent(): String = now()

    // ---------- source intake ----------

    class ImportException(message: String) : Exception(message)

    /** Parse and validate the raw representation of a source. Returns normalized
     *  data JSON plus diagnostics. Unconvertible values stay in place (raw kept,
     *  dataJson null) with a diagnostic instead of being dropped. */
    fun normalize(kind: String, raw: String): Pair<String?, List<String>> {
        val diagnostics = mutableListOf<String>()
        val dataJson: String? = try {
            when (kind) {
                SourceKind.MEASUREMENT -> {
                    val d = jsonFmt.decodeFromString<MeasurementData>(raw)
                    if (d.sigma <= 0.0) diagnostics.add("sigma must be positive, got ${d.sigma}")
                    if (d.bpAge < 0.0) diagnostics.add("negative BP age ${d.bpAge}")
                    jsonFmt.encodeToString(MeasurementData.serializer(), d)
                }
                SourceKind.CURVE -> {
                    val d = jsonFmt.decodeFromString<CurveData>(raw)
                    if (d.points.size < 2) diagnostics.add("curve needs at least 2 points")
                    val sorted = d.points.zipWithNext().all { (a, b) -> a.calBp < b.calBp }
                    if (!sorted) diagnostics.add("curve points not sorted by cal BP")
                    jsonFmt.encodeToString(CurveData.serializer(), d)
                }
                SourceKind.OFFSET -> {
                    val d = jsonFmt.decodeFromString<OffsetData>(raw)
                    if (d.deltaRSigma < 0.0) diagnostics.add("negative dR sigma ${d.deltaRSigma}")
                    jsonFmt.encodeToString(OffsetData.serializer(), d)
                }
                SourceKind.ERA_SYSTEM -> {
                    val d = jsonFmt.decodeFromString<EraSystemData>(raw)
                    if (d.convention != "BCE_CE_NO_YEAR_ZERO")
                        diagnostics.add("unknown era convention '${d.convention}'")
                    jsonFmt.encodeToString(EraSystemData.serializer(), d)
                }
                SourceKind.SEQUENCE -> jsonFmt.encodeToString(
                    SequenceData.serializer(), jsonFmt.decodeFromString<SequenceData>(raw))
                SourceKind.EXCLUSION -> jsonFmt.encodeToString(
                    ExclusionData.serializer(), jsonFmt.decodeFromString<ExclusionData>(raw))
                else -> throw ImportException("unknown source kind '$kind'")
            }
        } catch (e: ImportException) {
            throw e
        } catch (e: Exception) {
            diagnostics.add("cannot convert raw value to $kind: ${e.message}")
            null
        }
        return dataJson to diagnostics
    }

    /** Submit a source. First submission is an IMPORT_SOURCE event; a later
     *  submission for the same sourceId is a REVISE_SOURCE event with parent link. */
    fun submitSource(
        kind: String, sourceId: String, unit: String, provenance: String,
        raw: String, actor: String = "reviewer"
    ): SourceVersion {
        if (kind !in SourceKind.ALL) throw ImportException("unknown source kind '$kind'")
        val (dataJson, diagnostics) = normalize(kind, raw)
        val latest = store.latestSources()[sourceId]
        val version = (latest?.version ?: 0) + 1
        val id = "sv-" + sha256("$sourceId|$version|$raw").take(16)
        val eventType = if (latest == null) EventType.IMPORT_SOURCE else EventType.REVISE_SOURCE
        val ev = store.appendEvent(
            eventType, actor, now(),
            jsonFmt.encodeToString(
                mapOf(
                    "sourceId" to sourceId, "kind" to kind, "version" to version.toString(),
                    "parentId" to (latest?.id ?: ""), "sourceVersionId" to id,
                    "diagnostics" to diagnostics.joinToString("; ")
                )
            )
        )
        val sv = SourceVersion(
            id = id, sourceId = sourceId, kind = kind, version = version,
            parentId = latest?.id, unit = unit, provenance = provenance, raw = raw,
            dataJson = dataJson, diagnostics = diagnostics, createdSeq = ev.seq
        )
        store.insertSourceVersion(sv)
        return sv
    }

    // ---------- derivation ----------

    private fun requireData(sv: SourceVersion): String =
        sv.dataJson ?: throw IllegalStateException(
            "source ${sv.sourceId} v${sv.version} has no normalized value: ${sv.diagnostics.joinToString("; ")}"
        )

    /** Calibrate one measurement against chosen curve, offset and era sources.
     *  Idempotent: identical inputs reproduce the identical derived record. */
    fun calibrate(
        measurementSourceId: String, curveSourceId: String,
        offsetSourceId: String?, eraSourceId: String, actor: String = "reviewer"
    ): DerivedRecord {
        val latest = store.latestSources()
        val measSv = latest[measurementSourceId] ?: throw ImportException("no measurement '$measurementSourceId'")
        val curveSv = latest[curveSourceId] ?: throw ImportException("no curve '$curveSourceId'")
        val eraSv = latest[eraSourceId] ?: throw ImportException("no era system '$eraSourceId'")
        val offsetSv = offsetSourceId?.let { latest[it] ?: throw ImportException("no offset '$it'") }

        val meas = jsonFmt.decodeFromString<MeasurementData>(requireData(measSv))
        val curve = jsonFmt.decodeFromString<CurveData>(requireData(curveSv))
        val era = jsonFmt.decodeFromString<EraSystemData>(requireData(eraSv))
        val offset = offsetSv?.let { jsonFmt.decodeFromString<OffsetData>(requireData(it)) }

        val deps = listOfNotNull(measSv.id, curveSv.id, offsetSv?.id, eraSv.id)
        val fingerprint = Calibration.fingerprintOf(
            listOf(Calibration.ALGO_VERSION) + deps.map { id -> "$id:${store.sourceVersionById(id)?.dataJson}" }
        )
        val id = "dr-cal-" + fingerprint.take(16)
        store.derivedById(id)?.let { return it }

        val result = Calibration.calibrate(meas, measurementSourceId, offset, curve, era)
        val ev = store.appendEvent(
            EventType.CALIBRATE, actor, now(),
            jsonFmt.encodeToString(
                mapOf(
                    "derivedId" to id, "measurement" to measurementSourceId,
                    "curve" to curveSv.id, "offset" to (offsetSv?.id ?: "none"),
                    "era" to eraSv.id, "fingerprint" to fingerprint
                )
            )
        )
        val record = DerivedRecord(
            id = id, kind = DerivedKind.CALIBRATION, algoVersion = Calibration.ALGO_VERSION,
            fingerprint = fingerprint, deps = deps,
            payload = jsonFmt.encodeToString(CalibrationResult.serializer(), result),
            createdSeq = ev.seq
        )
        store.insertDerived(record)
        return record
    }

    /** Check one sequence constraint against the latest calibrations of both
     *  measurements. A conflict produces candidate adjustments; the measurement
     *  itself is never rewritten. */
    fun sequenceCheck(constraintSourceId: String, actor: String = "reviewer"): DerivedRecord {
        val latest = store.latestSources()
        val conSv = latest[constraintSourceId] ?: throw ImportException("no constraint '$constraintSourceId'")
        val con = jsonFmt.decodeFromString<SequenceData>(requireData(conSv))

        fun latestCalibrationFor(sourceId: String): DerivedRecord? =
            store.derived().filter { it.kind == DerivedKind.CALIBRATION }
                .map { it to jsonFmt.decodeFromString(CalibrationResult.serializer(), it.payload) }
                .filter { (_, r) -> r.measurementSourceId == sourceId }
                .filter { (d, _) -> derivedStatus(d).status == DerivedState.CURRENT }
                .maxByOrNull { it.first.createdSeq }?.first

        val beforeCal = latestCalibrationFor(con.beforeSourceId)
            ?: throw IllegalStateException("no current calibration for ${con.beforeSourceId}")
        val afterCal = latestCalibrationFor(con.afterSourceId)
            ?: throw IllegalStateException("no current calibration for ${con.afterSourceId}")
        val beforeRes = jsonFmt.decodeFromString(CalibrationResult.serializer(), beforeCal.payload)
        val afterRes = jsonFmt.decodeFromString(CalibrationResult.serializer(), afterCal.payload)

        val p = Calibration.pOlder(beforeRes, afterRes)
        val conflict = p < 0.5

        val candidates = mutableListOf<SequenceCandidate>()
        if (conflict) {
            val exclusions = latest.values.filter { it.kind == SourceKind.EXCLUSION }
                .mapNotNull { it.dataJson?.let { dj -> jsonFmt.decodeFromString<ExclusionData>(dj) } }
                .filter { it.excluded && (it.targetSourceId == con.beforeSourceId || it.targetSourceId == con.afterSourceId) }
            exclusions.forEach {
                candidates.add(SequenceCandidate("APPLY_EXCLUSION", "apply recorded exclusion of ${it.targetSourceId}: ${it.reason}"))
            }
            latest.values.filter { it.kind == SourceKind.OFFSET }.forEach { off ->
                candidates.add(SequenceCandidate("RERUN_WITH_OFFSET", "re-calibrate both under offset assumption ${off.sourceId}"))
            }
            candidates.add(SequenceCandidate("REQUEST_REMEASUREMENT", "request re-measurement; stored measurements stay unchanged"))
            candidates.add(SequenceCandidate("KEEP_BOTH_CANDIDATES", "retain both orderings as legal candidates; no unique decision"))
        }
        val status = when {
            !conflict -> "OK"
            candidates.size > 1 -> "CONFLICT_INDETERMINATE"
            else -> "CONFLICT"
        }
        val deps = listOf(conSv.id, beforeCal.id, afterCal.id)
        val fingerprint = Calibration.fingerprintOf(listOf("seqcheck-1.0") + deps)
        val id = "dr-seq-" + fingerprint.take(16)
        store.derivedById(id)?.let { return it }

        val result = SequenceCheckResult(
            constraintSourceId = constraintSourceId,
            beforeSourceId = con.beforeSourceId, afterSourceId = con.afterSourceId,
            beforeCalibrationId = beforeCal.id, afterCalibrationId = afterCal.id,
            pOrder = p, conflict = conflict, status = status, candidates = candidates,
            note = if (conflict) "P(${con.beforeSourceId} older)=${"%.3f".format(p)} contradicts constraint; candidates proposed, measurements untouched"
            else "constraint consistent with calibrated distributions"
        )
        val ev = store.appendEvent(
            EventType.SEQUENCE_CHECK, actor, now(),
            jsonFmt.encodeToString(mapOf("derivedId" to id, "constraint" to constraintSourceId, "status" to status))
        )
        val record = DerivedRecord(
            id = id, kind = DerivedKind.SEQUENCE_CHECK, algoVersion = "seqcheck-1.0",
            fingerprint = fingerprint, deps = deps,
            payload = jsonFmt.encodeToString(SequenceCheckResult.serializer(), result),
            createdSeq = ev.seq
        )
        store.insertDerived(record)
        return record
    }

    // ---------- adjudication & undo ----------

    fun adjudicate(derivedId: String, verdict: String, reason: String, actor: String): EventRecord {
        require(verdict in listOf(Verdict.ACCEPTED, Verdict.REJECTED, Verdict.PARAM_ADJUST))
        require(store.derivedById(derivedId) != null) { "no derived record $derivedId" }
        return store.appendEvent(
            EventType.ADJUDICATE, actor, now(),
            jsonFmt.encodeToString(AdjudicatePayload(derivedId, verdict, reason))
        )
    }

    fun undo(targetSeq: Long, reason: String, actor: String): EventRecord {
        val target = store.events().firstOrNull { it.seq == targetSeq }
            ?: throw IllegalArgumentException("no event seq=$targetSeq")
        require(target.type == EventType.ADJUDICATE) { "only adjudication events can be undone, got ${target.type}" }
        return store.appendEvent(EventType.UNDO, actor, now(), jsonFmt.encodeToString(UndoPayload(targetSeq, reason)))
    }

    /** Effective verdicts as of [asOfSeq], honouring undo events. */
    fun verdicts(asOfSeq: Long = Long.MAX_VALUE): Map<String, VerdictInfo> {
        val events = store.events(asOfSeq)
        val undone = events.filter { it.type == EventType.UNDO }
            .map { jsonFmt.decodeFromString<UndoPayload>(it.payload).targetSeq }.toSet()
        val out = mutableMapOf<String, VerdictInfo>()
        for (ev in events) {
            if (ev.type != EventType.ADJUDICATE || ev.seq in undone) continue
            val p = jsonFmt.decodeFromString<AdjudicatePayload>(ev.payload)
            out[p.derivedId] = VerdictInfo(p.verdict, p.reason, ev.actor, ev.seq)
        }
        return out
    }

    // ---------- staleness ----------

    /** A derived record is stale when any direct source dependency is no longer
     *  the latest version of its source. Only dependents of changed inputs expire. */
    fun derivedStatus(record: DerivedRecord, asOfSeq: Long = Long.MAX_VALUE): DerivedView {
        val latest = store.latestSources(asOfSeq)
        val byId = store.sourceVersions(asOfSeq).associateBy { it.id }
        val staleBecause = record.deps.mapNotNull { depId ->
            val sv = byId[depId] ?: return@mapNotNull null
            val cur = latest[sv.sourceId]
            if (cur != null && cur.id != depId) "${sv.sourceId} v${sv.version} -> v${cur.version}" else null
        }
        return DerivedView(record, if (staleBecause.isEmpty()) DerivedState.CURRENT else DerivedState.STALE, staleBecause)
    }

    fun derivedViews(asOfSeq: Long = Long.MAX_VALUE): List<DerivedView> =
        store.derived(asOfSeq).map { derivedStatus(it, asOfSeq) }

    // ---------- export / import ----------

    fun exportToString(actor: String = "reviewer"): String {
        store.appendEvent(EventType.EXPORT, actor, now(), "{}")
        return store.exportJson()
    }

    fun importFromString(text: String, actor: String = "reviewer"): ExportBundle {
        val bundle = store.importJson(text)
        store.appendEvent(
            EventType.IMPORT_FILE, actor, now(),
            jsonFmt.encodeToString(mapOf("importedEvents" to bundle.events.size.toString()))
        )
        return bundle
    }
}
