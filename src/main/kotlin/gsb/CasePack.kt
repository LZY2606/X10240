package gsb

import kotlinx.serialization.json.*

/** 案例包：「碳年候选席：校准曲线、储库偏移与多峰年代证据」离线案例包执行器。 */
object CasePack {
    fun execute(store: Store, root: JsonObject): ImportReport {
        val wb = Workbench(store)
        val steps = root["steps"]?.jsonArray ?: return ImportReport(false, listOf("案例包缺少 steps 数组"))
        val errors = ArrayList<String>()
        var done = 0
        steps.forEachIndexed { idx, el ->
            val step = el.jsonObject
            val op = step["op"]?.jsonPrimitive?.content
                ?: run { errors.add("steps[$idx]: 缺少 op"); return@forEachIndexed }
            try {
                when (op) {
                    "submit" -> {
                        val stream = step["stream"]!!.jsonPrimitive.content
                        val kind = step["kind"]!!.jsonPrimitive.content
                        val source = step["source"]!!.jsonPrimitive.content
                        val payload = step["payload"]!!.jsonObject
                        if (kind !in Kinds.ALL) throw WorkbenchException("未知输入类型 $kind")
                        store.submitInput(stream, kind, source, payload)
                    }
                    "calibrate" -> wb.calibrate(
                        step["measure"]!!.jsonPrimitive.content, step["curve"]!!.jsonPrimitive.content,
                        step["offset"]!!.jsonPrimitive.content, step["era"]!!.jsonPrimitive.content)
                    "assessStrat" -> wb.assessStrat(step["constraint"]!!.jsonPrimitive.content)
                    "adjudicate" -> wb.adjudicate(step["target"]!!.jsonPrimitive.content,
                        step["verdict"]!!.jsonPrimitive.content, step["rationale"]!!.jsonPrimitive.content)
                    "resolve" -> wb.resolveConflict(step["target"]!!.jsonPrimitive.content,
                        step["chosen"]!!.jsonPrimitive.content, step["note"]?.jsonPrimitive?.content ?: "")
                    "undo" -> wb.undoResolution(step["target"]!!.jsonPrimitive.content)
                    else -> errors.add("steps[$idx]: 未知操作 $op")
                }
                done++
            } catch (e: Exception) {
                errors.add("steps[$idx] ($op): ${e.message}")
            }
        }
        return ImportReport(errors.isEmpty(), errors, inputs = done)
    }

    fun loadBundled(): String =
        CasePack::class.java.getResourceAsStream("/casepack.json")!!.bufferedReader().readText()
}
