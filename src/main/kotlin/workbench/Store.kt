package workbench

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.sql.Connection
import java.sql.DriverManager

/** SQLite-backed append-oriented store. Inputs are immutable once submitted;
 *  revisions are new versions linked by parentId. */
class Store(dbPath: String) : AutoCloseable {
    val conn: Connection = DriverManager.getConnection("jdbc:sqlite:$dbPath")

    init {
        conn.createStatement().use { st ->
            st.executeUpdate("PRAGMA journal_mode=WAL")
            st.executeUpdate(
                """CREATE TABLE IF NOT EXISTS inputs(
                     id TEXT PRIMARY KEY, stream_id TEXT, version INT, parent_id TEXT,
                     kind TEXT, payload TEXT, provenance TEXT, created_seq INT)"""
            )
            st.executeUpdate(
                """CREATE TABLE IF NOT EXISTS derived(
                     id TEXT PRIMARY KEY, kind TEXT, algo_version TEXT, param_fingerprint TEXT,
                     source_ids TEXT, payload TEXT, created_seq INT)"""
            )
            st.executeUpdate(
                """CREATE TABLE IF NOT EXISTS events(
                     seq INTEGER PRIMARY KEY AUTOINCREMENT, id TEXT, type TEXT, payload TEXT, vtime INT)"""
            )
            st.executeUpdate("CREATE TABLE IF NOT EXISTS meta(key TEXT PRIMARY KEY, value TEXT)")
        }
    }

    fun nextSeq(): Long =
        conn.createStatement().use { st ->
            st.executeQuery("SELECT COALESCE(MAX(seq),0)+1 FROM events").let { rs -> rs.next(); rs.getLong(1) }
        }

    fun insertInput(r: InputRecord) {
        conn.prepareStatement("INSERT OR IGNORE INTO inputs VALUES(?,?,?,?,?,?,?,?)").use { ps ->
            ps.setString(1, r.id); ps.setString(2, r.streamId); ps.setInt(3, r.version)
            ps.setString(4, r.parentId); ps.setString(5, r.kind.name)
            ps.setString(6, r.payload.toString()); ps.setString(7, r.provenance)
            ps.setLong(8, r.createdSeq); ps.executeUpdate()
        }
    }

    fun insertDerived(r: DerivedRecord) {
        conn.prepareStatement("INSERT OR IGNORE INTO derived VALUES(?,?,?,?,?,?,?)").use { ps ->
            ps.setString(1, r.id); ps.setString(2, r.kind.name); ps.setString(3, r.algoVersion)
            ps.setString(4, r.paramFingerprint); ps.setString(5, JsonFmt.encodeToString(kotlinx.serialization.builtins.ListSerializer(kotlinx.serialization.builtins.serializer<String>()), r.sourceIds))
            ps.setString(6, r.payload.toString()); ps.setLong(7, r.createdSeq); ps.executeUpdate()
        }
    }

    fun insertEventAt(e: EventRecord) {
        conn.prepareStatement("INSERT OR REPLACE INTO events(seq,id,type,payload,vtime) VALUES(?,?,?,?,?)").use { ps ->
            ps.setLong(1, e.seq); ps.setString(2, e.id); ps.setString(3, e.type.name)
            ps.setString(4, e.payload.toString()); ps.setLong(5, e.vtime); ps.executeUpdate()
        }
    }

    fun appendEvent(type: EventType, payload: JsonObject, vtime: Long): EventRecord {
        conn.prepareStatement("INSERT INTO events(id,type,payload,vtime) VALUES(?,?,?,?)").use { ps ->
            val seq = nextSeq()
            val id = "evt-%06d".format(seq)
            ps.setString(1, id); ps.setString(2, type.name)
            ps.setString(3, payload.toString()); ps.setLong(4, vtime); ps.executeUpdate()
            return EventRecord(seq, id, type, payload, vtime)
        }
    }

    fun inputs(atSeq: Long = Long.MAX_VALUE): List<InputRecord> {
        val out = mutableListOf<InputRecord>()
        conn.prepareStatement("SELECT * FROM inputs WHERE created_seq<=? ORDER BY created_seq, id").use { ps ->
            ps.setLong(1, atSeq)
            val rs = ps.executeQuery()
            while (rs.next()) out += InputRecord(
                rs.getString("id"), rs.getString("stream_id"), rs.getInt("version"),
                rs.getString("parent_id"), InputKind.valueOf(rs.getString("kind")),
                Json.parseToJsonElement(rs.getString("payload")) as JsonObject,
                rs.getString("provenance"), rs.getLong("created_seq")
            )
        }
        return out
    }

    fun derived(atSeq: Long = Long.MAX_VALUE): List<DerivedRecord> {
        val out = mutableListOf<DerivedRecord>()
        conn.prepareStatement("SELECT * FROM derived WHERE created_seq<=? ORDER BY created_seq, id").use { ps ->
            ps.setLong(1, atSeq)
            val rs = ps.executeQuery()
            while (rs.next()) out += DerivedRecord(
                rs.getString("id"), DerivedKind.valueOf(rs.getString("kind")),
                rs.getString("algo_version"), rs.getString("param_fingerprint"),
                JsonFmt.decodeFromString(kotlinx.serialization.builtins.ListSerializer(kotlinx.serialization.builtins.serializer<String>()), rs.getString("source_ids")),
                Json.parseToJsonElement(rs.getString("payload")) as JsonObject,
                rs.getLong("created_seq")
            )
        }
        return out
    }

    fun events(atSeq: Long = Long.MAX_VALUE): List<EventRecord> {
        val out = mutableListOf<EventRecord>()
        conn.prepareStatement("SELECT * FROM events WHERE seq<=? ORDER BY seq").use { ps ->
            ps.setLong(1, atSeq)
            val rs = ps.executeQuery()
            while (rs.next()) out += EventRecord(
                rs.getLong("seq"), rs.getString("id"), EventType.valueOf(rs.getString("type")),
                Json.parseToJsonElement(rs.getString("payload")) as JsonObject, rs.getLong("vtime")
            )
        }
        return out
    }

    fun clearAll() {
        conn.createStatement().use { st ->
            st.executeUpdate("DELETE FROM inputs"); st.executeUpdate("DELETE FROM derived")
            st.executeUpdate("DELETE FROM events"); st.executeUpdate("DELETE FROM meta")
        }
    }

    override fun close() = conn.close()
}
