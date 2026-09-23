package rcwb

import kotlinx.serialization.json.*
import java.sql.Connection
import java.sql.DriverManager

data class ResultRecord(
    val id: String,
    val createdSeq: Long,
    val fingerprint: String,
    val algo: String,
    val sources: List<String>,
    val payload: JsonObject,
    val stale: Boolean,
    val staleReason: String?,
)

/**
 * SQLite 事件存储：原始输入一经提交不可覆盖；修订以新事件 + supersedes 关系表达。
 * 派生结果带算法版本、参数指纹与直接来源；来源被取代时结果过期但保留可查。
 */
class Store(dbPath: String, private val clock: Clock) : AutoCloseable {
    private val conn: Connection

    init {
        if (dbPath != ":memory:") {
            java.io.File(dbPath).absoluteFile.parentFile?.mkdirs()
        }
        conn = DriverManager.getConnection("jdbc:sqlite:$dbPath")
        conn.autoCommit = true
        conn.createStatement().use { st ->
            st.executeUpdate("PRAGMA journal_mode=WAL")
            st.executeUpdate(
                """CREATE TABLE IF NOT EXISTS events(
                     id TEXT PRIMARY KEY, seq INTEGER NOT NULL UNIQUE, type TEXT NOT NULL,
                     payload TEXT NOT NULL, at TEXT NOT NULL, actor TEXT NOT NULL)"""
            )
            st.executeUpdate(
                """CREATE TABLE IF NOT EXISTS results(
                     id TEXT PRIMARY KEY, created_seq INTEGER NOT NULL, fingerprint TEXT NOT NULL,
                     algo TEXT NOT NULL, sources TEXT NOT NULL, payload TEXT NOT NULL,
                     stale INTEGER NOT NULL DEFAULT 0, stale_reason TEXT)"""
            )
            st.executeUpdate(
                """CREATE TABLE IF NOT EXISTS superseded(
                     event_id TEXT PRIMARY KEY, superseded_by TEXT NOT NULL, seq INTEGER NOT NULL)"""
            )
            st.executeUpdate(
                """CREATE TABLE IF NOT EXISTS counters(prefix TEXT PRIMARY KEY, next INTEGER NOT NULL)"""
            )
        }
    }

