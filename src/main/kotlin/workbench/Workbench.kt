package workbench

import kotlinx.serialization.json.*

class Workbench(val store: Store) {

    // ---------- source submission (immutable; revisions are new versions) ----------

    fun submitSource(kind: String, stream: String, payload: JsonObject, provenance: String): SourceRow {
        val oldVersion = store.latestVersion(stream)
        val row = store.submitSource(kind, stream, payload, provenance)
        if (kind == "measurement") {
            val unit = payload["unit"]?.jsonPrimitive?.contentOrNull
            if (unit != "BP") {
                store.recordEvent("DIAGNOSTIC", buildJsonObject {
                    put("code", "UNCONVERTIBLE_UNIT")
                    put("stream", stream)
                    put("unit", unit ?: "<missing>")
                    put("detail", "value kept in raw form at source; no normalization attempted")
                })
            }
        }
        if (kind == "curve" && oldVersion > 0) {
            val affected = store.derivedForStream(stream).filter { it.status == "STALE" }.map { it.id }
            store.recordEvent("CURVE_UPGRADED", buildJsonObject {
                put("stream", stream)
                put("fromVersion", oldVersion)
                put("toVersion", row.version)
                putJsonArray("staleDerived") { affected.forEach { add(it) } }
                put("basis", "curve revision invalidates only derived values that list this curve stream as a direct source")
            })
        }
        return row
    }

    // ---------- calibration ----------

    fun calibrate(measurementStream: String, curveStream: String, offsetStream: String?): DerivedRow? {
        val m = store.latestSource(measurementStream) ?: return null
        val mp = json.parseToJsonElement(m.payload).jsonObject
        if (mp["unit"]?.jsonPrimitive?.contentOrNull != "BP") return null
        val c = store.latestSource(curveStream) ?: return null
        val curve = json.decodeFromString<CalibrationCurve>(
            json.parseToJsonElement(c.payload).jsonObject.toString())
        val rawAge = mp["c14Age"]!!.jsonPrimitive.double
        val rawSigma = mp["sigma"]!!.jsonPrimitive.double
        val offsetRow = offsetStream?.let { store.latestSource(it) }
        val (effAge, effSigma) = if (offsetRow != null) {
            val off = json.decodeFromString<OffsetDistribution>(offsetRow.payload)
            Calibrator.applyOffset(rawAge, rawSigma, off)
        } else rawAge to rawSigma
        val output = Calibrator.calibrate(effAge, effSigma, curve)
        val streams = listOfNotNull(measurementStream, curveStream, offsetStream)
        val fp = fingerprintOf(ALGORITHM_VERSION,
            "${m.id}@${m.version}", "${c.id}@${c.version}",
            offsetRow?.let { "${it.id}@${it.version}" } ?: "no-offset")
        val payload = buildJsonObject {
            put("measurement", measurementStream)
            put("curve", curveStream); put("curveVersion", c.version)
            put("offset", offsetStream); put("offsetVersion", offsetRow?.version)
            put("rawC14Age", rawAge); put("rawSigma", rawSigma)
            put("output", json.parseToJsonElement(json.encodeToString(CalibrationOutput.serializer(), output)))
        }
        val derived = store.recordDerived("calibration", streams, fp, payload)
        store.recordEvent("CALIBRATION_COMPUTED", buildJsonObject {
            put("derivedId", derived.id)
            put("measurement", measurementStream)
            put("curve", curveStream); put("curveVersion", c.version)
            put("offset", offsetStream)
            put("fingerprint", fp)
            put("normalizedMass", output.normalizedMass)
            put("edgeInterpolated", output.edgeInterpolated)
            put("intervalCount954", output.intervals954.size)
            put("basis", "multi-modal posterior kept as normalized grid; no mean year computed; interpolation linear on fixed curve version ${c.version}")
        })
        if (output.edgeInterpolated) {
            store.recordEvent("DIAGNOSTIC", buildJsonObject {
                put("code", "EDGE_INTERPOLATION")
                put("derivedId", derived.id)
                put("detail", "posterior mass reaches curve boundary; edge clamping applied, interval may be truncated")
            })
        }
        return derived
    }

    // ---------- sequence assessment ----------

