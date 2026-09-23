package bench

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.sql.Connection
import java.sql.DriverManager

/** SQLite-backed event store. Events are the source of truth; inputs and results
 *  are materialized views rebuilt by folding events. */
class Store(path: String) : AutoCloseable {
    val conn: Connection = DriverManager.getConnection("jdbc:sqlite:$path")

    init {
        conn.createStatement().use { st ->
            st.executeUpdate("PRAGMA journal_mode=WAL")
            st.executeUpdate(
                """CREATE TABLE IF NOT EXISTS events(
                     seq INTEGER PRIMARY KEY AUTOINCREMENT,
                     id TEXT NOT NULL UNIQUE,
                     type TEXT NOT NULL,
                     at TEXT NOT NULL,
                     payload TEXT NOT NULL)"""
            )
            st.executeUpdate(
                """CREATE TABLE IF NOT EXISTS inputs(
                     key TEXT PRIMARY KEY,
                     id TEXT NOT NULL,
                     version INTEGER NOT NULL,
                     parent TEXT,
                     kind TEXT NOT NULL,
                     provenance TEXT NOT NULL,
                     raw TEXT NOT NULL,
                     content TEXT NOT NULL,
                     hash TEXT NOT NULL,
                     submitted_at TEXT NOT NULL)"""
            )
            st.executeUpdate(
                """CREATE TABLE IF NOT EXISTS results(
                     id TEXT PRIMARY KEY,
                     kind TEXT NOT NULL,
                     algorithm TEXT NOT NULL,
                     fingerprint TEXT NOT NULL,
                     sources TEXT NOT NULL,
                     content TEXT NOT NULL,
                     status TEXT NOT NULL,
                     created_at TEXT NOT NULL)"""
            )
            st.executeUpdate("CREATE TABLE IF NOT EXISTS meta(key TEXT PRIMARY KEY, value TEXT NOT NULL)")
        }
    }

    fun nextSeq(): Long = conn.createStatement().use { st ->
        st.executeQuery("SELECT COALESCE(MAX(seq),0)+1 FROM events").use { rs ->
            rs.next(); rs.getLong(1)
        }
    }

    fun appendEvent(type: String, at: String, payload: JsonObject, fixedSeq: Long? = null, fixedId: String? = null): Event {
        val seq = fixedSeq ?: nextSeq()
        val id = fixedId ?: stableId("E", seq.toString(), type, at, JsonFmt.encodeToString(JsonObject.serializer(), payload))
        conn.prepareStatement("INSERT INTO events(seq,id,type,at,payload) VALUES(?,?,?,?,?)").use { ps ->
            ps.setLong(1, seq)
            ps.setString(2, id)
            ps.setString(3, type)
            ps.setString(4, at)
            ps.setString(5, JsonFmt.encodeToString(JsonObject.serializer(), payload))
            ps.executeUpdate()
        }
        return Event(seq, id, type, at, payload)
    }

    fun events(upTo: Long = Long.MAX_VALUE): List<Event> {
        val out = ArrayList<Event>()
        conn.prepareStatement("SELECT seq,id,type,at,payload FROM events WHERE seq<=? ORDER BY seq").use { ps ->
            ps.setLong(1, upTo)
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    out.add(Event(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), parseJsonObject(rs.getString(5))))
                }
            }
        }
        return out
    }

    fun putInput(r: InputRecord) {
        conn.prepareStatement("INSERT OR REPLACE INTO inputs(key,id,version,parent,kind,provenance,raw,content,hash,submitted_at) VALUES(?,?,?,?,?,?,?,?,?,?)").use { ps ->
            ps.setString(1, r.key); ps.setString(2, r.id); ps.setInt(3, r.version)
            if (r.parent == null) ps.setNull(4, java.sql.Types.VARCHAR) else ps.setString(4, r.parent)
            ps.setString(5, r.kind.wire); ps.setString(6, r.provenance); ps.setString(7, r.raw)
            ps.setString(8, JsonFmt.encodeToString(JsonObject.serializer(), r.content))
            ps.setString(9, r.hash); ps.setString(10, r.submittedAt)
            ps.executeUpdate()
        }
    }

    fun inputs(): List<InputRecord> {
        val out = ArrayList<InputRecord>()
        conn.createStatement().use { st ->
            st.executeQuery("SELECT key,id,version,parent,kind,provenance,raw,content,hash,submitted_at FROM inputs ORDER BY id,version").use { rs ->
                while (rs.next()) {
                    out.add(
                        InputRecord(
                            id = rs.getString(2), version = rs.getInt(3), parent = rs.getString(4),
                            kind = InputKind.of(rs.getString(5)), provenance = rs.getString(6),
                            raw = rs.getString(7), content = parseJsonObject(rs.getString(8)),
                            hash = rs.getString(9), submittedAt = rs.getString(10)
                        )
                    )
                }
            }
        }
        return out
    }

    fun putResult(r: DerivedResult) {
        conn.prepareStatement("INSERT OR REPLACE INTO results(id,kind,algorithm,fingerprint,sources,content,status,created_at) VALUES(?,?,?,?,?,?,?,?)").use { ps ->
            ps.setString(1, r.id); ps.setString(2, r.kind); ps.setString(3, r.algorithm)
            ps.setString(4, r.fingerprint); ps.setString(5, r.sources.joinToString(","))
            ps.setString(6, JsonFmt.encodeToString(JsonObject.serializer(), r.content))
            ps.setString(7, r.status.name); ps.setString(8, r.createdAt)
            ps.executeUpdate()
        }
    }

    fun results(): List<DerivedResult> {
        val out = ArrayList<DerivedResult>()
        conn.createStatement().use { st ->
            st.executeQuery("SELECT id,kind,algorithm,fingerprint,sources,content,status,created_at FROM results ORDER BY created_at,id").use { rs ->
                while (rs.next()) {
                    out.add(
                        DerivedResult(
                            id = rs.getString(1), kind = rs.getString(2), algorithm = rs.getString(3),
                            fingerprint = rs.getString(4),
                            sources = rs.getString(5).split(",").filter { it.isNotBlank() },
                            content = parseJsonObject(rs.getString(6)),
                            status = ResultStatus.valueOf(rs.getString(7)),
                            createdAt = rs.getString(8)
                        )
                    )
                }
            }
        }
        return out
    }

    fun setResultStatus(id: String, status: ResultStatus) {
        conn.prepareStatement("UPDATE results SET status=? WHERE id=?").use { ps ->
            ps.setString(1, status.name); ps.setString(2, id); ps.executeUpdate()
        }
    }

    fun meta(key: String): String? =
        conn.prepareStatement("SELECT value FROM meta WHERE key=?").use { ps ->
            ps.setString(1, key)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
        }

    fun setMeta(key: String, value: String) {
        conn.prepareStatement("INSERT OR REPLACE INTO meta(key,value) VALUES(?,?)").use { ps ->
            ps.setString(1, key); ps.setString(2, value); ps.executeUpdate()
        }
    }

    fun wipeDerived() {
        conn.createStatement().use { st ->
            st.executeUpdate("DELETE FROM inputs")
            st.executeUpdate("DELETE FROM results")
        }
    }

    fun wipeAll() {
        conn.createStatement().use { st ->
            st.executeUpdate("DELETE FROM events")
            st.executeUpdate("DELETE FROM inputs")
            st.executeUpdate("DELETE FROM results")
            st.executeUpdate("DELETE FROM meta")
            st.executeUpdate("DELETE FROM sqlite_sequence WHERE name='events'")
        }
    }

    override fun close() = conn.close()
}
