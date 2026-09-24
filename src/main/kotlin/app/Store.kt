package app

import kotlinx.serialization.json.*
import java.sql.Connection
import java.sql.DriverManager

class Store(val path: String) : AutoCloseable {
    val conn: Connection

    init {
        if (path != ":memory:") {
            val f = java.io.File(path)
            f.parentFile?.mkdirs()
        }
        conn = DriverManager.getConnection("jdbc:sqlite:$path")
        conn.createStatement().use { st ->
            st.executeUpdate("PRAGMA journal_mode=WAL")
            st.executeUpdate(
                """CREATE TABLE IF NOT EXISTS meta(k TEXT PRIMARY KEY, v TEXT)"""
            )
            st.executeUpdate(
                """CREATE TABLE IF NOT EXISTS sources(
                     id TEXT PRIMARY KEY, kind TEXT NOT NULL, version INTEGER NOT NULL,
                     parent_id TEXT, content TEXT NOT NULL, submitted_at TEXT NOT NULL, seq INTEGER NOT NULL)"""
            )
            st.executeUpdate(
                """CREATE TABLE IF NOT EXISTS results(
                     id TEXT PRIMARY KEY, kind TEXT NOT NULL, algo_version TEXT NOT NULL,
                     fingerprint TEXT NOT NULL, source_ids TEXT NOT NULL, content TEXT NOT NULL,
                     stale INTEGER NOT NULL DEFAULT 0, created_seq INTEGER NOT NULL)"""
            )
            st.executeUpdate(
                """CREATE TABLE IF NOT EXISTS events(
                     seq INTEGER PRIMARY KEY AUTOINCREMENT, id TEXT NOT NULL,
                     type TEXT NOT NULL, payload TEXT NOT NULL, at TEXT NOT NULL)"""
            )
        }
    }

    override fun close() = conn.close()

    fun metaGet(k: String): String? = conn.prepareStatement("SELECT v FROM meta WHERE k=?").use { ps ->
        ps.setString(1, k)
        val rs = ps.executeQuery()
        if (rs.next()) rs.getString(1) else null
    }

    fun metaPut(k: String, v: String) = conn.prepareStatement(
        "INSERT INTO meta(k,v) VALUES(?,?) ON CONFLICT(k) DO UPDATE SET v=excluded.v"
    ).use { ps -> ps.setString(1, k); ps.setString(2, v); ps.executeUpdate() }

    fun nextSeq(): Long = conn.createStatement().use { st ->
        val rs = st.executeQuery("SELECT COALESCE(MAX(seq),0)+1 FROM events")
        rs.next(); rs.getLong(1)
    }

    fun appendEvent(type: String, payload: JsonObject, at: String, id: String? = null): Event {
        val eventId = id ?: "ev-" + sha256("$type|${canonical(payload)}|$at|${nextSeq()}").take(12)
        conn.prepareStatement("INSERT INTO events(id,type,payload,at) VALUES(?,?,?,?)").use { ps ->
            ps.setString(1, eventId); ps.setString(2, type)
            ps.setString(3, JsonFmt.encodeToString(JsonObject.serializer(), payload)); ps.setString(4, at)
            ps.executeUpdate()
        }
        val seq = conn.createStatement().use { st ->
            val rs = st.executeQuery("SELECT last_insert_rowid()"); rs.next(); rs.getLong(1)
        }
        return Event(seq, eventId, type, payload, at)
    }

    fun events(): List<Event> = conn.createStatement().use { st ->
        val rs = st.executeQuery("SELECT seq,id,type,payload,at FROM events ORDER BY seq")
        val out = mutableListOf<Event>()
        while (rs.next()) out.add(
            Event(rs.getLong(1), rs.getString(2), rs.getString(3),
                JsonFmt.parseToJsonElement(rs.getString(4)).jsonObject, rs.getString(5))
        )
        out
    }

    fun insertSource(s: Source) = conn.prepareStatement(
        "INSERT INTO sources(id,kind,version,parent_id,content,submitted_at,seq) VALUES(?,?,?,?,?,?,?)"
    ).use { ps ->
        ps.setString(1, s.id); ps.setString(2, s.kind); ps.setInt(3, s.version)
        if (s.parentId == null) ps.setNull(4, java.sql.Types.VARCHAR) else ps.setString(4, s.parentId)
        ps.setString(5, JsonFmt.encodeToString(JsonObject.serializer(), s.content))
        ps.setString(6, s.submittedAt); ps.setLong(7, s.seq)
        ps.executeUpdate()
    }

