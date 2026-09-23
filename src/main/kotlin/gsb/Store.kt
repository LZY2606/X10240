package gsb

import kotlinx.serialization.json.*
import java.sql.Connection
import java.sql.DriverManager

data class ImportReport(val ok: Boolean, val errors: List<String>, val inputs: Int = 0, val events: Int = 0, val derived: Int = 0)

data class Snapshot(
    val atSeq: Long,
    val heads: Map<String, InputRecord>,
    val inputs: List<InputRecord>,
    val derived: List<DerivedRecord>,
    val staleIds: Set<String>,
    val events: List<Event>
)

class Store(dbPath: String, val clock: Clock) {
    val conn: Connection

    init {
        if (dbPath != ":memory:") java.io.File(dbPath).parentFile?.mkdirs()
        conn = DriverManager.getConnection("jdbc:sqlite:$dbPath")
        conn.createStatement().use { st ->
            st.executeUpdate("PRAGMA journal_mode=WAL")
            st.executeUpdate("CREATE TABLE IF NOT EXISTS inputs(id TEXT PRIMARY KEY, stream_id TEXT, kind TEXT, version INTEGER, parent_id TEXT, source TEXT, payload TEXT, seq INTEGER, created_at TEXT)")
            st.executeUpdate("CREATE TABLE IF NOT EXISTS derived(id TEXT PRIMARY KEY, kind TEXT, algo_version TEXT, fingerprint TEXT, deps TEXT, result TEXT, seq INTEGER, created_at TEXT)")
            st.executeUpdate("CREATE TABLE IF NOT EXISTS events(seq INTEGER PRIMARY KEY, id TEXT, type TEXT, payload TEXT, created_at TEXT)")
        }
    }

    fun close() = conn.close()

    private fun nextSeq(): Long =
        conn.createStatement().use { st ->
            val rs = st.executeQuery("SELECT COALESCE(MAX(seq),0) FROM events")
            rs.next(); rs.getLong(1)
        } + 1

    @Synchronized
    fun appendEvent(type: String, payload: JsonObject): Event {
        val seq = nextSeq()
        val ev = Event(seq, "evt-$seq-$type", type, payload, clock.now())
        conn.prepareStatement("INSERT INTO events(seq,id,type,payload,created_at) VALUES(?,?,?,?,?)").use { ps ->
            ps.setLong(1, ev.seq); ps.setString(2, ev.id); ps.setString(3, ev.type)
            ps.setString(4, ev.payload.toString()); ps.setString(5, ev.createdAt)
            ps.executeUpdate()
        }
        return ev
    }

    @Synchronized
    fun submitInput(streamId: String, kind: String, source: String, payload: JsonObject): InputRecord {
        require(kind in Kinds.ALL) { "未知输入类型: $kind" }
        val head = headOf(streamId)
        val version = (head?.version ?: 0) + 1
        val id = "inp-" + sha256("$streamId|$kind|$version|${canon(payload)}").take(12)
        val rec = InputRecord(id, streamId, kind, version, head?.id, source, payload, clock.now())
        val seq = nextSeq()
        conn.prepareStatement("INSERT INTO inputs(id,stream_id,kind,version,parent_id,source,payload,seq,created_at) VALUES(?,?,?,?,?,?,?,?,?)").use { ps ->
            ps.setString(1, rec.id); ps.setString(2, rec.streamId); ps.setString(3, rec.kind)
            ps.setInt(4, rec.version); ps.setString(5, rec.parentId); ps.setString(6, rec.source)
            ps.setString(7, rec.payload.toString()); ps.setLong(8, seq); ps.setString(9, rec.createdAt)
            ps.executeUpdate()
        }
        appendEvent(Ev.INPUT_SUBMITTED, buildJsonObject {
            put("inputId", rec.id); put("streamId", streamId); put("kind", kind)
            put("parentId", rec.parentId?.let { JsonPrimitive(it) } ?: JsonNull)
            put("version", version); put("source", source)
        })
        // 局部过期：只标记直接依赖该 stream 的派生结果
        for (d in allDerived()) {
            if (d.id in staleIds()) continue
            if (d.deps.any { it.streamId == streamId && it.inputId != rec.id }) {
                appendEvent(Ev.DERIVED_STALED, buildJsonObject {
                    put("derivedId", d.id); put("becauseOf", rec.id); put("streamId", streamId)
                })
            }
        }
        return rec
    }

