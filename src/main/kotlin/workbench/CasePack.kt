package workbench

import kotlinx.serialization.json.*

data class StepResult(val stepId: String, val title: String, val produced: List<String>, val detail: String)

class CasePackRunner(val wb: Workbench) {
    private val json = Json { ignoreUnknownKeys = true }
    private var lastCandidates = mutableMapOf<String, String>() // strategy -> derivedId
    private var lastDecisions = mutableMapOf<String, String>()  // reviewer -> eventId

    fun load(): JsonObject {
        val text = javaClass.getResourceAsStream("/casepack/carbon-bench.json")!!.bufferedReader().readText()
        return json.parseToJsonElement(text).jsonObject
    }

    fun stepCount(): Int = load()["steps"]!!.jsonArray.size

    fun runStep(index: Int): StepResult {
        val pack = load()
        val step = pack["steps"]!!.jsonArray[index].jsonObject
        val id = step["id"]!!.jsonPrimitive.content
        val title = step["title"]!!.jsonPrimitive.content
        val produced = mutableListOf<String>()
        val detail = StringBuilder()
        when (step["action"]!!.jsonPrimitive.content) {
            "import-baseline" -> {
                for (el in pack["baseline"]!!.jsonObject["inputs"]!!.jsonArray) {
                    val o = el.jsonObject
                    val rec = wb.submitInput(
                        InputKind.valueOf(o["kind"]!!.jsonPrimitive.content),
                        o["streamId"]!!.jsonPrimitive.content,
                        o["payload"]!!.jsonObject,
                        o["provenance"]!!.jsonPrimitive.content
                    )
                    produced += rec.id
                }
                detail.append("imported ${produced.size} baseline inputs, each with its own provenance")
            }
            "calibrate-all" -> {
                val curve = step["curve"]!!.jsonPrimitive.content
                val era = step["era"]!!.jsonPrimitive.content
                for (m in step["measurements"]!!.jsonArray) {
                    val rec = wb.calibrate(m.jsonPrimitive.content, curve, era)
                    val p = JsonFmt.decodeFromJsonElement(CalibrationPayload.serializer(), rec.payload)
                    produced += rec.id
                    detail.append("${m.jsonPrimitive.content}: peaks=${p.peaks.size} diag=${p.diagnostics.joinToString("|").ifEmpty{"none"}}\n")
                }
            }
            "offset-compare" -> {
                val rec = wb.compareOffsets(
                    step["measurement"]!!.jsonPrimitive.content,
                    step["curve"]!!.jsonPrimitive.content,
                    step["era"]!!.jsonPrimitive.content,
                    step["reservoirs"]!!.jsonArray.map { if (it is JsonNull) null else it.jsonPrimitive.content }
                )
                produced += rec.id
                detail.append("offset comparison ${rec.id}")
            }
            "strat-check" -> {
                val recs = wb.checkStratigraphy(step["stratigraphy"]!!.jsonPrimitive.content)
                produced += recs.map { it.id }
                for (r in recs.filter { it.kind == DerivedKind.STRAT_CANDIDATE }) {
                    val p = JsonFmt.decodeFromJsonElement(StratCandidatePayload.serializer(), r.payload)
                    lastCandidates[p.strategy] = r.id
                }
                detail.append("conflicts=${recs.count{it.kind==DerivedKind.STRAT_CONFLICT}} candidates=${recs.count{it.kind==DerivedKind.STRAT_CANDIDATE}} (all legal candidates kept)")
            }
            "decide" -> {
                val candId = lastCandidates[step["candidateStrategy"]!!.jsonPrimitive.content]
                    ?: error("no candidate for strategy")
                val ev = wb.decide(candId, step["accept"]!!.jsonPrimitive.boolean,
                    step["reviewer"]!!.jsonPrimitive.content, step["note"]!!.jsonPrimitive.content)
                lastDecisions[step["reviewer"]!!.jsonPrimitive.content] = ev.id
                produced += ev.id
                detail.append("decision ${ev.id} on $candId")
            }
            "resolve-conflict" -> {
                val candId = lastCandidates[step["candidateStrategy"]!!.jsonPrimitive.content]!!
                val chosen = lastDecisions[step["chosenReviewer"]!!.jsonPrimitive.content]!!
                val ev = wb.resolveConflict(candId, chosen, step["reviewer"]!!.jsonPrimitive.content, step["note"]!!.jsonPrimitive.content)
                produced += ev.id
                detail.append("resolution ${ev.id} chooses $chosen")
            }
            "revise-input" -> {
                val rec = wb.adjustParameters(
                    step["streamId"]!!.jsonPrimitive.content,
                    InputKind.valueOf(step["kind"]!!.jsonPrimitive.content),
                    step["payload"]!!.jsonObject,
                    step["provenance"]!!.jsonPrimitive.content,
                    step["reviewer"]!!.jsonPrimitive.content
                )
                produced += rec.id
                detail.append("revised ${rec.streamId} to v${rec.version}; only dependents of ${rec.streamId} go stale")
            }
            "curve-upgrade" -> {
                val rec = wb.submitInput(InputKind.CURVE, step["curve"]!!.jsonPrimitive.content,
                    step["payload"]!!.jsonObject, step["provenance"]!!.jsonPrimitive.content)
                produced += rec.id
                for (m in step["recalibrate"]!!.jsonArray) {
                    val cal = wb.calibrate(m.jsonPrimitive.content, step["curve"]!!.jsonPrimitive.content, step["era"]!!.jsonPrimitive.content)
                    produced += cal.id
                }
                detail.append("curve upgraded to ${rec.id}; old calibrations remain viewable as STALE (并列对照)")
            }
            "undo" -> {
                val ev = wb.undo()
                if (ev != null) { produced += ev.id; detail.append("undone ${ev.payload["undoneEventId"]!!.jsonPrimitive.content}") }
                else detail.append("nothing to undo")
            }
            "export" -> {
                val archive = wb.export()
                produced += archive["exportedAtEvent"]!!.jsonPrimitive.content
                detail.append("archive: ${archive["inputs"]!!.jsonArray.size} inputs, ${archive["derived"]!!.jsonArray.size} derived, ${archive["events"]!!.jsonArray.size} events")
            }
            else -> error("unknown action")
        }
        wb.store.appendEvent(EventType.SCENARIO_STEP, buildJsonObject {
            put("stepId", id); put("title", title)
        }, wb.clock.now())
        return StepResult(id, title, produced, detail.toString().trim())
    }
}
