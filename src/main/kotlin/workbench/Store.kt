package workbench

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant

interface Clock { fun now(): String }
class SystemClock : Clock { override fun now(): String = Instant.now().toString() }
class VirtualClock(private var tick: Long = 0) : Clock {
    override fun now(): String = "2026-01-01T00:00:%02dZ".format((tick++ % 60))
}

val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

@Serializable
data class SourceRow(
    val id: String, val seq: Long, val kind: String, val stream: String, val version: Int,
    val parentId: String?, val payload: String, val provenance: String, val submittedAt: String,
)

@Serializable
data class DerivedRow(
    val id: String, val seq: Long, val kind: String, val streams: List<String>,
    val fingerprint: String, val algorithm: String, val payload: String,
    val status: String, val createdAt: String,
)

@Serializable
data class EventRow(
    val id: String, val seq: Long, val type: String, val payload: String,
    val at: String, val parentId: String?,
)

@Serializable
data class ExportBundle(
    val format: String = "radiocarbon-workbench-export/1",
    val sources: List<SourceRow>,
    val derived: List<DerivedRow>,
    val events: List<EventRow>,
)

class Store(dbPath: String, private val clock: Clock = SystemClock()) {
    val conn: Connection = DriverManager.getConnection("jdbc:sqlite:$dbPath")

    init {
        conn.createStatement().use { st ->
            st.executeUpdate("PRAGMA journal_mode=WAL")
            st.executeUpdate(
                """CREATE TABLE IF NOT EXISTS sources(
                     id TEXT PRIMARY KEY, seq INTEGER UNIQUE, kind TEXT, stream TEXT,
                     version INTEGER, parent_id TEXT, payload TEXT, provenance TEXT, submitted_at TEXT)"""
            )
            st.executeUpdate(
                """CREATE TABLE IF NOT EXISTS derived(
                     id TEXT PRIMARY KEY, seq INTEGER UNIQUE, kind TEXT, streams TEXT,
                     fingerprint TEXT, algorithm TEXT, payload TEXT, status TEXT, created_at TEXT)"""
            )
            st.executeUpdate(
                """CREATE TABLE IF NOT EXISTS events(
                     id TEXT PRIMARY KEY, seq INTEGER UNIQUE, type TEXT, payload TEXT,
                     at TEXT, parent_id TEXT)"""
            )
            st.executeUpdate("CREATE TABLE IF NOT EXISTS counters(name TEXT PRIMARY KEY, value INTEGER)")
        }
    }

    fun close() = conn.close()

    private fun nextSeq(): Long = conn.prepareStatement(
        "INSERT INTO counters(name,value) VALUES('seq',1) " +
            "ON CONFLICT(name) DO UPDATE SET value=value+1 RETURNING value"
    ).executeQuery().getLong(1)

    private fun nextId(prefix: String, seq: Long) = "%s-%05d".format(prefix, seq)

    fun isEmpty(): Boolean = conn.createStatement().executeQuery("SELECT COUNT(*) FROM sources").getLong(1) == 0L

    // ---------- sources ----------

    fun submitSource(kind: String, stream: String, payload: JsonObject, provenance: String): SourceRow {
        val version = latestVersion(stream) + 1
        val parent = latestSource(stream)?.id
        val seq = nextSeq()
        val row = SourceRow(nextId("src", seq), seq, kind, stream, version, parent,
            payload.toString(), provenance, clock.now())
        conn.prepareStatement(
            "INSERT INTO sources(id,seq,kind,stream,version,parent_id,payload,provenance,submitted_at) VALUES(?,?,?,?,?,?,?,?,?)"
        ).use { ps ->
            ps.setString(1, row.id); ps.setLong(2, row.seq); ps.setString(3, row.kind)
            ps.setString(4, row.stream); ps.setInt(5, row.version); ps.setString(6, row.parentId)
            ps.setString(7, row.payload); ps.setString(8, row.provenance); ps.setString(9, row.submittedAt)
            ps.executeUpdate()
        }
        if (version > 1) markStale(stream)
        recordEvent(if (version > 1) "SOURCE_REVISED" else "SOURCE_SUBMITTED",
            json.parseToJsonElement("""{"sourceId":"${row.id}","kind":"$kind","stream":"$stream","version":$version,"provenance":${json.parseToJsonElement(Json.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), json.parseToJsonElement(provenance)))} }""").jsonObject.let {
                JsonObject(it + ("provenance" to kotlinx.serialization.json.JsonPrimitive(provenance)))
            })
        return row
    }

