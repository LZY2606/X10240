package workbench

import kotlinx.serialization.json.*
import java.sql.Connection
import java.sql.DriverManager

interface Clock { fun now(): String }
class SystemClock : Clock { override fun now(): String = java.time.Instant.now().toString() }
class FixedClock(start: String = "2026-01-01T00:00:00Z") : Clock {
    private var tick = 0
    private val base = start.removeSuffix("Z")
    override fun now(): String = synchronized(this) {
        val t = java.time.LocalDateTime.parse(base).plusSeconds(tick.toLong())
        tick++
        t.toString() + "Z"
    }
}

data class EventRow(val seq: Long, val id: String, val type: String, val at: String, val payload: JsonObject)
data class SourceRow(val id: String, val version: Int, val kind: String, val parentId: String?,
                     val parentVersion: Int?, val fingerprint: String, val payload: JsonObject, val createdSeq: Long)
data class ResultRow(val id: String, val kind: String, val algoVersion: String, val fingerprint: String,
                     val sourceRefs: List<String>, val payload: JsonObject, val createdSeq: Long,
                     val stale: Boolean, val staleReason: String?)
data class DecisionRow(val id: String, val targetId: String, val action: String, val rationale: String,
                       val reviewer: String, val createdSeq: Long, val undoneSeq: Long?)

class Store(dbPath: String, val clock: Clock = SystemClock()) : AutoCloseable {
    val conn: Connection = DriverManager.getConnection("jdbc:sqlite:$dbPath")
    val json = Json { prettyPrint = false }