    fun assessSequence(constraintStream: String): DerivedRow? {
        val cs = store.latestSource(constraintStream) ?: return null
        val cp = json.parseToJsonElement(cs.payload).jsonObject
        val below = cp["below"]!!.jsonPrimitive.content
        val above = cp["above"]!!.jsonPrimitive.content
        val belowCal = latestCalibrationFor(below) ?: return null
        val aboveCal = latestCalibrationFor(above) ?: return null
        val belowOut = outputOf(belowCal); val aboveOut = outputOf(aboveCal)
        val p = Calibrator.probabilityOlder(belowOut.cells, aboveOut.cells)
        val conflict = p < 0.5
        val candidates = if (conflict) listOf(
            ConflictCandidate("EXCLUDE_AS_OUTLIER", below, "treat lower sample $below as intrusive/outlier; measurement NOT modified"),
            ConflictCandidate("EXCLUDE_AS_OUTLIER", above, "treat upper sample $above as residual/outlier; measurement NOT modified"),
            ConflictCandidate("WIDEN_UNCERTAINTY", below, "inflate sigma of $below and recompute as candidate branch"),
            ConflictCandidate("ACCEPT_PHASE_OVERLAP", constraintStream, "accept overlap of 95.4% intervals as a phase boundary")
        ) else emptyList()
        val assessment = SequenceAssessment(constraintStream, below, above, p, conflict, candidates)
        val derived = store.recordDerived("sequence-assessment",
            listOf(constraintStream, below, above),
            fingerprintOf(ALGORITHM_VERSION, belowCal.id, aboveCal.id, "${cs.id}@${cs.version}"),
            buildJsonObject { put("assessment", json.parseToJsonElement(json.encodeToString(SequenceAssessment.serializer(), assessment))) })
        if (conflict) {
            store.recordEvent("SEQUENCE_CONFLICT", buildJsonObject {
                put("assessmentId", derived.id)
                put("constraint", constraintStream)
                put("probabilityBelowOlder", p)
                putJsonArray("candidates") { candidates.forEach { c ->
                    add(buildJsonObject { put("type", c.type); put("target", c.targetStream); put("detail", c.detail) }) } }
                put("basis", "constraint says $below older than $above but posterior probability is $p; candidates proposed, measurements unchanged")
            })
        }
        return derived
    }

    fun latestCalibrationFor(measurementStream: String): DerivedRow? =
        store.derivedForStream(measurementStream)
            .filter { it.kind == "calibration" && it.status == "CURRENT" }
            .maxByOrNull { it.seq }

    fun outputOf(derived: DerivedRow): CalibrationOutput =
        json.decodeFromString(CalibrationOutput.serializer(),
            json.parseToJsonElement(derived.payload).jsonObject["output"]!!.toString())

    // ---------- decisions ----------

    fun decide(derivedId: String, action: String, rationale: String, reviewer: String, provenance: String): EventRow =
        store.recordEvent("DECISION_$action", buildJsonObject {
            put("targetDerived", derivedId)
            put("action", action)
            put("rationale", rationale)
            put("reviewer", reviewer)
            put("provenance", provenance)
        })

    fun undoDecision(decisionEventId: String, reviewer: String): EventRow? {
        val original = store.eventById(decisionEventId) ?: return null
        if (!original.type.startsWith("DECISION_") || original.type == "DECISION_UNDO") return null
        return store.recordEvent("DECISION_UNDO", buildJsonObject {
            put("undoes", decisionEventId)
            put("reviewer", reviewer)
        })
    }

    /** Effective decisions per derived target, honoring undo. */
    fun effectiveDecisions(): Map<String, EventRow> {
        val undone = store.events().filter { it.type == "DECISION_UNDO" }
            .mapNotNull { json.parseToJsonElement(it.payload).jsonObject["undoes"]?.jsonPrimitive?.content }.toSet()
        val out = mutableMapOf<String, EventRow>()
        for (e in store.events()) {
            if (!e.type.startsWith("DECISION_") || e.type == "DECISION_UNDO" || e.id in undone) continue
            val target = json.parseToJsonElement(e.payload).jsonObject["targetDerived"]?.jsonPrimitive?.content ?: continue
            out[target] = e
        }
        return out
    }
}
