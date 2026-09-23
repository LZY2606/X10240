package gsb.store

import kotlinx.serialization.json.*
import java.sql.Connection
import java.sql.DriverManager

data class EventRecord(
    val seq: Long,
    val id: String,
    val type: String,
    val actor: String,
    val at: String,
    val payload: JsonObject,
)

data class InputRecord(
    val id: String,
    val stream: String,
    val kind: String,
    val versionNo: Int,
    val parentId: String?,
    val sourceRef: String,
    val payload: JsonObject,
    val createdSeq: Long,
)

data class ResultRecord(
    val id: String,
    val kind: String,
    val sampleId: String,
    val algVersion: String,
    val curveVersion: String,
    val fingerprint: String,
    val sources: List<String>,
    val payload: JsonObject,
    val status: String, // CURRENT | STALE
    val createdSeq: Long,
    val staledSeq: Long?,
)

object ResultKind {
    const val CALIBRATION = "CALIBRATION"
    const val STRAT_CONFLICT = "STRAT_CONFLICT"
}

/** SQLite-backed event store. Raw inputs are immutable once submitted;
 *  revisions are new versions linked by parent. Every derived result carries
 *  algorithm version, parameter fingerprint and direct source ids. */
class Store(dbPath: String, private val clock: Clock) : AutoCloseable {
    val conn: Connection = DriverManager.getConnection("jdbc:sqlite:$dbPath")

    init {
        conn.createStatement().use { st ->
            st.executeUpdate("PRAGMA journal_mode=WAL")
            st.executeUpdate(
                """CREATE TABLE IF NOT EXISTS meta(k TEXT PRIMARY KEY, v TEXT)"""
            )
            st.executeUpdate(
                """CREATE TABLE IF NOT EXISTS events(
                     seq INTEGER PRIMARY KEY, id TEXT UNIQUE, type TEXT,
                     actor TEXT, at TEXT, payload TEXT)"""
            )
            st.executeUpdate(
                """CREATE TABLE IF NOT EXISTS inputs(
                     id TEXT PRIMARY KEY, stream TEXT, kind TEXT,
                     version_no INTEGER, parent_id TEXT, source_ref TEXT,
                     payload TEXT, created_seq INTEGER)"""
            )
            st.executeUpdate(
                """CREATE TABLE IF NOT EXISTS results(
                     id TEXT PRIMARY KEY, kind TEXT, sample_id TEXT,
                     alg_version TEXT, curve_version TEXT, fingerprint TEXT,
                     sources TEXT, payload TEXT, status TEXT,
                     created_seq INTEGER, staled_seq INTEGER)"""
            )
        }
    }

    override fun close() = conn.close()

    fun nextId(prefix: String): String {
        val cur = conn.prepareStatement("SELECT v FROM meta WHERE k=?").use { ps ->
            ps.setString(1, "seq:$prefix")
            val rs = ps.executeQuery()
            if (rs.next()) rs.getString(1).toLong() else 0L
        }
        val nxt = cur + 1
        conn.prepareStatement(
            "INSERT INTO meta(k,v) VALUES(?,?) ON CONFLICT(k) DO UPDATE SET v=excluded.v"
        ).use { ps ->
            ps.setString(1, "seq:$prefix"); ps.setString(2, nxt.toString()); ps.executeUpdate()
        }
        return "$prefix-%04d".format(nxt)
    }

    fun appendEvent(type: String, actor: String, payload: JsonObject): EventRecord {
        val id = nextId("evt")
        val at = clock.now().toString()
        conn.prepareStatement(
            "INSERT INTO events(id,type,actor,at,payload) VALUES(?,?,?,?,?)"
        ).use { ps ->
            ps.setString(1, id); ps.setString(2, type); ps.setString(3, actor)
            ps.setString(4, at); ps.setString(5, payload.toString())
            ps.executeUpdate()
        }
        val seq = conn.createStatement().use { st ->
            val rs = st.executeQuery("SELECT seq FROM events WHERE id='$id'")
            rs.next(); rs.getLong(1)
        }
        return EventRecord(seq, id, type, actor, at, payload)
    }

    fun events(): List<EventRecord> =
        conn.createStatement().use { st ->
            val rs = st.executeQuery("SELECT seq,id,type,actor,at,payload FROM events ORDER BY seq")
            val out = mutableListOf<EventRecord>()
            while (rs.next()) out += EventRecord(
                rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getString(5), Json.parseToJsonElement(rs.getString(6)).jsonObject,
            )
            out
        }