    @Synchronized
    fun nextId(prefix: String): String {
        val cur = conn.prepareStatement("SELECT next FROM counters WHERE prefix=?").use { ps ->
            ps.setString(1, prefix)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else 1L }
        }
        conn.prepareStatement(
            "INSERT INTO counters(prefix,next) VALUES(?,?) ON CONFLICT(prefix) DO UPDATE SET next=excluded.next"
        ).use { ps ->
            ps.setString(1, prefix); ps.setLong(2, cur + 1); ps.executeUpdate()
        }
        return "%s-%04d".format(prefix, cur)
    }

    private fun maxSeq(): Long =
        conn.createStatement().use { st ->
            st.executeQuery("SELECT COALESCE(MAX(seq),0) FROM events").use { rs -> rs.next(); rs.getLong(1) }
        }

    @Synchronized
    fun append(type: String, payload: JsonObject, actor: String = "system"): Event {
        val ev = Event(nextId(prefixFor(type)), maxSeq() + 1, type, payload, clock.now(), actor)
        insertEvent(ev)
        afterAppend(ev)
        return ev
    }

    private fun insertEvent(ev: Event) {
        conn.prepareStatement("INSERT INTO events(id,seq,type,payload,at,actor) VALUES(?,?,?,?,?,?)").use { ps ->
            ps.setString(1, ev.id); ps.setLong(2, ev.seq); ps.setString(3, ev.type)
            ps.setString(4, ev.payload.toString()); ps.setString(5, ev.at); ps.setString(6, ev.actor)
            ps.executeUpdate()
        }
    }

    private fun afterAppend(ev: Event) {
        val sup = ev.payload["supersedes"]?.jsonArrayOrNull()?.map { it.jsonPrimitive.content } ?: emptyList()
        if (sup.isNotEmpty()) {
            conn.prepareStatement(
                "INSERT OR IGNORE INTO superseded(event_id,superseded_by,seq) VALUES(?,?,?)"
            ).use { ps ->
                for (s in sup) { ps.setString(1, s); ps.setString(2, ev.id); ps.setLong(3, ev.seq); ps.executeUpdate() }
            }
            restale()
        }
    }

    /** 重新计算过期标记：直接来源被取代的结果过期，但保留可查。 */
    @Synchronized
    fun restale() {
        val sup = supersededMap()
        for (r in results()) {
            val bad = r.sources.firstOrNull { sup.containsKey(it) }
            val stale = bad != null
            val reason = if (stale) "source $bad superseded by ${sup[bad]}" else null
            if (r.stale != stale || r.staleReason != reason) {
                conn.prepareStatement("UPDATE results SET stale=?, stale_reason=? WHERE id=?").use { ps ->
                    ps.setInt(1, if (stale) 1 else 0)
                    if (reason == null) ps.setNull(2, java.sql.Types.VARCHAR) else ps.setString(2, reason)
                    ps.setString(3, r.id); ps.executeUpdate()
                }
            }
        }
    }

    fun supersededMap(): Map<String, Pair<String, Long>> {
        val out = LinkedHashMap<String, Pair<String, Long>>()
        conn.createStatement().use { st ->
            st.executeQuery("SELECT event_id,superseded_by,seq FROM superseded").use { rs ->
                while (rs.next()) out[rs.getString(1)] = rs.getString(2) to rs.getLong(3)
            }
        }
        return out
    }

    fun supersededAt(seq: Long): Set<String> {
        val out = HashSet<String>()
        conn.prepareStatement("SELECT event_id FROM superseded WHERE seq<=?").use { ps ->
            ps.setLong(1, seq)
            ps.executeQuery().use { rs -> while (rs.next()) out.add(rs.getString(1)) }
        }
        return out
    }

    @Synchronized
    fun saveResult(id: String, createdSeq: Long, fingerprint: String, algo: String, sources: List<String>, payload: JsonObject) {
        conn.prepareStatement(
            "INSERT OR REPLACE INTO results(id,created_seq,fingerprint,algo,sources,payload,stale,stale_reason) VALUES(?,?,?,?,?,?,0,NULL)"
        ).use { ps ->
            ps.setString(1, id); ps.setLong(2, createdSeq); ps.setString(3, fingerprint)
            ps.setString(4, algo); ps.setString(5, sources.joinToString(",")); ps.setString(6, payload.toString())
            ps.executeUpdate()
        }
        restale()
    }

    fun events(): List<Event> {
        val out = mutableListOf<Event>()
        conn.createStatement().use { st ->
            st.executeQuery("SELECT id,seq,type,payload,at,actor FROM events ORDER BY seq").use { rs ->
                while (rs.next()) out.add(Event(rs.getString(1), rs.getLong(2), rs.getString(3),
                    JSON.parseToJsonElement(rs.getString(4)).jsonObject, rs.getString(5), rs.getString(6)))
            }
        }
        return out
    }

    fun eventsOfType(type: String): List<Event> = events().filter { it.type == type }

    fun event(id: String): Event? =
        conn.prepareStatement("SELECT id,seq,type,payload,at,actor FROM events WHERE id=?").use { ps ->
            ps.setString(1, id)
            ps.executeQuery().use { rs ->
                if (rs.next()) Event(rs.getString(1), rs.getLong(2), rs.getString(3),
                    JSON.parseToJsonElement(rs.getString(4)).jsonObject, rs.getString(5), rs.getString(6))
                else null
            }
        }

    fun results(): List<ResultRecord> {
        val out = mutableListOf<ResultRecord>()
        conn.createStatement().use { st ->
            st.executeQuery("SELECT id,created_seq,fingerprint,algo,sources,payload,stale,stale_reason FROM results ORDER BY created_seq").use { rs ->
                while (rs.next()) out.add(ResultRecord(
                    rs.getString(1), rs.getLong(2), rs.getString(3), rs.getString(4),
                    rs.getString(5).split(",").filter { it.isNotBlank() },
                    JSON.parseToJsonElement(rs.getString(6)).jsonObject,
                    rs.getInt(7) != 0, rs.getString(8)))
            }
        }
        return out
    }

    fun result(id: String): ResultRecord? =
        conn.prepareStatement("SELECT id,created_seq,fingerprint,algo,sources,payload,stale,stale_reason FROM results WHERE id=?").use { ps ->
            ps.setString(1, id)
            ps.executeQuery().use { rs ->
                if (rs.next()) ResultRecord(rs.getString(1), rs.getLong(2), rs.getString(3), rs.getString(4),
                    rs.getString(5).split(",").filter { it.isNotBlank() },
                    JSON.parseToJsonElement(rs.getString(6)).jsonObject, rs.getInt(7) != 0, rs.getString(8))
                else null
            }
        }

    /** 导出：事件 + 派生结果 + 取代关系，顺序即身份。 */
    @Synchronized
    fun exportJson(): JsonObject = obj(
        "format" to "rcwb-export/1",
        "events" to events().map { ev ->
            obj("id" to ev.id, "seq" to ev.seq, "type" to ev.type, "payload" to ev.payload, "at" to ev.at, "actor" to ev.actor)
        },
        "results" to results().map { r ->
            obj("id" to r.id, "createdSeq" to r.createdSeq, "fingerprint" to r.fingerprint, "algo" to r.algo,
                "sources" to r.sources, "payload" to r.payload)
        },
    )

    /** 导入导出档：身份、顺序、来源关系原样恢复。 */
    @Synchronized
    fun importEvents(doc: JsonObject): List<String> {
        val errors = mutableListOf<String>()
        if (doc["format"]?.jsonPrimitive?.contentOrNull != "rcwb-export/1") {
            return listOf("format must be rcwb-export/1")
        }
        val evs = doc["events"]?.jsonArrayOrNull() ?: return listOf("missing events array")
        for (e in evs) {
            val o = e.jsonObject
            val id = o["id"]?.jsonPrimitive?.contentOrNull ?: return listOf("event missing id")
            if (event(id) != null) { errors.add("duplicate event id $id"); continue }
            val ev = Event(id, o["seq"]!!.jsonPrimitive.long, o["type"]!!.jsonPrimitive.content,
                o["payload"]!!.jsonObject, o["at"]!!.jsonPrimitive.content, o["actor"]!!.jsonPrimitive.content)
            insertEvent(ev)
            afterAppend(ev)
        }
        // 重建计数器，保证后续身份不冲突
        for (ev in events()) {
            val prefix = ev.id.substringBefore('-')
            val n = ev.id.substringAfter('-').toLongOrNull() ?: continue
            val cur = conn.prepareStatement("SELECT next FROM counters WHERE prefix=?").use { ps ->
                ps.setString(1, prefix); ps.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else 1L }
            }
            if (n + 1 > cur) conn.prepareStatement(
                "INSERT INTO counters(prefix,next) VALUES(?,?) ON CONFLICT(prefix) DO UPDATE SET next=excluded.next"
            ).use { ps -> ps.setString(1, prefix); ps.setLong(2, n + 1); ps.executeUpdate() }
        }
        val res = doc["results"]?.jsonArrayOrNull() ?: emptyList()
        for (r in res) {
            val o = r.jsonObject
            saveResult(o["id"]!!.jsonPrimitive.content, o["createdSeq"]!!.jsonPrimitive.long,
                o["fingerprint"]!!.jsonPrimitive.content, o["algo"]!!.jsonPrimitive.content,
                o["sources"]!!.jsonArray.map { it.jsonPrimitive.content }, o["payload"]!!.jsonObject)
        }
        restale()
        return errors
    }

    override fun close() { conn.close() }

    private fun JsonElement.jsonArrayOrNull(): JsonArray? = this as? JsonArray
}
