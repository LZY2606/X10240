package workbench

import kotlinx.serialization.json.*
import java.sql.Connection
import java.sql.DriverManager

interface Clock { fun now(): String }
object SystemClock : Clock { override fun now(): String = java.time.Instant.now().toString() }
class MutableClock(var current: String = "2026-01-01T00:00:00Z") : Clock {
    override fun now(): String = current
    fun advance(seconds: Long) {
        current = java.time.Instant.parse(current).plusSeconds(seconds).toString()
    }
}

// ---------------------------------------------------------------------------
// Record types mirroring the SQLite tables.
// ---------------------------------------------------------------------------

data class SourceRecord(
    val id: String, val kind: String, val version: Int, val parent: String?,
    val raw: JsonObject, val status: String, val seq: Long, val time: String
)

data class ResultRecord(
    val id: String, val kind: String, val algo: String, val fingerprint: String,
    val sources: List<String>, val payload: JsonObject, val status: String,
    val seq: Long, val time: String
)

data class DecisionRecord(
    val id: String, val kind: String, val targets: List<String>, val rationale: String,
    val actor: String, val status: String, val undoneBy: String?, val payload: JsonObject,
    val seq: Long, val time: String
)

data class EventRecord(
    val seq: Long, val id: String, val time: String, val actor: String,
    val type: String, val payload: JsonObject
)

// ---------------------------------------------------------------------------
// SQLite store. Identities are deterministic (prefix + counter kept in meta),
// so export -> delete -> import preserves identity, order and provenance.
// ---------------------------------------------------------------------------

class Db(path: String) {
    val conn: Connection = DriverManager.getConnection("jdbc:sqlite:$path")

    init {
        conn.createStatement().use { st ->
            st.executeUpdate("PRAGMA journal_mode=WAL")
            st.executeUpdate("CREATE TABLE IF NOT EXISTS meta(k TEXT PRIMARY KEY, v TEXT NOT NULL)")
            st.executeUpdate(
                "CREATE TABLE IF NOT EXISTS sources(" +
                    "id TEXT PRIMARY KEY, kind TEXT NOT NULL, version INTEGER NOT NULL," +
                    "parent TEXT, raw TEXT NOT NULL, status TEXT NOT NULL," +
                    "seq INTEGER NOT NULL, time TEXT NOT NULL)"
            )
            st.executeUpdate(
                "CREATE TABLE IF NOT EXISTS results(" +
                    "id TEXT PRIMARY KEY, kind TEXT NOT NULL, algo TEXT NOT NULL," +
                    "fingerprint TEXT NOT NULL, sources TEXT NOT NULL, payload TEXT NOT NULL," +
                    "status TEXT NOT NULL, seq INTEGER NOT NULL, time TEXT NOT NULL)"
            )
            st.executeUpdate(
                "CREATE TABLE IF NOT EXISTS decisions(" +
                    "id TEXT PRIMARY KEY, kind TEXT NOT NULL, targets TEXT NOT NULL," +
                    "rationale TEXT NOT NULL, actor TEXT NOT NULL, status TEXT NOT NULL," +
                    "undone_by TEXT, payload TEXT NOT NULL, seq INTEGER NOT NULL, time TEXT NOT NULL)"
            )
            st.executeUpdate(
                "CREATE TABLE IF NOT EXISTS events(" +
                    "seq INTEGER PRIMARY KEY, id TEXT NOT NULL, time TEXT NOT NULL," +
                    "actor TEXT NOT NULL, type TEXT NOT NULL, payload TEXT NOT NULL)"
            )
        }
    }

    fun close() = conn.close()

    fun nextSeq(): Long = nextCounter("seq")
    fun nextId(prefix: String): String = "%s-%04d".format(prefix, nextCounter("id-$prefix"))

