package workbench

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object CasePack {
    fun resourceText(): String =
        requireNotNull(CasePack::class.java.getResource("/casepack/case.json")) { "案例包资源缺失" }.readText()

    fun describe(text: String): Pair<String, String> {
        val o = Json.parseToJsonElement(text).jsonObject
        return o.getValue("caseId").jsonPrimitive.content to o.getValue("title").jsonPrimitive.content
    }

    /** 按脚本顺序执行案例包；所有步骤与手工操作走同一事件管线。 */
    fun run(app: App, text: String): List<String> {
        val pack = Json.parseToJsonElement(text).jsonObject
        val log = mutableListOf<String>()
        val json = Json { classDiscriminator = "type"; ignoreUnknownKeys = true }
        fun JsonObject.s(k: String): String? = this[k]?.jsonPrimitive?.contentOrNull
        for (step in pack.getValue("steps").jsonArray) {
            val o = step.jsonObject
            when (val op = o.getValue("do").jsonPrimitive.content) {
                "event" -> {
                    val p = json.decodeFromJsonElement(Payload.serializer(), o.getValue("payload"))
                    val e = app.submit(p)
                    log += "事件 #${e.seq} ${e.payload::class.simpleName}"
                }
                "calibrate" -> {
                    val r = app.calibrate(o.s("measurement")!!, o.s("offset"))
                    log += "校准 ${r.measurementId} -> ${r.id} 状态=${r.status} 峰数=${r.peaks.size} 指纹=${r.fingerprint.take(12)}"
                }
                "stratcheck" -> {
                    val s = app.stratCheck()
                    log += "层序检查 ${s.id} 冲突=${s.conflicts.size}"
                }
                "decide" -> {
                    val p = app.projections()
                    val resultId = o.s("result") ?: p.results.values.last { it.measurementId == o.s("measurement") }.id
                    val d = app.decide(resultId, o.s("peak")?.toInt(), o.s("action")!!, o.s("reason") ?: "", o.s("reviewer") ?: "casepack")
                    log += "裁定 ${d.id} ${d.action} 结果=$resultId 峰=${d.peakIndex}"
                }
                "undo" -> {
                    val p = app.projections()
                    val target = p.activeDecisions().lastOrNull { it.resultId == o.s("result") || it.id == o.s("decision") }
                        ?: error("无可撤销裁定")
                    val u = app.undo(target.id, o.s("reason") ?: "")
                    log += "撤销 ${u.id} 撤销对象=${u.undoOf}"
                }
                else -> error("未知案例步骤：$op")
            }
        }
        return log
    }
}
