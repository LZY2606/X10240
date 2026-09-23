package gsb

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class Source(
    val sourceId: String,
    val kind: String,
    val stream: String,
    val version: Int,
    val parent: String?,
    val raw: JsonObject,
    val provenance: JsonObject,
    val eventId: String,
    val ts: String,
    var supersededBy: String? = null,
    var excluded: Boolean = false,
)

data class Derived(
    val derivedId: String,
    val algorithmVersion: String,
    val paramFingerprint: String,
    val inputs: Map<String, String>,
    val payload: JsonObject,
    val eventId: String,
    val ts: String,
    var status: String = "current", // current | stale
    var staleReason: String? = null,
)

data class Adjudication(
    val key: String, // "$derivedId#$intervalIndex"
    val decision: String, // accepted | rejected
    val reason: String,
    val reviewer: String,
    val eventId: String,
    val ts: String,
)

data class Candidate(
    val candidateId: String,
    val conflictId: String,
    val action: String, // exclude-constraint | flag-outlier
    val target: String,
    val rationale: String,
    var decision: String? = null, // accepted | rejected | null = open
    var decisionEventId: String? = null,
)

data class Conflict(
    val conflictId: String,
    val cycle: List<String>,
    val constraintIds: List<String>,
    val eventId: String,
    var resolved: Boolean = false,
)

/** Fold of the event log: the complete review state at a point in history. */
class ProjectState {
    val sources = LinkedHashMap<String, Source>()
    val derived = LinkedHashMap<String, Derived>()
    val adjudications = LinkedHashMap<String, Adjudication>()
    val candidates = LinkedHashMap<String, Candidate>()
    val conflicts = LinkedHashMap<String, Conflict>()
    val undoneEvents = HashSet<String>()
    val adjudicationHistory = mutableListOf<Adjudication>()
    val events = mutableListOf<Event>()
    val expiredLog = mutableListOf<JsonObject>()

    fun apply(e: Event) {
        events += e
        if (e.type == "decision.undone") {
            val target = e.payload["targetEventId"]!!.jsonPrimitive.content
            undoneEvents += target
            adjudicationHistory.removeIf { it.eventId == target }
            adjudications.clear()
            adjudicationHistory.forEach { adjudications[it.key] = it }
            candidates.values.forEach { c ->
                if (c.decisionEventId == target) { c.decision = null; c.decisionEventId = null }
            }
            return
        }
        if (e.id in undoneEvents) return
        when (e.type) {
            "source.imported", "source.revised" -> {
                val s = Source(
                    sourceId = e.payload["sourceId"]!!.jsonPrimitive.content,
                    kind = e.payload["kind"]!!.jsonPrimitive.content,
                    stream = e.payload["stream"]!!.jsonPrimitive.content,
                    version = e.payload["version"]!!.jsonPrimitive.content.toInt(),
                    parent = e.payload["parent"]?.jsonPrimitive?.content,
                    raw = e.payload["raw"]!!.jsonObject,
                    provenance = e.payload["provenance"]!!.jsonObject,
                    eventId = e.id,
                    ts = e.ts,
                )
                s.parent?.let { p -> sources[p]?.let { if (it.stream == s.stream) it.supersededBy = s.sourceId } }
                sources[s.sourceId] = s
            }
            "calibration.computed" -> {
                val inputs = e.payload["inputs"]!!.jsonObject.entries
                    .associate { it.key to it.value.jsonPrimitive.content }
                derived[e.payload["derivedId"]!!.jsonPrimitive.content] = Derived(
                    derivedId = e.payload["derivedId"]!!.jsonPrimitive.content,
                    algorithmVersion = e.payload["algorithmVersion"]!!.jsonPrimitive.content,
                    paramFingerprint = e.payload["paramFingerprint"]!!.jsonPrimitive.content,
                    inputs = inputs,
                    payload = e.payload,
                    eventId = e.id,
                    ts = e.ts,
                )
            }
            "result.expired" -> {
                expiredLog += e.payload
                for (idEl in e.payload["derivedIds"]!!.jsonArray) {
                    val id = idEl.jsonPrimitive.content
                    derived[id]?.let {
                        if (it.status == "current") {
                            it.status = "stale"
                            it.staleReason = e.payload["reason"]!!.jsonPrimitive.content
                        }
                    }
                }
            }
            "interval.adjudicated" -> {
                val key = e.payload["derivedId"]!!.jsonPrimitive.content +
                    "#" + e.payload["intervalIndex"]!!.jsonPrimitive.content
                val adj = Adjudication(
                    key = key,
                    decision = e.payload["decision"]!!.jsonPrimitive.content,
                    reason = e.payload["reason"]!!.jsonPrimitive.content,
                    reviewer = e.payload["reviewer"]!!.jsonPrimitive.content,
                    eventId = e.id,
                    ts = e.ts,
                )
                adjudicationHistory += adj
                adjudications[key] = adj
            }
            "strat.conflict.detected" -> {
                val cid = e.payload["conflictId"]!!.jsonPrimitive.content
                conflicts[cid] = Conflict(
                    conflictId = cid,
                    cycle = e.payload["cycle"]!!.jsonArray.map { it.jsonPrimitive.content },
                    constraintIds = e.payload["constraintIds"]!!.jsonArray.map { it.jsonPrimitive.content },
                    eventId = e.id,
                )
                for (cEl in e.payload["candidates"]!!.jsonArray) {
                    val c = cEl.jsonObject
                    candidates[c["candidateId"]!!.jsonPrimitive.content] = Candidate(
                        candidateId = c["candidateId"]!!.jsonPrimitive.content,
                        conflictId = cid,
                        action = c["action"]!!.jsonPrimitive.content,
                        target = c["target"]!!.jsonPrimitive.content,
                        rationale = c["rationale"]!!.jsonPrimitive.content,
                    )
                }
            }
            "candidate.adjudicated" -> {
                val cid = e.payload["candidateId"]!!.jsonPrimitive.content
                candidates[cid]?.let { c ->
                    c.decision = e.payload["decision"]!!.jsonPrimitive.content
                    c.decisionEventId = e.id
                    if (c.decision == "accepted" && c.action == "exclude-constraint") {
                        sources[c.target]?.excluded = true
                        conflicts[c.conflictId]?.resolved = true
                    }
                }
            }
            else -> { /* project.created, params.adjusted, project.imported: kept in the log only */ }
        }
    }

    fun latestSource(kind: String, stream: String): Source? =
        sources.values.filter { it.kind == kind && it.stream == stream }.maxByOrNull { it.version }

    fun sourcesOfKind(kind: String): List<Source> = sources.values.filter { it.kind == kind }

    fun currentDerivedFor(stream: String): List<Derived> =
        derived.values.filter { it.inputs["measurement"]?.let { id -> sources[id]?.stream } == stream }

    companion object {
        fun replay(events: List<Event>, upto: Int = events.size): ProjectState {
            val st = ProjectState()
            events.take(upto).forEach { st.apply(it) }
            return st
        }
    }
}