    private fun nextCounter(key: String): Long {
        val cur = conn.prepareStatement("SELECT v FROM meta WHERE k=?").use { ps ->
            ps.setString(1, key)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1).toLong() else 0L }
        }
        val next = cur + 1
        conn.prepareStatement(
            "INSERT INTO meta(k,v) VALUES(?,?) ON CONFLICT(k) DO UPDATE SET v=excluded.v"
        ).use { ps ->
            ps.setString(1, key); ps.setString(2, next.toString()); ps.executeUpdate()
        }
        return next
    }

    fun setCounter(key: String, value: Long) {
        conn.prepareStatement(
            "INSERT INTO meta(k,v) VALUES(?,?) ON CONFLICT(k) DO UPDATE SET v=excluded.v"
        ).use { ps -> ps.setString(1, key); ps.setString(2, value.toString()); ps.executeUpdate() }
    }

    fun getCounter(key: String): Long =
        conn.prepareStatement("SELECT v FROM meta WHERE k=?").use { ps ->
            ps.setString(1, key)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1).toLong() else 0L }
        }

    // ---- inserts -----------------------------------------------------------

    fun insertSource(s: SourceRecord) {
        conn.prepareStatement(
            "INSERT INTO sources(id,kind,version,parent,raw,status,seq,time) VALUES(?,?,?,?,?,?,?,?)"
        ).use { ps ->
            ps.setString(1, s.id); ps.setString(2, s.kind); ps.setInt(3, s.version)
            if (s.parent == null) ps.setNull(4, java.sql.Types.VARCHAR) else ps.setString(4, s.parent)
            ps.setString(5, s.raw.toString()); ps.setString(6, s.status)
            ps.setLong(7, s.seq); ps.setString(8, s.time)
            ps.executeUpdate()
        }
    }

    fun insertResult(r: ResultRecord) {
        conn.prepareStatement(
            "INSERT INTO results(id,kind,algo,fingerprint,sources,payload,status,seq,time) VALUES(?,?,?,?,?,?,?,?,?)"
        ).use { ps ->
            ps.setString(1, r.id); ps.setString(2, r.kind); ps.setString(3, r.algo)
            ps.setString(4, r.fingerprint); ps.setString(5, JsonArray(r.sources.map { JsonPrimitive(it) }).toString())
            ps.setString(6, r.payload.toString()); ps.setString(7, r.status)
            ps.setLong(8, r.seq); ps.setString(9, r.time)
            ps.executeUpdate()
        }
    }

    fun insertDecision(d: DecisionRecord) {
        conn.prepareStatement(
            "INSERT INTO decisions(id,kind,targets,rationale,actor,status,undone_by,payload,seq,time) VALUES(?,?,?,?,?,?,?,?,?,?)"
        ).use { ps ->
            ps.setString(1, d.id); ps.setString(2, d.kind)
            ps.setString(3, JsonArray(d.targets.map { JsonPrimitive(it) }).toString())
            ps.setString(4, d.rationale); ps.setString(5, d.actor); ps.setString(6, d.status)
            if (d.undoneBy == null) ps.setNull(7, java.sql.Types.VARCHAR) else ps.setString(7, d.undoneBy)
            ps.setString(8, d.payload.toString()); ps.setLong(9, d.seq); ps.setString(10, d.time)
            ps.executeUpdate()
        }
    }

    fun insertEvent(e: EventRecord) {
        conn.prepareStatement(
            "INSERT INTO events(seq,id,time,actor,type,payload) VALUES(?,?,?,?,?,?)"
        ).use { ps ->
            ps.setLong(1, e.seq); ps.setString(2, e.id); ps.setString(3, e.time)
            ps.setString(4, e.actor); ps.setString(5, e.type); ps.setString(6, e.payload.toString())
            ps.executeUpdate()
        }
    }

    fun updateResultStatus(id: String, status: String) {
        conn.prepareStatement("UPDATE results SET status=? WHERE id=?").use { ps ->
            ps.setString(1, status); ps.setString(2, id); ps.executeUpdate()
        }
    }

    fun updateDecisionUndone(id: String, undoneBy: String, status: String) {
        conn.prepareStatement("UPDATE decisions SET undone_by=?, status=? WHERE id=?").use { ps ->
            ps.setString(1, undoneBy); ps.setString(2, status); ps.setString(3, id); ps.executeUpdate()
        }
    }

    // ---- queries -----------------------------------------------------------

    fun sources(): List<SourceRecord> =
        conn.createStatement().use { st ->
            st.executeQuery("SELECT * FROM sources ORDER BY seq").use { rs ->
                generateSequence { if (rs.next()) rowToSource(rs) else null }.toList()
            }
        }

    fun source(id: String): SourceRecord? =
        conn.prepareStatement("SELECT * FROM sources WHERE id=?").use { ps ->
            ps.setString(1, id)
            ps.executeQuery().use { rs -> if (rs.next()) rowToSource(rs) else null }
        }

    fun results(): List<ResultRecord> =
        conn.createStatement().use { st ->
            st.executeQuery("SELECT * FROM results ORDER BY seq").use { rs ->
                generateSequence { if (rs.next()) rowToResult(rs) else null }.toList()
            }
        }

    fun result(id: String): ResultRecord? =
        conn.prepareStatement("SELECT * FROM results WHERE id=?").use { ps ->
            ps.setString(1, id)
            ps.executeQuery().use { rs -> if (rs.next()) rowToResult(rs) else null }
        }

    fun decisions(): List<DecisionRecord> =
        conn.createStatement().use { st ->
            st.executeQuery("SELECT * FROM decisions ORDER BY seq").use { rs ->
                generateSequence { if (rs.next()) rowToDecision(rs) else null }.toList()
            }
        }

    fun decision(id: String): DecisionRecord? =
        conn.prepareStatement("SELECT * FROM decisions WHERE id=?").use { ps ->
            ps.setString(1, id)
            ps.executeQuery().use { rs -> if (rs.next()) rowToDecision(rs) else null }
        }

    fun events(): List<EventRecord> =
        conn.createStatement().use { st ->
            st.executeQuery("SELECT * FROM events ORDER BY seq").use { rs ->
                generateSequence { if (rs.next()) rowToEvent(rs) else null }.toList()
            }
        }

    fun event(seq: Long): EventRecord? =
        conn.prepareStatement("SELECT * FROM events WHERE seq=?").use { ps ->
            ps.setLong(1, seq)
            ps.executeQuery().use { rs -> if (rs.next()) rowToEvent(rs) else null }
        }

    private fun rowToSource(rs: java.sql.ResultSet) = SourceRecord(
        rs.getString("id"), rs.getString("kind"), rs.getInt("version"), rs.getString("parent"),
        Json.parseToJsonElement(rs.getString("raw")).jsonObject, rs.getString("status"),
        rs.getLong("seq"), rs.getString("time")
    )

    private fun rowToResult(rs: java.sql.ResultSet) = ResultRecord(
        rs.getString("id"), rs.getString("kind"), rs.getString("algo"), rs.getString("fingerprint"),
        Json.parseToJsonElement(rs.getString("sources")).jsonArray.map { it.jsonPrimitive.content },
        Json.parseToJsonElement(rs.getString("payload")).jsonObject, rs.getString("status"),
        rs.getLong("seq"), rs.getString("time")
    )

    private fun rowToDecision(rs: java.sql.ResultSet) = DecisionRecord(
        rs.getString("id"), rs.getString("kind"),
        Json.parseToJsonElement(rs.getString("targets")).jsonArray.map { it.jsonPrimitive.content },
        rs.getString("rationale"), rs.getString("actor"), rs.getString("status"),
        rs.getString("undone_by"), Json.parseToJsonElement(rs.getString("payload")).jsonObject,
        rs.getLong("seq"), rs.getString("time")
    )

    private fun rowToEvent(rs: java.sql.ResultSet) = EventRecord(
        rs.getLong("seq"), rs.getString("id"), rs.getString("time"), rs.getString("actor"),
        rs.getString("type"), Json.parseToJsonElement(rs.getString("payload")).jsonObject
    )

    // ---- export / import ---------------------------------------------------

    fun exportJson(): JsonObject = buildJsonObject {
        put("format", "radiocarbon-workbench-export-1")
        putJsonArray("meta") {
            conn.createStatement().use { st ->
                st.executeQuery("SELECT k,v FROM meta ORDER BY k").use { rs ->
                    while (rs.next()) add(buildJsonObject {
                        put("k", rs.getString(1)); put("v", rs.getString(2))
                    })
                }
            }
        }
        putJsonArray("sources") { sources().forEach { s -> add(buildJsonObject {
            put("id", s.id); put("kind", s.kind); put("version", s.version)
            put("parent", s.parent); put("raw", s.raw); put("status", s.status)
            put("seq", s.seq); put("time", s.time)
        }) } }
        putJsonArray("results") { results().forEach { r -> add(buildJsonObject {
            put("id", r.id); put("kind", r.kind); put("algo", r.algo)
            put("fingerprint", r.fingerprint); put("sources", JsonArray(r.sources.map { JsonPrimitive(it) }))
            put("payload", r.payload); put("status", r.status); put("seq", r.seq); put("time", r.time)
        }) } }
        putJsonArray("decisions") { decisions().forEach { d -> add(buildJsonObject {
            put("id", d.id); put("kind", d.kind)
            put("targets", JsonArray(d.targets.map { JsonPrimitive(it) }))
            put("rationale", d.rationale); put("actor", d.actor); put("status", d.status)
            put("undone_by", d.undoneBy); put("payload", d.payload); put("seq", d.seq); put("time", d.time)
        }) } }
        putJsonArray("events") { events().forEach { e -> add(buildJsonObject {
            put("seq", e.seq); put("id", e.id); put("time", e.time)
            put("actor", e.actor); put("type", e.type); put("payload", e.payload)
        }) } }
    }

    /** Import into an empty database, preserving identity, order and provenance. */
    fun importJson(doc: JsonObject) {
        require(doc["format"]?.jsonPrimitive?.content == "radiocarbon-workbench-export-1") {
            "unrecognized export format"
        }
        conn.autoCommit = false
        try {
            for (m in doc["meta"]!!.jsonArray) {
                val o = m.jsonObject
                setCounter(o["k"]!!.jsonPrimitive.content, o["v"]!!.jsonPrimitive.content.toLong())
            }
            for (el in doc["sources"]!!.jsonArray) {
                val o = el.jsonObject
                insertSource(SourceRecord(
                    o["id"]!!.jsonPrimitive.content, o["kind"]!!.jsonPrimitive.content,
                    o["version"]!!.jsonPrimitive.int, o["parent"]?.jsonPrimitive?.contentOrNull,
                    o["raw"]!!.jsonObject, o["status"]!!.jsonPrimitive.content,
                    o["seq"]!!.jsonPrimitive.long, o["time"]!!.jsonPrimitive.content))
            }
            for (el in doc["results"]!!.jsonArray) {
                val o = el.jsonObject
                insertResult(ResultRecord(
                    o["id"]!!.jsonPrimitive.content, o["kind"]!!.jsonPrimitive.content,
                    o["algo"]!!.jsonPrimitive.content, o["fingerprint"]!!.jsonPrimitive.content,
                    o["sources"]!!.jsonArray.map { it.jsonPrimitive.content },
                    o["payload"]!!.jsonObject, o["status"]!!.jsonPrimitive.content,
                    o["seq"]!!.jsonPrimitive.long, o["time"]!!.jsonPrimitive.content))
            }
            for (el in doc["decisions"]!!.jsonArray) {
                val o = el.jsonObject
                insertDecision(DecisionRecord(
                    o["id"]!!.jsonPrimitive.content, o["kind"]!!.jsonPrimitive.content,
                    o["targets"]!!.jsonArray.map { it.jsonPrimitive.content },
                    o["rationale"]!!.jsonPrimitive.content, o["actor"]!!.jsonPrimitive.content,
                    o["status"]!!.jsonPrimitive.content, o["undone_by"]?.jsonPrimitive?.contentOrNull,
                    o["payload"]!!.jsonObject, o["seq"]!!.jsonPrimitive.long, o["time"]!!.jsonPrimitive.content))
            }
            for (el in doc["events"]!!.jsonArray) {
                val o = el.jsonObject
                insertEvent(EventRecord(
                    o["seq"]!!.jsonPrimitive.long, o["id"]!!.jsonPrimitive.content,
                    o["time"]!!.jsonPrimitive.content, o["actor"]!!.jsonPrimitive.content,
                    o["type"]!!.jsonPrimitive.content, o["payload"]!!.jsonObject))
            }
            conn.commit()
        } catch (e: Exception) {
            conn.rollback()
            throw e
        } finally {
            conn.autoCommit = true
        }
    }
}