    private fun sourceFrom(rs: java.sql.ResultSet) = Source(
        rs.getString("id"), rs.getString("kind"), rs.getInt("version"),
        rs.getString("parent_id"),
        JsonFmt.parseToJsonElement(rs.getString("content")).jsonObject,
        rs.getString("submitted_at"), rs.getLong("seq")
    )

    fun sources(): List<Source> = conn.createStatement().use { st ->
        val rs = st.executeQuery("SELECT * FROM sources ORDER BY seq, id")
        val out = mutableListOf<Source>()
        while (rs.next()) out.add(sourceFrom(rs))
        out
    }

    fun source(id: String): Source? = conn.prepareStatement("SELECT * FROM sources WHERE id=?").use { ps ->
        ps.setString(1, id)
        val rs = ps.executeQuery()
        if (rs.next()) sourceFrom(rs) else null
    }

    /** Latest version of each source line (line root = id chain via parent). */
    fun latestSources(): List<Source> {
        val all = sources()
        val byId = all.associateBy { it.id }
        fun root(s: Source): String = s.parentId?.let { byId[it] }?.let { root(it) } ?: s.id
        return all.groupBy { root(it) }.map { (_, versions) -> versions.maxBy { it.version } }
    }

    fun insertResult(r: DerivedResult) = conn.prepareStatement(
        "INSERT INTO results(id,kind,algo_version,fingerprint,source_ids,content,stale,created_seq) VALUES(?,?,?,?,?,?,?,?)"
    ).use { ps ->
        ps.setString(1, r.id); ps.setString(2, r.kind); ps.setString(3, r.algoVersion)
        ps.setString(4, r.paramsFingerprint)
        ps.setString(5, JsonFmt.encodeToString(JsonArray.serializer(), JsonArray(r.sourceIds.map { JsonPrimitive(it) })))
        ps.setString(6, JsonFmt.encodeToString(JsonObject.serializer(), r.content))
        ps.setInt(7, if (r.stale) 1 else 0); ps.setLong(8, r.createdSeq)
        ps.executeUpdate()
    }

    private fun resultFrom(rs: java.sql.ResultSet) = DerivedResult(
        rs.getString("id"), rs.getString("kind"), rs.getString("algo_version"),
        rs.getString("fingerprint"),
        JsonFmt.parseToJsonElement(rs.getString("source_ids")).jsonArray.map { it.jsonPrimitive.content },
        JsonFmt.parseToJsonElement(rs.getString("content")).jsonObject,
        rs.getInt("stale") != 0, rs.getLong("created_seq")
    )

    fun results(): List<DerivedResult> = conn.createStatement().use { st ->
        val rs = st.executeQuery("SELECT * FROM results ORDER BY created_seq, id")
        val out = mutableListOf<DerivedResult>()
        while (rs.next()) out.add(resultFrom(rs))
        out
    }

    fun result(id: String): DerivedResult? = conn.prepareStatement("SELECT * FROM results WHERE id=?").use { ps ->
        ps.setString(1, id)
        val rs = ps.executeQuery()
        if (rs.next()) resultFrom(rs) else null
    }

    fun markStale(resultId: String) = conn.prepareStatement("UPDATE results SET stale=1 WHERE id=?").use { ps ->
        ps.setString(1, resultId); ps.executeUpdate()
    }

    fun wipeAll() = conn.createStatement().use { st ->
        st.executeUpdate("DELETE FROM events")
        st.executeUpdate("DELETE FROM results")
        st.executeUpdate("DELETE FROM sources")
        st.executeUpdate("DELETE FROM meta")
    }

    // ---- export / import ----

