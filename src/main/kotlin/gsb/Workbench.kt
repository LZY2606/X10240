package gsb

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/** Loads fixed-version calibration curves shipped inside the repo (offline case pack). */
class CurveBank(private val casepackDir: File) {
    private val cache = HashMap<String, Curve>()
    fun load(fileName: String): Curve = cache.getOrPut(fileName) {
        val f = File(casepackDir, "curves/$fileName")
        require(f.isFile) { "curve file not found: ${f.path}" }
        val o = Json.parseToJsonElement(f.readText()).jsonObject
        val pts = o["points"]!!.jsonArray.map { p ->
            val a = p.jsonArray
            CurvePoint(a[0].jsonPrimitive.content.toInt(), a[1].jsonPrimitive.double, a[2].jsonPrimitive.double)
        }
        Curve(o["curveId"]!!.jsonPrimitive.content, o["version"]!!.jsonPrimitive.content, pts)
    }
}

class Workbench(val store: Store, val casepackDir: File) {
    val curves = CurveBank(casepackDir)
    var state: ProjectState = ProjectState.replay(store.events())
        private set

    @Synchronized
    fun append(type: String, payload: JsonObject): Event {
        val e = store.append(type, payload)
        state.apply(e)
        return e
    }

    /** Import (or revise) a provenance-bearing source. Raw representation is kept verbatim. */
    @Synchronized
    fun importSource(kind: String, stream: String, raw: JsonObject, units: JsonObject, provenance: JsonObject): Event {
        val existing = state.sources.values.filter { it.kind == kind && it.stream == stream }
        val version = (existing.maxOfOrNull { it.version } ?: 0) + 1
        val parent = existing.firstOrNull { it.version == version - 1 }?.sourceId
        val sourceId = stableId("src", kind, stream, version.toString(), canonical(raw))
        val payload = buildJsonObject {
            put("sourceId", JsonPrimitive(sourceId))
            put("kind", JsonPrimitive(kind))
            put("stream", JsonPrimitive(stream))
            put("version", JsonPrimitive(version))
            put("parent", parent?.let { JsonPrimitive(it) } ?: JsonPrimitive(null as String?))
            put("raw", raw)
            put("units", units)
            put("provenance", provenance)
        }
        val e = append(if (version > 1) "source.revised" else "source.imported", payload)
        if (version > 1) expireDependents(existing.map { it.sourceId }, "source revised: $kind/$stream -> v$version", e.id)
        if (kind == "stratConstraint") detectStratConflicts(e.id)
        return e
    }

    /** Local change: only derived results that directly name one of the expired source ids become stale. */
    private fun expireDependents(oldSourceIds: List<String>, reason: String, causeEventId: String) {
        val affected = state.derived.values.filter { d ->
            d.status == "current" && d.inputs.values.any { it in oldSourceIds }
        }.map { it.derivedId }
        if (affected.isEmpty()) return
        append("result.expired", buildJsonObject {
            put("derivedIds", JsonArray(affected.map { JsonPrimitive(it) }))
            put("reason", JsonPrimitive(reason))
            put("causeEventId", JsonPrimitive(causeEventId))
        })
    }

    /** Deterministic calibration. Idempotent: identical inputs reuse the existing derived result. */
    @Synchronized
    fun compute(measurementSourceId: String, curveSourceId: String, offsetSourceId: String, eraSourceId: String, step: Int = 1): Derived {
        val meas = state.sources[measurementSourceId] ?: error("unknown measurement $measurementSourceId")
        val curveSrc = state.sources[curveSourceId] ?: error("unknown curve $curveSourceId")
        val offSrc = state.sources[offsetSourceId] ?: error("unknown offset $offsetSourceId")
        require(state.sources.containsKey(eraSourceId)) { "unknown era system $eraSourceId" }
        val curve = curves.load(curveSrc.raw["file"]!!.jsonPrimitive.content)
        val measAge = meas.raw["c14AgeBP"]!!.jsonPrimitive.double
        val measSigma = meas.raw["sigma"]!!.jsonPrimitive.double
        val offMu = offSrc.raw["mu"]!!.jsonPrimitive.double
        val offSigma = offSrc.raw["sigma"]!!.jsonPrimitive.double
        val params = Calibrator.paramsOf(measAge, measSigma, offMu, offSigma, Calibrator.curveHashOf(curve), step)
        val fp = Calibrator.fingerprint(params)
        val derivedId = stableId("cal", Calibrator.ALGORITHM_VERSION, fp,
            measurementSourceId, curveSourceId, offsetSourceId, eraSourceId)
        state.derived[derivedId]?.let { return it }
        val out = Calibrator.calibrate(measAge, measSigma, offMu, offSigma, curve, step)
        val payload = buildJsonObject {
            put("derivedId", JsonPrimitive(derivedId))
            put("algorithmVersion", JsonPrimitive(Calibrator.ALGORITHM_VERSION))
            put("paramFingerprint", JsonPrimitive(fp))
            put("params", params)
            put("inputs", buildJsonObject {
                put("measurement", JsonPrimitive(measurementSourceId))
                put("curve", JsonPrimitive(curveSourceId))
                put("offset", JsonPrimitive(offsetSourceId))
                put("era", JsonPrimitive(eraSourceId))
            })
            put("grid", buildJsonObject {
                put("loBp", JsonPrimitive(out.loBp)); put("hiBp", JsonPrimitive(out.hiBp)); put("step", JsonPrimitive(out.step))
            })
            put("peaks", JsonArray(out.peaks.map { p ->
                buildJsonObject {
                    put("apexBp", JsonPrimitive(p.apexBp)); put("loBp", JsonPrimitive(p.loBp))
                    put("hiBp", JsonPrimitive(p.hiBp)); put("mass", JsonPrimitive(p.mass))
                }
            }))
            put("intervals", JsonArray(out.intervals.map { iv ->
                buildJsonObject {
                    put("level", JsonPrimitive(iv.level)); put("loBp", JsonPrimitive(iv.loBp)); put("hiBp", JsonPrimitive(iv.hiBp))
                    put("prob", JsonPrimitive(iv.prob)); put("loLabel", JsonPrimitive(iv.loLabel))
                    put("hiLabel", JsonPrimitive(iv.hiLabel)); put("evidence", iv.evidence)
                }
            }))
            put("diagnostics", JsonArray(out.diagnostics.map { JsonPrimitive(it) }))
            put("density", JsonArray(out.density.map { JsonPrimitive(it) }))
        }
        append("calibration.computed", payload)
        return state.derived.getValue(derivedId)
    }