    fun latestVersion(stream: String): Int = conn.prepareStatement(
        "SELECT COALESCE(MAX(version),0) FROM sources WHERE stream=?"
    ).apply { setString(1, stream) }.executeQuery().getInt(1)

    fun latestSource(stream: String): SourceRow? = conn.prepareStatement(
        "SELECT * FROM sources WHERE stream=? ORDER BY version DESC LIMIT 1"
    ).apply { setString(1, stream) }.executeQuery().let { rs -> if (rs.next()) rowToSource(rs) else null }

    fun sourceById(id: String): SourceRow? = conn.prepareStatement(
        "SELECT * FROM sources WHERE id=?"
    ).apply { setString(1, id) }.executeQuery().let { rs -> if (rs.next()) rowToSource(rs) else null }

    fun sources(kind: String? = null): List<SourceRow> {
        val sql = if (kind == null) "SELECT * FROM sources ORDER BY seq" else "SELECT * FROM sources WHERE kind=? ORDER BY seq"
        val ps = conn.prepareStatement(sql)
        if (kind != null) ps.setString(1, kind)
        val rs = ps.executeQuery()
        val out = mutableListOf<SourceRow>()
        while (rs.next()) out.add(rowToSource(rs))
        return out
    }

    fun streams(kind: String): List<String> =
        sources(kind).map { it.stream }.distinct()

    private fun rowToSource(rs: java.sql.ResultSet) = SourceRow(
        rs.getString("id"), rs.getLong("seq"), rs.getString("kind"), rs.getString("stream"),
        rs.getInt("version"), rs.getString("parent_id"), rs.getString("payload"),
        rs.getString("provenance"), rs.getString("submitted_at")
    )

    // ---------- derived ----------

    fun recordDerived(kind: String, streams: List<String>, fingerprint: String, payload: JsonObject): DerivedRow {
        val seq = nextSeq()
        val row = DerivedRow(nextId("drv", seq), seq, kind, streams, fingerprint,
            ALGORITHM_VERSION, payload.toString(), "CURRENT", clock.now())
        conn.prepareStatement(
            "INSERT INTO derived(id,seq,kind,streams,fingerprint,algorithm,payload,status,created_at) VALUES(?,?,?,?,?,?,?,?,?)"
        ).use { ps ->
            ps.setString(1, row.id); ps.setLong(2, row.seq); ps.setString(3, row.kind)
            ps.setString(4, row.streams.joinToString(",")); ps.setString(5, row.fingerprint)
            ps.setString(6, row.algorithm); ps.setString(7, row.payload)
            ps.setString(8, row.status); ps.setString(9, row.createdAt)
            ps.executeUpdate()
        }
        return row
    }

    fun markStale(stream: String) {
        conn.prepareStatement(
            "UPDATE derived SET status='STALE' WHERE status='CURRENT' AND (','||streams||',') LIKE ?"
        ).apply { setString(1, "%,$stream,%") }.executeUpdate()
    }

    fun derived(status: String? = null): List<DerivedRow> {
        val sql = if (status == null) "SELECT * FROM derived ORDER BY seq" else "SELECT * FROM derived WHERE status=? ORDER BY seq"
        val ps = conn.prepareStatement(sql)
        if (status != null) ps.setString(1, status)
        val rs = ps.executeQuery()
        val out = mutableListOf<DerivedRow>()
        while (rs.next()) out.add(rowToDerived(rs))
        return out
    }

    fun derivedById(id: String): DerivedRow? = conn.prepareStatement(
        "SELECT * FROM derived WHERE id=?"
    ).apply { setString(1, id) }.executeQuery().let { rs -> if (rs.next()) rowToDerived(rs) else null }

    fun derivedForStream(stream: String): List<DerivedRow> = conn.prepareStatement(
        "SELECT * FROM derived WHERE (','||streams||',') LIKE ? ORDER BY seq"
    ).apply { setString(1, "%,$stream,%") }.executeQuery().let { rs ->
        val out = mutableListOf<DerivedRow>()
        while (rs.next()) out.add(rowToDerived(rs))
        out
    }

    private fun rowToDerived(rs: java.sql.ResultSet) = DerivedRow(
        rs.getString("id"), rs.getLong("seq"), rs.getString("kind"),
        rs.getString("streams").split(",").filter { it.isNotBlank() },
        rs.getString("fingerprint"), rs.getString("algorithm"), rs.getString("payload"),
        rs.getString("status"), rs.getString("created_at")
    )

    // ---------- events ----------

