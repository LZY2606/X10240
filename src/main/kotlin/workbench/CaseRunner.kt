package workbench

import kotlinx.serialization.json.*
import java.io.File

/** 离线案例包回放器：每个脚本步骤都落成机器可读事件。 */
class CaseRunner(val wb: Workbench) {
    val json = wb.json
    val handles = mutableMapOf<String, String>() // as-句柄 -> resultId
    private var lastDecisionId: String? = null

    fun run(caseFile: File) {
        val root = Json.parseToJsonElement(caseFile.readText()).jsonObject
        run(root)
    }

    fun run(root: JsonObject) {
        val caseId = root["caseId"]!!.jsonPrimitive.content
        wb.store.appendEvent("case.started", buildJsonObject {
            put("caseId", caseId); put("title", root["title"]!!.jsonPrimitive.content)
        })
        val baseline = root["baseline"]!!.jsonObject["sources"]!!.jsonArray
        for (op in root["script"]!!.jsonArray) {
            val o = op.jsonObject
            when (o["op"]!!.jsonPrimitive.content) {
                "note" -> wb.store.appendEvent("case.note", buildJsonObject {
                    put("caseId", caseId); put("text", o["text"]!!.jsonPrimitive.content)
                })
                "submitBaseline" -> for (s in baseline) {
                    val src = s.jsonObject
                    wb.submitSource(src["kind"]!!.jsonPrimitive.content,
                        src["id"]!!.jsonPrimitive.content, src["payload"]!!.jsonObject)
                }
                "calibrate" -> {
                    val r = wb.calibrate(
                        o["measurement"]!!.jsonPrimitive.content,
                        o["curve"]!!.jsonPrimitive.content,
                        o["offset"]!!.jsonPrimitive.content)
                    o["as"]?.jsonPrimitive?.content?.let { handles[it] = r.id }
                }
                "sequence" -> {
                    val constraintId = o["constraint"]!!.jsonPrimitive.content
                    val constraint = wb.store.latestSource(constraintId)!!
                    val map = mutableMapOf<String, String>()
                    for (p in constraint.payload["pairs"]!!.jsonArray) {
                        val pair = p.jsonObject
                        for (key in listOf("older", "younger")) {
                            val measId = pair[key]!!.jsonPrimitive.content
                            map[measId] = latestCalibrationFor(measId)
                        }
                    }
                    val r = wb.checkSequence(constraintId, map)
                    o["as"]?.jsonPrimitive?.content?.let { handles[it] = r.id }
                }
                "revise" -> wb.reviseSource(o["id"]!!.jsonPrimitive.content,
                    o["payload"]!!.jsonObject, o["note"]!!.jsonPrimitive.content)
                "decide" -> {
                    val target = resolveTarget(o["target"]!!.jsonPrimitive.content)
                    val d = wb.decide(target, o["action"]!!.jsonPrimitive.content,
                        o["rationale"]!!.jsonPrimitive.content, o["reviewer"]!!.jsonPrimitive.content)
                    lastDecisionId = d.id
                }
                "undo" -> {
                    val which = o["decision"]!!.jsonPrimitive.content
                    val id = if (which == "last") lastDecisionId else which
                    wb.undoDecision(id ?: error("no decision to undo"))
                }
                else -> error("unknown op: ${o["op"]}")
            }
        }
        wb.store.appendEvent("case.completed", buildJsonObject {
            put("caseId", caseId); put("handles", handles.toString())
        })
    }

    private fun resolveTarget(raw: String): String =
        if (raw.startsWith("$")) handles[raw.removePrefix("$")] ?: error("unknown handle $raw") else raw

    private fun latestCalibrationFor(measurementId: String): String =
        wb.store.results().filter { it.kind == "calibration" && !it.stale &&
            it.payload["measurementId"]!!.jsonPrimitive.content == measurementId }
            .maxByOrNull { it.createdSeq }?.id
            ?: error("no calibration for $measurementId")
}