    @Synchronized
    fun adjudicate(derivedId: String, intervalIndex: Int, decision: String, reason: String, reviewer: String): Event {
        require(state.derived.containsKey(derivedId)) { "unknown derived $derivedId" }
        require(decision in setOf("accepted", "rejected")) { "decision must be accepted|rejected" }
        return append("interval.adjudicated", buildJsonObject {
            put("derivedId", JsonPrimitive(derivedId))
            put("intervalIndex", JsonPrimitive(intervalIndex))
            put("decision", JsonPrimitive(decision))
            put("reason", JsonPrimitive(reason))
            put("reviewer", JsonPrimitive(reviewer))
        })
    }

    /** Case-pack friendly: resolve the current derived result for a measurement stream, then adjudicate. */
    @Synchronized
    fun adjudicateByStream(stream: String, intervalIndex: Int, decision: String, reason: String, reviewer: String): Event {
        val d = state.currentDerivedFor(stream).filter { it.status == "current" }.maxByOrNull { it.ts }
            ?: error("no current derived result for stream $stream")
        return adjudicate(d.derivedId, intervalIndex, decision, reason, reviewer)
    }

    @Synchronized
    fun undo(targetEventId: String, reason: String): Event {
        require(state.events.any { it.id == targetEventId }) { "unknown event $targetEventId" }
        return append("decision.undone", buildJsonObject {
            put("targetEventId", JsonPrimitive(targetEventId))
            put("reason", JsonPrimitive(reason))
        })
    }

    @Synchronized
    fun adjudicateCandidate(candidateId: String, decision: String, reason: String, reviewer: String): Event {
        require(state.candidates.containsKey(candidateId)) { "unknown candidate $candidateId" }
        return append("candidate.adjudicated", buildJsonObject {
            put("candidateId", JsonPrimitive(candidateId))
            put("decision", JsonPrimitive(decision))
            put("reason", JsonPrimitive(reason))
            put("reviewer", JsonPrimitive(reviewer))
        })
    }

    /** Cycle detection over non-excluded stratigraphic constraints. Produces candidate adjustments; never rewrites measurements. */
    private fun detectStratConflicts(causeEventId: String) {
        val constraints = state.sourcesOfKind("stratConstraint").filter { !it.excluded }
        val edges = constraints.map { it.raw["older"]!!.jsonPrimitive.content to it.raw["younger"]!!.jsonPrimitive.content }
        val adj = HashMap<String, MutableList<String>>()
        edges.forEach { (a, b) -> adj.getOrPut(a) { mutableListOf() }.add(b) }
        val cycle = findCycle(adj) ?: return
        val conflictId = stableId("conflict", cycle.joinToString(">"))
        if (state.conflicts.containsKey(conflictId)) return
        val inCycle = constraints.filter {
            val o = it.raw["older"]!!.jsonPrimitive.content
            val y = it.raw["younger"]!!.jsonPrimitive.content
            o in cycle && y in cycle
        }
        val cands = mutableListOf<JsonObject>()
        for (c in inCycle) {
            cands += buildJsonObject {
                put("candidateId", JsonPrimitive(stableId("cand", conflictId, "exclude-constraint", c.sourceId)))
                put("action", JsonPrimitive("exclude-constraint"))
                put("target", JsonPrimitive(c.sourceId))
                put("rationale", JsonPrimitive(
                    "excluding constraint ${c.stream} (${c.raw["older"]!!.jsonPrimitive.content} older-than " +
                        "${c.raw["younger"]!!.jsonPrimitive.content}) breaks the cycle ${cycle.joinToString(" > ")}"))
            }
        }
        for (m in cycle) {
            cands += buildJsonObject {
                put("candidateId", JsonPrimitive(stableId("cand", conflictId, "flag-outlier", m)))
                put("action", JsonPrimitive("flag-outlier"))
                put("target", JsonPrimitive(m))
                put("rationale", JsonPrimitive("flag measurement $m as potential outlier; the measurement itself is NOT modified"))
            }
        }
        append("strat.conflict.detected", buildJsonObject {
            put("conflictId", JsonPrimitive(conflictId))
            put("cycle", JsonArray(cycle.map { JsonPrimitive(it) }))
            put("constraintIds", JsonArray(inCycle.map { JsonPrimitive(it.sourceId) }))
            put("candidates", JsonArray(cands))
            put("causeEventId", JsonPrimitive(causeEventId))
        })
    }