    init {
        conn.createStatement().use { st ->
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS event(
                  seq INTEGER PRIMARY KEY,
                  id TEXT UNIQUE NOT NULL,
                  type TEXT NOT NULL,
                  at TEXT NOT NULL,
                  payload TEXT NOT NULL)""")
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS source(
                  id TEXT NOT NULL, version INTEGER NOT NULL,
                  kind TEXT NOT NULL, parent_id TEXT, parent_version INTEGER,
                  fingerprint TEXT NOT NULL, payload TEXT NOT NULL, created_seq INTEGER NOT NULL,
                  PRIMARY KEY(id, version))""")
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS result(
                  id TEXT PRIMARY KEY, kind TEXT NOT NULL, algo_version TEXT NOT NULL,
                  fingerprint TEXT NOT NULL, source_refs TEXT NOT NULL, payload TEXT NOT NULL,
                  created_seq INTEGER NOT NULL, stale INTEGER NOT NULL DEFAULT 0, stale_reason TEXT)""")
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS decision(
                  id TEXT PRIMARY KEY, target_id TEXT NOT NULL, action TEXT NOT NULL,
                  rationale TEXT NOT NULL, reviewer TEXT NOT NULL,
                  created_seq INTEGER NOT NULL, undone_seq INTEGER)""")
        }
    }

    override fun close() { conn.close() }

    fun nextSeq(): Long = conn.createStatement().use { st ->
        st.executeQuery("SELECT COALESCE(MAX(seq),0)+1 FROM event").let { rs -> rs.next(); rs.getLong(1) }
    }

    fun appendEvent(type: String, payload: JsonObject, forcedSeq: Long? = null, forcedId: String? = null, forcedAt: String? = null): EventRow {
        val seq = forcedSeq ?: nextSeq()
        val id = forcedId ?: "evt-%06d".format(seq)
        val at = forcedAt ?: clock.now()
        conn.prepareStatement("INSERT INTO event(seq,id,type,at,payload) VALUES(?,?,?,?,?)").use { ps ->
            ps.setLong(1, seq); ps.setString(2, id); ps.setString(3, type); ps.setString(4, at)
            ps.setString(5, payload.toString()); ps.executeUpdate()
        }
        conn.prepareStatement("INSERT OR REPLACE INTO sqlite_sequence(name,seq) VALUES('event',?)").use { ps ->
            ps.setLong(1, maxOf(seq, currentMaxSeq())); ps.executeUpdate()
        }
        return EventRow(seq, id, type, at, payload)
    }

    private fun currentMaxSeq(): Long = conn.createStatement().use { st ->
        st.executeQuery("SELECT COALESCE(MAX(seq),0) FROM event").let { rs -> rs.next(); rs.getLong(1) }
    }

    fun events(): List<EventRow> = conn.createStatement().use { st ->
        st.executeQuery("SELECT seq,id,type,at,payload FROM event ORDER BY seq").let { rs ->
            val out = mutableListOf<EventRow>()
            while (rs.next()) out.add(EventRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                json.parseToJsonElement(rs.getString(5)).jsonObject))
            out
        }
    }

    fun event(seq: Long): EventRow? = events().firstOrNull { it.seq == seq }

    fun putSource(row: SourceRow) {
        conn.prepareStatement("INSERT INTO source(id,version,kind,parent_id,parent_version,fingerprint,payload,created_seq) VALUES(?,?,?,?,?,?,?,?)").use { ps ->
            ps.setString(1, row.id); ps.setInt(2, row.version); ps.setString(3, row.kind)
            if (row.parentId == null) ps.setNull(4, java.sql.Types.VARCHAR) else ps.setString(4, row.parentId)
            if (row.parentVersion == null) ps.setNull(5, java.sql.Types.INTEGER) else ps.setInt(5, row.parentVersion)
            ps.setString(6, row.fingerprint); ps.setString(7, row.payload.toString()); ps.setLong(8, row.createdSeq)
            ps.executeUpdate()
        }
    }

    fun sources(): List<SourceRow> = conn.createStatement().use { st ->
        st.executeQuery("SELECT id,version,kind,parent_id,parent_version,fingerprint,payload,created_seq FROM source ORDER BY id,version").let { rs ->
            val out = mutableListOf<SourceRow>()
            while (rs.next()) out.add(SourceRow(rs.getString(1), rs.getInt(2), rs.getString(3),
                rs.getString(4), if (rs.getObject(5) == null) null else rs.getInt(5),
                rs.getString(6), json.parseToJsonElement(rs.getString(7)).jsonObject, rs.getLong(8)))
            out
        }
    }

    fun source(id: String, version: Int): SourceRow? = sources().firstOrNull { it.id == id && it.version == version }
    fun latestSource(id: String): SourceRow? = sources().filter { it.id == id }.maxByOrNull { it.version }

    fun putResult(row: ResultRow) {
        conn.prepareStatement("INSERT INTO result(id,kind,algo_version,fingerprint,source_refs,payload,created_seq,stale,stale_reason) VALUES(?,?,?,?,?,?,?,?,?)").use { ps ->
            ps.setString(1, row.id); ps.setString(2, row.kind); ps.setString(3, row.algoVersion)
            ps.setString(4, row.fingerprint); ps.setString(5, row.sourceRefs.joinToString(","))
            ps.setString(6, row.payload.toString()); ps.setLong(7, row.createdSeq)
            ps.setInt(8, if (row.stale) 1 else 0)
            if (row.staleReason == null) ps.setNull(9, java.sql.Types.VARCHAR) else ps.setString(9, row.staleReason)
            ps.executeUpdate()
        }
    }

    fun results(): List<ResultRow> = conn.createStatement().use { st ->
        st.executeQuery("SELECT id,kind,algo_version,fingerprint,source_refs,payload,created_seq,stale,stale_reason FROM result ORDER BY created_seq").let { rs ->
            val out = mutableListOf<ResultRow>()
            while (rs.next()) out.add(ResultRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getString(5).split(",").filter { it.isNotBlank() },
                json.parseToJsonElement(rs.getString(6)).jsonObject, rs.getLong(7),
                rs.getInt(8) == 1, rs.getString(9)))
            out
        }
    }

    fun result(id: String): ResultRow? = results().firstOrNull { it.id == id }

    fun markResultStale(resultId: String, reason: String) {
        conn.prepareStatement("UPDATE result SET stale=1, stale_reason=? WHERE id=?").use { ps ->
            ps.setString(1, reason); ps.setString(2, resultId); ps.executeUpdate()
        }
    }

    fun putDecision(row: DecisionRow) {
        conn.prepareStatement("INSERT INTO decision(id,target_id,action,rationale,reviewer,created_seq,undone_seq) VALUES(?,?,?,?,?,?,?)").use { ps ->
            ps.setString(1, row.id); ps.setString(2, row.targetId); ps.setString(3, row.action)
            ps.setString(4, row.rationale); ps.setString(5, row.reviewer); ps.setLong(6, row.createdSeq)
            if (row.undoneSeq == null) ps.setNull(7, java.sql.Types.INTEGER) else ps.setLong(7, row.undoneSeq)
            ps.executeUpdate()
        }
    }

    fun decisions(): List<DecisionRow> = conn.createStatement().use { st ->
        st.executeQuery("SELECT id,target_id,action,rationale,reviewer,created_seq,undone_seq FROM decision ORDER BY created_seq").let { rs ->
            val out = mutableListOf<DecisionRow>()
            while (rs.next()) out.add(DecisionRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getString(5), rs.getLong(6), if (rs.getObject(7) == null) null else rs.getLong(7)))
            out
        }
    }

    fun markDecisionUndone(decisionId: String, seq: Long) {
        conn.prepareStatement("UPDATE decision SET undone_seq=? WHERE id=?").use { ps ->
            ps.setLong(1, seq); ps.setString(2, decisionId); ps.executeUpdate()
        }
    }

    fun wipe() {
        conn.createStatement().use { st ->
            st.executeUpdate("DELETE FROM event"); st.executeUpdate("DELETE FROM source")
            st.executeUpdate("DELETE FROM result"); st.executeUpdate("DELETE FROM decision")
            st.executeUpdate("DELETE FROM sqlite_sequence WHERE name='event'")
        }
    }

    fun exportJson(): JsonObject = buildJsonObject {
        put("format", "radiocarbon-workbench-export/1")
        put("exportedAt", clock.now())
        putJsonArray("events") { events().forEach { e -> add(buildJsonObject {
            put("seq", e.seq); put("id", e.id); put("type", e.type); put("at", e.at); put("payload", e.payload) }) } }
        putJsonArray("sources") { sources().forEach { s -> add(buildJsonObject {
            put("id", s.id); put("version", s.version); put("kind", s.kind)
            s.parentId?.let { put("parentId", it) }; s.parentVersion?.let { put("parentVersion", it) }
            put("fingerprint", s.fingerprint); put("payload", s.payload); put("createdSeq", s.createdSeq) }) } }
        putJsonArray("results") { results().forEach { r -> add(buildJsonObject {
            put("id", r.id); put("kind", r.kind); put("algoVersion", r.algoVersion)
            put("fingerprint", r.fingerprint); putJsonArray("sourceRefs") { r.sourceRefs.forEach { add(it) } }
            put("payload", r.payload); put("createdSeq", r.createdSeq)
            put("stale", r.stale); r.staleReason?.let { put("staleReason", it) } }) } }
        putJsonArray("decisions") { decisions().forEach { d -> add(buildJsonObject {
            put("id", d.id); put("targetId", d.targetId); put("action", d.action)
            put("rationale", d.rationale); put("reviewer", d.reviewer); put("createdSeq", d.createdSeq)
            d.undoneSeq?.let { put("undoneSeq", it) } }) } }
    }

    /** 导入保留全部身份、顺序与来源关系；不存在的表内容直接按原 seq 落库。 */
    fun importJson(root: JsonObject) {
        require(root["format"]?.jsonPrimitive?.content == "radiocarbon-workbench-export/1") { "unknown export format" }
        wipe()
        for (e in root["events"]!!.jsonArray) {
            val o = e.jsonObject
            appendEvent(o["type"]!!.jsonPrimitive.content, o["payload"]!!.jsonObject,
                forcedSeq = o["seq"]!!.jsonPrimitive.long,
                forcedId = o["id"]!!.jsonPrimitive.content,
                forcedAt = o["at"]!!.jsonPrimitive.content)
        }
        for (s in root["sources"]!!.jsonArray) {
            val o = s.jsonObject
            putSource(SourceRow(o["id"]!!.jsonPrimitive.content, o["version"]!!.jsonPrimitive.int,
                o["kind"]!!.jsonPrimitive.content, o["parentId"]?.jsonPrimitive?.content,
                o["parentVersion"]?.jsonPrimitive?.int, o["fingerprint"]!!.jsonPrimitive.content,
                o["payload"]!!.jsonObject, o["createdSeq"]!!.jsonPrimitive.long))
        }
        for (r in root["results"]!!.jsonArray) {
            val o = r.jsonObject
            putResult(ResultRow(o["id"]!!.jsonPrimitive.content, o["kind"]!!.jsonPrimitive.content,
                o["algoVersion"]!!.jsonPrimitive.content, o["fingerprint"]!!.jsonPrimitive.content,
                o["sourceRefs"]!!.jsonArray.map { it.jsonPrimitive.content }, o["payload"]!!.jsonObject,
                o["createdSeq"]!!.jsonPrimitive.long, o["stale"]!!.jsonPrimitive.boolean,
                o["staleReason"]?.jsonPrimitive?.content))
        }
        for (d in root["decisions"]!!.jsonArray) {
            val o = d.jsonObject
            putDecision(DecisionRow(o["id"]!!.jsonPrimitive.content, o["targetId"]!!.jsonPrimitive.content,
                o["action"]!!.jsonPrimitive.content, o["rationale"]!!.jsonPrimitive.content,
                o["reviewer"]!!.jsonPrimitive.content, o["createdSeq"]!!.jsonPrimitive.long,
                o["undoneSeq"]?.jsonPrimitive?.long))
        }
    }
}
