package workbench.store

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import workbench.domain.*
import java.sql.Connection
import java.sql.DriverManager

/** SQLite-backed store. All state lives here; export/import round-trips preserve identity. */
class EventStore(dbPath: String) {
    val conn: Connection = DriverManager.getConnection("jdbc:sqlite:$dbPath")

    init {
        conn.createStatement().use { st ->
            st.executeUpdate("PRAGMA journal_mode=WAL")
            st.executeUpdate(
                """CREATE TABLE IF NOT EXISTS events(
                     seq INTEGER PRIMARY KEY, id TEXT UNIQUE, type TEXT, at TEXT, actor TEXT, payload TEXT)"""
            )
            st.executeUpdate(
                """CREATE TABLE IF NOT EXISTS sources(
                     id TEXT PRIMARY KEY, kind TEXT, lineage_id TEXT, version INTEGER,
                     parent_id TEXT, submitted_by TEXT, submitted_at TEXT, raw TEXT)"""
            )
            st.executeUpdate(
                """CREATE TABLE IF NOT EXISTS derived(
                     id TEXT PRIMARY KEY, kind TEXT, algorithm_version TEXT, fingerprint TEXT,
                     source_ids TEXT, payload TEXT, created_at TEXT, superseded INTEGER)"""
            )
            st.executeUpdate("CREATE TABLE IF NOT EXISTS meta(key TEXT PRIMARY KEY, value TEXT)")
        }
    }

    fun nextSeq(): Long =
        conn.createStatement().use { st ->
            st.executeQuery("SELECT COALESCE(MAX(seq),0)+1 FROM events").let { rs -> rs.next(); rs.getLong(1) }
        }

    fun appendEvent(e: Event) {
        conn.prepareStatement("INSERT INTO events(seq,id,type,at,actor,payload) VALUES(?,?,?,?,?,?)").use { ps ->
            ps.setLong(1, e.seq); ps.setString(2, e.id); ps.setString(3, e.type)
            ps.setString(4, e.at); ps.setString(5, e.actor)
            ps.setString(6, CanonicalJson.encodeToString(JsonObject.serializer(), e.payload))
            ps.executeUpdate()
        }
    }

    fun events(): List<Event> =
        conn.createStatement().use { st ->
            st.executeQuery("SELECT seq,id,type,at,actor,payload FROM events ORDER BY seq").let { rs ->
                val out = ArrayList<Event>()
                while (rs.next()) out.add(
                    Event(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                        CanonicalJson.decodeFromString(JsonObject.serializer(), rs.getString(6)))
                )
                out
            }
        }

    fun putSource(s: SourceRecord) {
        conn.prepareStatement("INSERT OR REPLACE INTO sources VALUES(?,?,?,?,?,?,?,?)").use { ps ->
            ps.setString(1, s.id); ps.setString(2, s.kind.name); ps.setString(3, s.lineageId)
            ps.setInt(4, s.version); ps.setString(5, s.parentId); ps.setString(6, s.submittedBy)
            ps.setString(7, s.submittedAt)
            ps.setString(8, CanonicalJson.encodeToString(JsonObject.serializer(), s.raw))
            ps.executeUpdate()
        }
    }

    fun sources(): List<SourceRecord> =
        conn.createStatement().use { st ->
            st.executeQuery("SELECT * FROM sources ORDER BY rowid").let { rs ->
                val out = ArrayList<SourceRecord>()
                while (rs.next()) out.add(
                    SourceRecord(rs.getString("id"), SourceKind.valueOf(rs.getString("kind")),
                        rs.getString("lineage_id"), rs.getInt("version"), rs.getString("parent_id"),
                        rs.getString("submitted_by"), rs.getString("submitted_at"),
                        CanonicalJson.decodeFromString(JsonObject.serializer(), rs.getString("raw")))
                )
                out
            }
        }

    fun putDerived(d: DerivedResult) {
        conn.prepareStatement("INSERT OR REPLACE INTO derived VALUES(?,?,?,?,?,?,?,?)").use { ps ->
            ps.setString(1, d.id); ps.setString(2, d.kind); ps.setString(3, d.algorithmVersion)
            ps.setString(4, d.fingerprint); ps.setString(5, d.sourceIds.joinToString(","))
            ps.setString(6, CanonicalJson.encodeToString(JsonObject.serializer(), d.payload))
            ps.setString(7, d.createdAt); ps.setInt(8, if (d.superseded) 1 else 0)
            ps.executeUpdate()
        }
    }

    fun derived(): List<DerivedResult> =
        conn.createStatement().use { st ->
            st.executeQuery("SELECT * FROM derived ORDER BY rowid").let { rs ->
                val out = ArrayList<DerivedResult>()
                while (rs.next()) out.add(
                    DerivedResult(rs.getString("id"), rs.getString("kind"), rs.getString("algorithm_version"),
                        rs.getString("fingerprint"), rs.getString("source_ids").split(",").filter { it.isNotBlank() },
                        CanonicalJson.decodeFromString(JsonObject.serializer(), rs.getString("payload")),
                        rs.getString("created_at"), rs.getInt("superseded") == 1)
                )
                out
            }
        }

    fun markSuperseded(id: String) {
        conn.prepareStatement("UPDATE derived SET superseded=1 WHERE id=?").use { ps ->
            ps.setString(1, id); ps.executeUpdate()
        }
    }

    fun exportJson(): JsonObject = kotlinx.serialization.json.buildJsonObject {
        put("format", "radiocarbon-workbench-export-v1")
        putJsonArrayLike("events", events().map { CanonicalJson.encodeToJsonElement(Event.serializer(), it) })
        putJsonArrayLike("sources", sources().map { CanonicalJson.encodeToJsonElement(SourceRecord.serializer(), it) })
        putJsonArrayLike("derived", derived().map { CanonicalJson.encodeToJsonElement(DerivedResult.serializer(), it) })
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putJsonArrayLike(key: String, items: List<kotlinx.serialization.json.JsonElement>) {
        put(key, kotlinx.serialization.json.JsonArray(items))
    }

    fun importJson(doc: JsonObject) {
        conn.autoCommit = false
        try {
            doc["events"]!!.let { arr ->
                for (el in arr.jsonArray) {
                    val e = CanonicalJson.decodeFromJsonElement(Event.serializer(), el)
                    appendEvent(e)
                }
            }
            doc["sources"]!!.let { arr ->
                for (el in arr.jsonArray) {
                    putSource(CanonicalJson.decodeFromJsonElement(SourceRecord.serializer(), el))
                }
            }
            doc["derived"]!!.let { arr ->
                for (el in arr.jsonArray) {
                    putDerived(CanonicalJson.decodeFromJsonElement(DerivedResult.serializer(), el))
                }
            }
            conn.commit()
        } catch (ex: Exception) {
            conn.rollback(); throw ex
        } finally { conn.autoCommit = true }
    }

    fun close() = conn.close()
}