    private fun findCycle(adj: Map<String, List<String>>): List<String>? {
        val color = HashMap<String, Int>() // 0=unvisited 1=in-stack 2=done
        val stack = ArrayDeque<String>()
        fun dfs(u: String): List<String>? {
            color[u] = 1
            stack.addLast(u)
            for (v in adj[u].orEmpty()) {
                when (color[v] ?: 0) {
                    1 -> {
                        val idx = stack.indexOf(v)
                        return stack.drop(idx)
                    }
                    0 -> dfs(v)?.let { return it }
                }
            }
            stack.removeLast()
            color[u] = 2
            return null
        }
        for (u in adj.keys) if ((color[u] ?: 0) == 0) dfs(u)?.let { return it }
        return null
    }

    /** Apply one step file of the offline case pack (or a user-pasted batch of the same shape). */
    @Synchronized
    fun applyBatch(batch: JsonObject): List<Event> {
        val out = mutableListOf<Event>()
        for (el in batch["events"]!!.jsonArray) {
            val o = el.jsonObject
            when (val type = o["type"]!!.jsonPrimitive.content) {
                "source.imported" -> out += importSource(
                    o["kind"]!!.jsonPrimitive.content,
                    o["stream"]!!.jsonPrimitive.content,
                    o["raw"]!!.jsonObject,
                    o["units"]!!.jsonObject,
                    o["provenance"]!!.jsonObject,
                )
                "interval.adjudicated" -> out += adjudicateByStream(
                    o["stream"]!!.jsonPrimitive.content,
                    o["intervalIndex"]!!.jsonPrimitive.content.toInt(),
                    o["decision"]!!.jsonPrimitive.content,
                    o["reason"]!!.jsonPrimitive.content,
                    o["reviewer"]!!.jsonPrimitive.content,
                )
                "calibration.compute" -> {
                    val meas = state.latestSource("measurement", o["stream"]!!.jsonPrimitive.content)
                        ?: error("unknown measurement stream")
                    val curve = state.latestSource("curveVersion", o["curveStream"]!!.jsonPrimitive.content)
                        ?: error("unknown curve stream")
                    val offset = state.latestSource("offsetDistribution", o["offsetStream"]!!.jsonPrimitive.content)
                        ?: error("unknown offset stream")
                    val era = state.sourcesOfKind("eraSystem").maxByOrNull { it.version }
                        ?: error("no era system imported")
                    val before = state.events.size
                    compute(meas.sourceId, curve.sourceId, offset.sourceId, era.sourceId)
                    if (state.events.size > before) out += state.events.last()
                }
                else -> out += append(type, o["payload"]!!.jsonObject)
            }
        }
        return out
    }

    fun applyCasepackStep(name: String): List<Event> {
        val f = File(casepackDir, "$name.json")
        require(f.isFile) { "case pack step not found: ${f.path}" }
        return applyBatch(Json.parseToJsonElement(f.readText()).jsonObject)
    }

    fun casepackSteps(): List<String> =
        casepackDir.listFiles { f -> f.name.matches(Regex("\\d+-.*\\.json")) }?.map { it.name.removeSuffix(".json") }?.sorted()
            ?: emptyList()

    fun exportJson(): JsonObject = store.exportJson()

    /** Wipe the local database and re-import a previously exported log. Identity and order are preserved. */
    @Synchronized
    fun importLog(json: JsonObject): Int {
        val evts = Store.parseExport(json)
        store.replaceAll(evts)
        state = ProjectState.replay(store.events())
        append("project.imported", buildJsonObject {
            put("count", JsonPrimitive(evts.size))
            put("head", JsonPrimitive(evts.lastOrNull()?.id ?: GENESIS))
        })
        return evts.size
    }
}