    fun submitInput(stream: String, kind: String, payload: JsonObject, sourceRef: String): InputRecord {
        val parent = latestInput(stream)
        val id = nextId("inp")
        val versionNo = (parent?.versionNo ?: 0) + 1
        val seq = appendEvent("input.submitted", "system", buildJsonObject {
            put("stream", stream); put("inputId", id); put("kind", kind)
            put("versionNo", versionNo); put("parentId", parent?.id ?: JsonNull)
            put("sourceRef", sourceRef)
        }).seq
        conn.prepareStatement(
            "INSERT INTO inputs(id,stream,kind,version_no,parent_id,source_ref,payload,created_seq) VALUES(?,?,?,?,?,?,?,?)"
        ).use { ps ->
            ps.setString(1, id); ps.setString(2, stream); ps.setString(3, kind)
            ps.setInt(4, versionNo); ps.setString(5, parent?.id)
            ps.setString(6, sourceRef); ps.setString(7, payload.toString()); ps.setLong(8, seq)
            ps.executeUpdate()
        }
        return InputRecord(id, stream, kind, versionNo, parent?.id, sourceRef, payload, seq)
    }

    fun latestInput(stream: String): InputRecord? =
        conn.prepareStatement(
            "SELECT id,stream,kind,version_no,parent_id,source_ref,payload,created_seq FROM inputs WHERE stream=? ORDER BY version_no DESC LIMIT 1"
        ).use { ps ->
            ps.setString(1, stream)
            val rs = ps.executeQuery()
            if (!rs.next()) null else InputRecord(
                rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4),
                rs.getString(5), rs.getString(6),
                Json.parseToJsonElement(rs.getString(7)).jsonObject, rs.getLong(8),
            )
        }

    fun inputs(stream: String): List<InputRecord> =
        conn.prepareStatement(
            "SELECT id,stream,kind,version_no,parent_id,source_ref,payload,created_seq FROM inputs WHERE stream=? ORDER BY version_no"
        ).use { ps ->
            ps.setString(1, stream)
            val rs = ps.executeQuery()
            val out = mutableListOf<InputRecord>()
            while (rs.next()) out += InputRecord(
                rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4),
                rs.getString(5), rs.getString(6),
                Json.parseToJsonElement(rs.getString(7)).jsonObject, rs.getLong(8),
            )
            out
        }

    fun allStreams(): List<String> =
        conn.createStatement().use { st ->
            val rs = st.executeQuery("SELECT DISTINCT stream FROM inputs ORDER BY stream")
            val out = mutableListOf<String>()
            while (rs.next()) out += rs.getString(1)
            out
        }

    fun saveResult(
        kind: String, sampleId: String, algVersion: String, curveVersion: String,
        fingerprint: String, sources: List<String>, payload: JsonObject, createdSeq: Long,
    ): String {
        val id = nextId("res")
        conn.prepareStatement(
            "INSERT INTO results(id,kind,sample_id,alg_version,curve_version,fingerprint,sources,payload,status,created_seq,staled_seq) VALUES(?,?,?,?,?,?,?,?,?,?,NULL)"
        ).use { ps ->
            ps.setString(1, id); ps.setString(2, kind); ps.setString(3, sampleId)
            ps.setString(4, algVersion); ps.setString(5, curveVersion); ps.setString(6, fingerprint)
            ps.setString(7, JsonArray(sources.map { JsonPrimitive(it) }).toString())
            ps.setString(8, payload.toString()); ps.setString(9, "CURRENT"); ps.setLong(10, createdSeq)
            ps.executeUpdate()
        }
        return id
    }

    fun results(): List<ResultRecord> =
        conn.createStatement().use { st ->
            val rs = st.executeQuery("SELECT id,kind,sample_id,alg_version,curve_version,fingerprint,sources,payload,status,created_seq,staled_seq FROM results ORDER BY id")
            val out = mutableListOf<ResultRecord>()
            while (rs.next()) out += ResultRecord(
                rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getString(5), rs.getString(6),
                Json.parseToJsonElement(rs.getString(7)).jsonArray.map { it.jsonPrimitive.content },
                Json.parseToJsonElement(rs.getString(8)).jsonObject,
                rs.getString(9), rs.getLong(10),
                if (rs.getObject(11) == null) null else rs.getLong(11),
            )
            out
        }

    /** Mark results depending on any version of [stream] as STALE. They stay
     *  readable but can no longer pose as current. Returns affected ids. */
    fun staleByStream(stream: String, eventSeq: Long): List<String> {
        val streamInputIds = inputs(stream).map { it.id }.toSet()
        val affected = mutableListOf<String>()
        for (r in results()) {
            if (r.status != "CURRENT") continue
            if (r.sources.any { it in streamInputIds }) {
                conn.prepareStatement("UPDATE results SET status='STALE', staled_seq=? WHERE id=?").use { ps ->
                    ps.setLong(1, eventSeq); ps.setString(2, r.id); ps.executeUpdate()
                }
                affected += r.id
            }
        }
        return affected
    }

    fun exportJson(): JsonObject {
        val ev = events()
        return buildJsonObject {
            put("format", "radiocarbon-workbench-export-v1")
            putJsonArray("events") {
                ev.forEach { e ->
                    add(buildJsonObject {
                        put("seq", e.seq); put("id", e.id); put("type", e.type)
                        put("actor", e.actor); put("at", e.at); put("payload", e.payload)
                    })
                }
            }
            putJsonArray("inputs") {
                allStreams().flatMap { inputs(it) }.forEach { i ->
                    add(buildJsonObject {
                        put("id", i.id); put("stream", i.stream); put("kind", i.kind)
                        put("versionNo", i.versionNo); put("parentId", i.parentId ?: JsonNull)
                        put("sourceRef", i.sourceRef); put("payload", i.payload)
                        put("createdSeq", i.createdSeq)
                    })
                }
            }
            putJsonArray("results") {
                results().forEach { r ->
                    add(buildJsonObject {
                        put("id", r.id); put("kind", r.kind); put("sampleId", r.sampleId)
                        put("algVersion", r.algVersion); put("curveVersion", r.curveVersion)
                        put("fingerprint", r.fingerprint)
                        putJsonArray("sources") { r.sources.forEach { add(it) } }
                        put("payload", r.payload); put("status", r.status)
                        put("createdSeq", r.createdSeq)
                        put("staledSeq", r.staledSeq ?: JsonNull)
                    })
                }
            }
            putJsonObject("counters") {
                conn.createStatement().use { st ->
                    val rs = st.executeQuery("SELECT k,v FROM meta WHERE k LIKE 'seq:%'")
                    while (rs.next()) put(rs.getString(1), rs.getString(2))
                }
            }
        }
    }

    /** Wipe and rebuild from an export. Identities, order and provenance
     *  links are preserved verbatim. */
    fun importJson(doc: JsonObject) {
        conn.createStatement().use { st ->
            st.executeUpdate("DELETE FROM events"); st.executeUpdate("DELETE FROM inputs")
            st.executeUpdate("DELETE FROM results"); st.executeUpdate("DELETE FROM meta")
        }
        for (e in doc["events"]!!.jsonArray) {
            val o = e.jsonObject
            conn.prepareStatement("INSERT INTO events(seq,id,type,actor,at,payload) VALUES(?,?,?,?,?,?)").use { ps ->
                ps.setLong(1, o["seq"]!!.jsonPrimitive.long)
                ps.setString(2, o["id"]!!.jsonPrimitive.content)
                ps.setString(3, o["type"]!!.jsonPrimitive.content)
                ps.setString(4, o["actor"]!!.jsonPrimitive.content)
                ps.setString(5, o["at"]!!.jsonPrimitive.content)
                ps.setString(6, o["payload"]!!.jsonObject.toString())
                ps.executeUpdate()
            }
        }
        for (i in doc["inputs"]!!.jsonArray) {
            val o = i.jsonObject
            conn.prepareStatement("INSERT INTO inputs(id,stream,kind,version_no,parent_id,source_ref,payload,created_seq) VALUES(?,?,?,?,?,?,?,?)").use { ps ->
                ps.setString(1, o["id"]!!.jsonPrimitive.content)
                ps.setString(2, o["stream"]!!.jsonPrimitive.content)
                ps.setString(3, o["kind"]!!.jsonPrimitive.content)
                ps.setInt(4, o["versionNo"]!!.jsonPrimitive.int)
                val parent = o["parentId"]!!
                ps.setString(5, if (parent is JsonNull) null else parent.jsonPrimitive.content)
                ps.setString(6, o["sourceRef"]!!.jsonPrimitive.content)
                ps.setString(7, o["payload"]!!.jsonObject.toString())
                ps.setLong(8, o["createdSeq"]!!.jsonPrimitive.long)
                ps.executeUpdate()
            }
        }
        for (r in doc["results"]!!.jsonArray) {
            val o = r.jsonObject
            conn.prepareStatement("INSERT INTO results(id,kind,sample_id,alg_version,curve_version,fingerprint,sources,payload,status,created_seq,staled_seq) VALUES(?,?,?,?,?,?,?,?,?,?,?)").use { ps ->
                ps.setString(1, o["id"]!!.jsonPrimitive.content)
                ps.setString(2, o["kind"]!!.jsonPrimitive.content)
                ps.setString(3, o["sampleId"]!!.jsonPrimitive.content)
                ps.setString(4, o["algVersion"]!!.jsonPrimitive.content)
                ps.setString(5, o["curveVersion"]!!.jsonPrimitive.content)
                ps.setString(6, o["fingerprint"]!!.jsonPrimitive.content)
                ps.setString(7, o["sources"]!!.jsonArray.toString())
                ps.setString(8, o["payload"]!!.jsonObject.toString())
                ps.setString(9, o["status"]!!.jsonPrimitive.content)
                ps.setLong(10, o["createdSeq"]!!.jsonPrimitive.long)
                val staled = o["staledSeq"]!!
                if (staled is JsonNull) ps.setNull(11, java.sql.Types.INTEGER) else ps.setLong(11, staled.jsonPrimitive.long)
                ps.executeUpdate()
            }
        }
        for ((k, v) in doc["counters"]!!.jsonObject) {
            conn.prepareStatement("INSERT INTO meta(k,v) VALUES(?,?)").use { ps ->
                ps.setString(1, k); ps.setString(2, v.jsonPrimitive.content); ps.executeUpdate()
            }
        }
    }
}