    fun recordEvent(type: String, payload: JsonObject): EventRow {
        val seq = nextSeq()
        val parent = events().lastOrNull()?.id
        val row = EventRow(nextId("evt", seq), seq, type, payload.toString(), clock.now(), parent)
        conn.prepareStatement(
            "INSERT INTO events(id,seq,type,payload,at,parent_id) VALUES(?,?,?,?,?,?)"
        ).use { ps ->
            ps.setString(1, row.id); ps.setLong(2, row.seq); ps.setString(3, row.type)
            ps.setString(4, row.payload); ps.setString(5, row.at); ps.setString(6, row.parentId)
            ps.executeUpdate()
        }
        return row
    }

    fun events(): List<EventRow> {
        val rs = conn.createStatement().executeQuery("SELECT * FROM events ORDER BY seq")
        val out = mutableListOf<EventRow>()
        while (rs.next()) out.add(EventRow(
            rs.getString("id"), rs.getLong("seq"), rs.getString("type"),
            rs.getString("payload"), rs.getString("at"), rs.getString("parent_id")))
        return out
    }

    fun eventById(id: String): EventRow? = conn.prepareStatement(
        "SELECT * FROM events WHERE id=?"
    ).apply { setString(1, id) }.executeQuery().let { rs ->
        if (rs.next()) EventRow(rs.getString("id"), rs.getLong("seq"), rs.getString("type"),
            rs.getString("payload"), rs.getString("at"), rs.getString("parent_id")) else null
    }

    // ---------- export / import ----------

    fun export(): ExportBundle = ExportBundle(sources = sources(), derived = derived(), events = events())

    fun import(bundle: ExportBundle) {
        require(isEmpty() && events().isEmpty()) { "import requires an empty database" }
        var seq = 0L
        for (s in bundle.sources.sortedBy { it.seq }) {
            conn.prepareStatement(
                "INSERT INTO sources(id,seq,kind,stream,version,parent_id,payload,provenance,submitted_at) VALUES(?,?,?,?,?,?,?,?,?)"
            ).use { ps ->
                ps.setString(1, s.id); ps.setLong(2, s.seq); ps.setString(3, s.kind); ps.setString(4, s.stream)
                ps.setInt(5, s.version); ps.setString(6, s.parentId); ps.setString(7, s.payload)
                ps.setString(8, s.provenance); ps.setString(9, s.submittedAt); ps.executeUpdate()
            }
            seq = maxOf(seq, s.seq)
        }
        for (d in bundle.derived.sortedBy { it.seq }) {
            conn.prepareStatement(
                "INSERT INTO derived(id,seq,kind,streams,fingerprint,algorithm,payload,status,created_at) VALUES(?,?,?,?,?,?,?,?,?)"
            ).use { ps ->
                ps.setString(1, d.id); ps.setLong(2, d.seq); ps.setString(3, d.kind)
                ps.setString(4, d.streams.joinToString(",")); ps.setString(5, d.fingerprint)
                ps.setString(6, d.algorithm); ps.setString(7, d.payload); ps.setString(8, d.status)
                ps.setString(9, d.createdAt); ps.executeUpdate()
            }
            seq = maxOf(seq, d.seq)
        }
        for (e in bundle.events.sortedBy { it.seq }) {
            conn.prepareStatement(
                "INSERT INTO events(id,seq,type,payload,at,parent_id) VALUES(?,?,?,?,?,?)"
            ).use { ps ->
                ps.setString(1, e.id); ps.setLong(2, e.seq); ps.setString(3, e.type)
                ps.setString(4, e.payload); ps.setString(5, e.at); ps.setString(6, e.parentId)
                ps.executeUpdate()
            }
            seq = maxOf(seq, e.seq)
        }
        conn.prepareStatement(
            "INSERT INTO counters(name,value) VALUES('seq',?) " +
                "ON CONFLICT(name) DO UPDATE SET value=excluded.value"
        ).apply { setLong(1, seq) }.executeUpdate()
    }

    /** State as of an event: derived rows visible at that seq, with staleness evaluated at that seq. */
    fun derivedAsOf(eventSeq: Long): List<DerivedRow> {
        val visible = derived().filter { it.seq <= eventSeq }
        val revisionsByStream = sources().filter { it.seq <= eventSeq && it.version > 1 }
            .groupBy { it.stream }.mapValues { (_, v) -> v.minOf { it.seq } }
        return visible.map { d ->
            val staleAt = d.streams.any { st -> revisionsByStream[st]?.let { it > d.seq } == true }
            if (staleAt) d.copy(status = "STALE") else d
        }
    }

    fun sourcesAsOf(eventSeq: Long): List<SourceRow> = sources().filter { it.seq <= eventSeq }
}
