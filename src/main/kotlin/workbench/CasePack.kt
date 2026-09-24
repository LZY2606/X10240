package workbench

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

object CasePack {
    const val PREFIX = "casepack/"

    private fun read(path: String): String {
        val stream = CasePack::class.java.classLoader.getResourceAsStream(PREFIX + path)
            ?: throw IllegalArgumentException("案例包缺少文件: $path")
        return stream.bufferedReader().readText()
    }

    fun load(wb: Workbench): Int {
        val script = json.parseToJsonElement(read("script.json")).jsonObject
        val steps = script["steps"]!!.jsonArray
        var done = 0
        for (stepEl in steps) {
            val step = stepEl.jsonObject
            when (val op = step["op"]!!.jsonPrimitive.content) {
                "registerCurve" -> wb.registerCurve(read(step["file"]!!.jsonPrimitive.content)).getOrThrow()
                "importMeasurements" -> {
                    for (m in json.parseToJsonElement(read(step["file"]!!.jsonPrimitive.content)).jsonArray) {
                        wb.importMeasurement(m.toString()).getOrThrow()
                    }
                }
                "assumeOffsets" -> {
                    for (o in json.parseToJsonElement(read(step["file"]!!.jsonPrimitive.content)).jsonArray) {
                        wb.assumeOffset(o.toString()).getOrThrow()
                    }
                }
                "addConstraints" -> {
                    for (c in json.parseToJsonElement(read(step["file"]!!.jsonPrimitive.content)).jsonArray) {
                        wb.addConstraint(c.toString()).getOrThrow()
                    }
                }
                "calibrate", "recalibrate" -> {
                    val offset = step["offset"]?.jsonPrimitive?.content
                    wb.computeCalibration(step["sample"]!!.jsonPrimitive.content, offset).getOrThrow()
                }
                "calibrateAll" -> {
                    val offsets = step["offsets"]!!.jsonObject
                    for ((sample, off) in offsets) {
                        wb.computeCalibration(sample, off.jsonPrimitive.content).getOrThrow()
                    }
                }
                "decideInterval" -> {
                    val st = wb.state()
                    val sample = step["sample"]!!.jsonPrimitive.content
                    val cal = st.latestCalibration(sample) ?: error("无校准结果: $sample")
                    wb.decideInterval(
                        cal.calibrationId, step["intervalIndex"]!!.jsonPrimitive.int,
                        step["accept"]!!.jsonPrimitive.boolean,
                        step["actor"]!!.jsonPrimitive.content, step["rationale"]!!.jsonPrimitive.content
                    ).getOrThrow()
                }
                "reviseOffset" -> wb.assumeOffset(read(step["file"]!!.jsonPrimitive.content)).getOrThrow()
                "reviseMeasurement" -> wb.importMeasurement(read(step["file"]!!.jsonPrimitive.content)).getOrThrow()
                "decideCandidate" -> {
                    val st = wb.state()
                    val constraintId = step["constraint"]!!.jsonPrimitive.content
                    val kind = step["kind"]!!.jsonPrimitive.content
                    val conflict = st.conflicts.values.firstOrNull { it.constraintId == constraintId }
                        ?: error("未找到约束 $constraintId 的冲突")
                    val cand = st.candidates.values.firstOrNull { it.conflictId == conflict.conflictId && it.kind == kind }
                        ?: error("未找到候选 $kind")
                    wb.decideCandidate(
                        cand.candidateId, step["accept"]!!.jsonPrimitive.boolean,
                        step["actor"]!!.jsonPrimitive.content, step["rationale"]!!.jsonPrimitive.content
                    ).getOrThrow()
                }
                "undoLastDecisionBy" -> {
                    val st = wb.state()
                    val actor = step["actor"]!!.jsonPrimitive.content
                    val target = st.events.filter { it.type == "decision.made" }
                        .lastOrNull { it.payload["decision"]!!.jsonObject["actor"]!!.jsonPrimitive.content == actor }
                        ?: error("没有 $actor 的裁定可撤销")
                    wb.undoDecision(target.seq, step["undoActor"]!!.jsonPrimitive.content, step["rationale"]!!.jsonPrimitive.content).getOrThrow()
                }
                else -> error("未知案例包操作: $op")
            }
            done++
        }
        wb.store.append("casepack.loaded", buildJsonObject {
            put("name", script["name"]!!.jsonPrimitive.content)
            put("steps", done)
        })
        return done
    }
}