    fun exportJson(): JsonObject {
        fun table(name: String, cols: List<String>): JsonArray {
            val arr = mutableListOf<JsonElement>()
            conn.createStatement().use { st ->
                val rs = st.executeQuery("SELECT * FROM $name ORDER BY rowid")
                while (rs.next()) {
                    val o = buildJsonObject {
                        cols.forEach { c ->
                            val v = rs.getObject(c)
                            when (v) {
                                null -> put(c, JsonNull)
                                is Int, is Long -> put(c, (v as Number).toLong())
                                else -> put(c, v.toString())
                            }
                        }
                    }
                    arr.add(o)
                }
            }
            return JsonArray(arr)
        }
        return buildJsonObject {
            put("format", EXPORT_FORMAT)
            put("formatVersion", EXPORT_VERSION)
            put("meta", table("meta", listOf("k", "v")))
            put("sources", table("sources", listOf("id", "kind", "version", "parent_id", "content", "submitted_at", "seq")))
            put("results", table("results", listOf("id", "kind", "algo_version", "fingerprint", "source_ids", "content", "stale", "created_seq")))
            put("events", table("events", listOf("seq", "id", "type", "payload", "at")))
        }
    }

    fun importJson(doc: JsonObject) {
        val fmt = doc["format"]?.jsonPrimitive?.contentOrNull
        if (fmt != EXPORT_FORMAT) throw ImportException(listOf("export: bad format marker '$fmt'"))
        wipeAll()
        fun rows(key: String) = doc[key]?.jsonArray ?: JsonArray(emptyList())
        conn.autoCommit = false
        try {
            rows("meta").forEach { el ->
                val o = el.jsonObject
                conn.prepareStatement("INSERT INTO meta(k,v) VALUES(?,?)").use { ps ->
                    ps.setString(1, o["k"]!!.jsonPrimitive.content)
                    ps.setString(2, o["v"]!!.jsonPrimitive.content)
                    ps.executeUpdate()
                }
            }
            rows("sources").forEach { el ->
                val o = el.jsonObject
                conn.prepareStatement("INSERT INTO sources(id,kind,version,parent_id,content,submitted_at,seq) VALUES(?,?,?,?,?,?,?)").use { ps ->
                    ps.setString(1, o["id"]!!.jsonPrimitive.content)
                    ps.setString(2, o["kind"]!!.jsonPrimitive.content)
                    ps.setInt(3, o["version"]!!.jsonPrimitive.int)
                    val p = o["parent_id"]
                    if (p == null || p is JsonNull) ps.setNull(4, java.sql.Types.VARCHAR) else ps.setString(4, p.jsonPrimitive.content)
                    ps.setString(5, o["content"]!!.jsonPrimitive.content)
                    ps.setString(6, o["submitted_at"]!!.jsonPrimitive.content)
                    ps.setLong(7, o["seq"]!!.jsonPrimitive.long)
                    ps.executeUpdate()
                }
            }
            rows("results").forEach { el ->
                val o = el.jsonObject
                conn.prepareStatement("INSERT INTO results(id,kind,algo_version,fingerprint,source_ids,content,stale,created_seq) VALUES(?,?,?,?,?,?,?,?)").use { ps ->
                    ps.setString(1, o["id"]!!.jsonPrimitive.content)
                    ps.setString(2, o["kind"]!!.jsonPrimitive.content)
                    ps.setString(3, o["algo_version"]!!.jsonPrimitive.content)
                    ps.setString(4, o["fingerprint"]!!.jsonPrimitive.content)
                    ps.setString(5, o["source_ids"]!!.jsonPrimitive.content)
                    ps.setString(6, o["content"]!!.jsonPrimitive.content)
                    ps.setInt(7, o["stale"]!!.jsonPrimitive.int)
                    ps.setLong(8, o["created_seq"]!!.jsonPrimitive.long)
                    ps.executeUpdate()
                }
            }
            rows("events").forEach { el ->
                val o = el.jsonObject
                conn.prepareStatement("INSERT INTO events(seq,id,type,payload,at) VALUES(?,?,?,?,?)").use { ps ->
                    ps.setLong(1, o["seq"]!!.jsonPrimitive.long)
                    ps.setString(2, o["id"]!!.jsonPrimitive.content)
                    ps.setString(3, o["type"]!!.jsonPrimitive.content)
                    ps.setString(4, o["payload"]!!.jsonPrimitive.content)
                    ps.setString(5, o["at"]!!.jsonPrimitive.content)
                    ps.executeUpdate()
                }
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