    fun headOf(streamId: String, atSeq: Long = Long.MAX_VALUE): InputRecord? =
        allInputs().filter { it.streamId == streamId && inputSeq(it.id) <= atSeq }.maxByOrNull { it.version }

    fun inputById(id: String): InputRecord? = allInputs().firstOrNull { it.id == id }

    fun allInputs(): List<InputRecord> {
        val out = ArrayList<InputRecord>()
        conn.createStatement().use { st ->
            val rs = st.executeQuery("SELECT id,stream_id,kind,version,parent_id,source,payload,created_at FROM inputs ORDER BY seq")
            while (rs.next()) out.add(InputRecord(
                rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4),
                rs.getString(5), rs.getString(6), JsonFmt.parseToJsonElement(rs.getString(7)).jsonObject, rs.getString(8)))
        }
        return out
    }

    fun inputSeq(id: String): Long =
        conn.prepareStatement("SELECT seq FROM inputs WHERE id=?").use { ps ->
            ps.setString(1, id); val rs = ps.executeQuery(); if (rs.next()) rs.getLong(1) else Long.MAX_VALUE
        }

    fun derivedSeq(id: String): Long =
        conn.prepareStatement("SELECT seq FROM derived WHERE id=?").use { ps ->
            ps.setString(1, id); val rs = ps.executeQuery(); if (rs.next()) rs.getLong(1) else Long.MAX_VALUE
        }

    fun allDerived(): List<DerivedRecord> {
        val out = ArrayList<DerivedRecord>()
        conn.createStatement().use { st ->
            val rs = st.executeQuery("SELECT id,kind,algo_version,fingerprint,deps,result,created_at FROM derived ORDER BY seq")
            while (rs.next()) out.add(DerivedRecord(
                rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                JsonFmt.decodeFromString<List<Dep>>(rs.getString(5)),
                JsonFmt.parseToJsonElement(rs.getString(6)).jsonObject, rs.getString(7)))
        }
        return out
    }

    fun derivedById(id: String): DerivedRecord? = allDerived().firstOrNull { it.id == id }

    fun events(atSeq: Long = Long.MAX_VALUE): List<Event> {
        val out = ArrayList<Event>()
        conn.prepareStatement("SELECT seq,id,type,payload,created_at FROM events WHERE seq<=? ORDER BY seq").use { ps ->
            ps.setLong(1, atSeq)
            val rs = ps.executeQuery()
            while (rs.next()) out.add(Event(rs.getLong(1), rs.getString(2), rs.getString(3),
                JsonFmt.parseToJsonElement(rs.getString(4)).jsonObject, rs.getString(5)))
        }
        return out
    }

    fun staleIds(atSeq: Long = Long.MAX_VALUE): Set<String> =
        events(atSeq).filter { it.type == Ev.DERIVED_STALED }
            .mapNotNull { it.payload["derivedId"]?.jsonPrimitive?.content }.toSet()

    /** 记录派生结果：算法版本 + 参数指纹 + 直接来源；同指纹去重。 */
    @Synchronized
    fun recordDerived(kind: String, algoVersion: String, deps: List<Dep>, result: JsonObject): DerivedRecord {
        val fp = sha256(canon(buildJsonObject {
            put("algo", algoVersion)
            putJsonArray("deps") { deps.sortedBy { it.streamId }.forEach { d ->
                add(buildJsonObject { put("streamId", d.streamId); put("inputId", d.inputId)
                    put("payload", inputById(d.inputId)?.payload ?: JsonNull) }) } }
        }))
        val id = "der-" + fp.take(12)
        derivedById(id)?.let { return it }
        val rec = DerivedRecord(id, kind, algoVersion, fp, deps, result, clock.now())
        val seq = nextSeq()
        conn.prepareStatement("INSERT INTO derived(id,kind,algo_version,fingerprint,deps,result,seq,created_at) VALUES(?,?,?,?,?,?,?,?)").use { ps ->
            ps.setString(1, rec.id); ps.setString(2, rec.kind); ps.setString(3, rec.algoVersion)
            ps.setString(4, rec.fingerprint); ps.setString(5, JsonFmt.encodeToString(rec.deps))
            ps.setString(6, rec.result.toString()); ps.setLong(7, seq); ps.setString(8, rec.createdAt)
            ps.executeUpdate()
        }
        appendEvent(if (kind == "calibration") Ev.CALIB_COMPUTED else Ev.STRAT_ASSESSED, buildJsonObject {
            put("derivedId", rec.id); put("kind", kind); put("algoVersion", algoVersion)
            put("fingerprint", fp)
            putJsonArray("deps") { deps.forEach { add(buildJsonObject { put("streamId", it.streamId); put("inputId", it.inputId) }) } }
        })
        return rec
    }

    /** 某目标上的人工决定状态：accept/reject/adjust/conflicted/undecided/无。 */
    fun adjudicationState(target: String, atSeq: Long = Long.MAX_VALUE): Pair<String, List<Event>> {
        val evs = events(atSeq)
        val adjs = evs.filter { it.type == Ev.ADJUDICATION && it.payload["target"]?.jsonPrimitive?.content == target }
        val undoneResolutions = evs.filter { it.type == Ev.CONFLICT_UNDONE }
            .mapNotNull { it.payload["resolutionSeq"]?.jsonPrimitive?.long }.toSet()
        val resolution = evs.filter { it.type == Ev.CONFLICT_RESOLVED && it.seq !in undoneResolutions &&
            it.payload["target"]?.jsonPrimitive?.content == target }.lastOrNull()
        if (resolution != null) {
            val chosen = resolution.payload["chosen"]?.jsonPrimitive?.content ?: "undecided"
            if (chosen == "undecided") return "undecided" to adjs
            val chosenEv = adjs.firstOrNull { it.seq.toString() == chosen }
            return (chosenEv?.payload?.get("verdict")?.jsonPrimitive?.content ?: "undecided") to adjs
        }
        if (adjs.isEmpty()) return "none" to adjs
        val verdicts = adjs.map { it.payload["verdict"]?.jsonPrimitive?.content }.toSet()
        return (if (verdicts.size == 1) verdicts.first()!! else "conflicted") to adjs
    }

    fun stateAt(seq: Long): Snapshot {
        val evs = events(seq)
        val ins = allInputs().filter { inputSeq(it.id) <= seq }
        val heads = ins.groupBy { it.streamId }.mapValues { e -> e.value.maxByOrNull { it.version }!! }
        val der = allDerived().filter { derivedSeq(it.id) <= seq }
        return Snapshot(seq, heads, ins, der, staleIds(seq), evs)
    }

    // ---------- 导出 / 导入（身份、顺序、来源关系保持） ----------
    fun exportJson(): String {
        val root = buildJsonObject {
            put("formatVersion", 1)
            putJsonArray("inputs") {
                allInputs().forEach { r ->
                    add(buildJsonObject {
                        put("seq", inputSeq(r.id))
                        JsonFmt.encodeToJsonElement(r).jsonObject.forEach { (k, v) -> put(k, v) }
                    })
                }
            }
            putJsonArray("derived") {
                allDerived().forEach { r ->
                    add(buildJsonObject {
                        put("seq", derivedSeq(r.id))
                        JsonFmt.encodeToJsonElement(r).jsonObject.forEach { (k, v) -> put(k, v) }
                    })
                }
            }
            putJsonArray("events") { events().forEach { add(JsonFmt.encodeToJsonElement(it)) } }
        }
        return Json { prettyPrint = true }.encodeToJsonElement(root).toString()
    }

    fun isEmpty(): Boolean = allInputs().isEmpty() && events().isEmpty()

    @Synchronized
    fun importJson(text: String): ImportReport {
        val root = try { JsonFmt.parseToJsonElement(text).jsonObject }
        catch (e: Exception) { return ImportReport(false, listOf("JSON 解析失败: ${e.message}")) }
        if (root["formatVersion"] != null) return importExport(root)
        if (root["steps"] != null) return CasePack.execute(this, root)
        if (root["kind"] != null && root["stream"] != null) {
            val kind = root["kind"]!!.jsonPrimitive.content
            val stream = root["stream"]!!.jsonPrimitive.content
            val source = root["source"]?.jsonPrimitive?.content ?: return ImportReport(false, listOf("缺少 source 字段"))
            val payload = root["payload"]?.jsonObject ?: return ImportReport(false, listOf("缺少 payload 对象"))
            if (kind !in Kinds.ALL) return ImportReport(false, listOf("未知输入类型: $kind（允许: ${Kinds.ALL.joinToString()}）"))
            submitInput(stream, kind, source, payload)
            return ImportReport(true, emptyList(), inputs = 1)
        }
        return ImportReport(false, listOf("无法识别的导入格式：需要 formatVersion（导出文件）、steps（案例包）或 kind+stream（单条输入）"))
    }

    private fun importExport(root: JsonObject): ImportReport {
        if (!isEmpty()) return ImportReport(false, listOf("完整恢复只能导入空数据库；请先删除本地数据库"))
        val errors = ArrayList<String>()
        val inputs = root["inputs"]?.jsonArray ?: run { errors.add("缺少 inputs 数组"); JsonArray(emptyList()) }
        val derived = root["derived"]?.jsonArray ?: JsonArray(emptyList())
        val events = root["events"]?.jsonArray ?: run { errors.add("缺少 events 数组"); JsonArray(emptyList()) }
        if (errors.isNotEmpty()) return ImportReport(false, errors)
        var ni = 0; var nd = 0; var ne = 0
        for (el in inputs) {
            val r = JsonFmt.decodeFromJsonElement<InputRecord>(el)
            val seq = el.jsonObject["seq"]?.jsonPrimitive?.long ?: (ni + 1L)
            conn.prepareStatement("INSERT INTO inputs(id,stream_id,kind,version,parent_id,source,payload,seq,created_at) VALUES(?,?,?,?,?,?,?,?,?)").use { ps ->
                ps.setString(1, r.id); ps.setString(2, r.streamId); ps.setString(3, r.kind)
                ps.setInt(4, r.version); ps.setString(5, r.parentId); ps.setString(6, r.source)
                ps.setString(7, r.payload.toString()); ps.setLong(8, seq); ps.setString(9, r.createdAt)
                ps.executeUpdate()
            }
            ni++
        }
        for (el in derived) {
            val r = JsonFmt.decodeFromJsonElement<DerivedRecord>(el)
            val seq = el.jsonObject["seq"]?.jsonPrimitive?.long ?: (nd + 1L)
            conn.prepareStatement("INSERT INTO derived(id,kind,algo_version,fingerprint,deps,result,seq,created_at) VALUES(?,?,?,?,?,?,?,?)").use { ps ->
                ps.setString(1, r.id); ps.setString(2, r.kind); ps.setString(3, r.algoVersion)
                ps.setString(4, r.fingerprint); ps.setString(5, JsonFmt.encodeToString(r.deps))
                ps.setString(6, r.result.toString()); ps.setLong(7, seq); ps.setString(8, r.createdAt)
                ps.executeUpdate()
            }
            nd++
        }
        for (el in events) {
            val e = JsonFmt.decodeFromJsonElement<Event>(el)
            conn.prepareStatement("INSERT INTO events(seq,id,type,payload,created_at) VALUES(?,?,?,?,?)").use { ps ->
                ps.setLong(1, e.seq); ps.setString(2, e.id); ps.setString(3, e.type)
                ps.setString(4, e.payload.toString()); ps.setString(5, e.createdAt)
                ps.executeUpdate()
            }
            ne++
        }
        return ImportReport(true, emptyList(), ni, ne, nd)
    }
}
