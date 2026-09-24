package workbench

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 导出/导入：身份、顺序与来源关系在删除本地库再导入后保持不变。 */
object Exporter {
    fun export(store: Store): String {
        val events = store.loadEvents()
        val doc = buildJsonObject {
            put("format", "radiocarbon-workbench-export")
            put("formatVersion", 1)
            put("eventCount", events.size)
            put("events", buildJsonArray {
                events.forEach { e ->
                    add(buildJsonObject {
                        put("seq", e.seq); put("id", e.id); put("ts", e.ts)
                        put("kind", e.kind); put("refId", e.refId)
                        put("summary", e.summary); put("payload", e.payload)
                    })
                }
            })
        }
        return JsonFmt.toString(kotlinx.serialization.json.JsonObject.serializer(), doc)
    }

    /** 导入到全新库：按原 seq 顺序原样写入，保留 id 与负载。 */
    fun importInto(store: Store, text: String): Int {
        val doc = JsonFmt.parseToJsonElement(text) as JsonObject
        require((doc["format"] as JsonPrimitive).content == "radiocarbon-workbench-export") { "不是本系统导出文件" }
        val events = doc["events"] as kotlinx.serialization.json.JsonArray
        var n = 0
        for (el in events) {
            val o = el as JsonObject
            store.conn.prepareStatement(
                "INSERT INTO events(seq,id,ts,kind,ref_id,summary,payload) VALUES(?,?,?,?,?,?,?)"
            ).apply {
                setLong(1, (o["seq"] as JsonPrimitive).content.toLong())
                setString(2, (o["id"] as JsonPrimitive).content)
                setString(3, (o["ts"] as JsonPrimitive).content)
                setString(4, (o["kind"] as JsonPrimitive).content)
                setString(5, (o["refId"] as JsonPrimitive).content)
                setString(6, (o["summary"] as JsonPrimitive).content)
                setString(7, o["payload"].toString())
            }.executeUpdate()
            n++
        }
        return n
    }
}
