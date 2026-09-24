package workbench

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.sql.Connection
import java.sql.DriverManager

@Serializable
data class SourceRow(
    val id: String,
    val kind: String,
    val label: String,
    val unit: String,
    val basis: String,
    val raw: String,
    val normalized: String?,
    val sourceRef: String,
    val version: Int,
    val parentId: String?,
    val createdSeq: Int,
    val createdAt: String,
)

@Serializable
data class EventRow(
    val seq: Int,
    val id: String,
    val at: String,
    val actor: String,
    val type: String,
    val payload: String,
)

@Serializable
data class ResultRow(
    val id: String,
    val kind: String,
    val algoVersion: String,
    val fingerprint: String,
    val sourceIds: List<String>,
    val payload: String,
    val createdSeq: Int,
    val createdAt: String,
)

@Serializable
data class ExportBundle(
    val format: String,
    val sources: List<SourceRow>,
    val events: List<EventRow>,
    val results: List<ResultRow>,
)

class Store(dbPath: String, private val clock: Clock) : AutoCloseable {
    val conn: Connection = DriverManager.getConnection("jdbc:sqlite:$dbPath")
    private val json = Json { prettyPrint = false; encodeDefaults = true }

    init {
        conn.createStatement().use { st ->
            st.executeUpdate("PRAGMA journal_mode=WAL")
            st.executeUpdate(
                """CREATE TABLE IF NOT EXISTS meta(key TEXT PRIMARY KEY, value TEXT NOT NULL)"""
            )
            st.executeUpdate(
                """CREATE TABLE IF NOT EXISTS sources(
                    id TEXT PRIMARY KEY, kind TEXT NOT NULL, label TEXT NOT NULL,
                    unit TEXT NOT NULL, basis TEXT NOT NULL, raw TEXT NOT NULL,
                    normalized TEXT, source_ref TEXT NOT NULL, version INTEGER NOT NULL,
                    parent_id TEXT, created_seq INTEGER NOT NULL, created_at TEXT NOT NULL)"""
            )
            st.executeUpdate(
                """CREATE TABLE IF NOT EXISTS events(
                    seq INTEGER PRIMARY KEY AUTOINCREMENT, id TEXT UNIQUE NOT NULL,
                    at TEXT NOT NULL, actor TEXT NOT NULL, type TEXT NOT NULL, payload TEXT NOT NULL)"""
            )
            st.executeUpdate(
                """CREATE TABLE IF NOT EXISTS results(
                    id TEXT PRIMARY KEY, kind TEXT NOT NULL, algo_version TEXT NOT NULL,
                    fingerprint TEXT NOT NULL, source_ids TEXT NOT NULL,
                    payload TEXT NOT NULL, created_seq INTEGER NOT NULL, created_at TEXT NOT NULL)"""
            )
        }
        setMetaIfAbsent("schema", "1")
    }

    private fun setMetaIfAbsent(k: String, v: String) {
        conn.prepareStatement("INSERT OR IGNORE INTO meta(key,value) VALUES(?,?)").use {
            it.setString(1, k); it.setString(2, v); it.executeUpdate()
        }
    }

    fun appendEvent(type: String, actor: String, payload: String): EventRow {
        val at = clock.now().toString()
        conn.prepareStatement("INSERT INTO events(at,actor,type,payload) VALUES(?,?,?,?)").use { ps ->
            ps.setString(1, at); ps.setString(2, actor); ps.setString(3, type); ps.setString(4, payload)
            ps.executeUpdate()
        }
        val seq = conn.createStatement().use { st ->
            st.executeQuery("SELECT last_insert_rowid()").use { rs -> rs.next(); rs.getInt(1) }
        }
        val id = Ids.of("ev", seq.toString(), type, payload)
        conn.prepareStatement("UPDATE events SET id=? WHERE seq=?").use { ps ->
            ps.setString(1, id); ps.setInt(2, seq); ps.executeUpdate()
        }
        return EventRow(seq, id, at, actor, type, payload)
    }

    fun lastEventSeq(): Int = conn.createStatement().use { st ->
        st.executeQuery("SELECT COALESCE(MAX(seq),0) FROM events").use { rs -> rs.next(); rs.getInt(1) }
    }

    fun insertSource(s: SourceRow) {
        conn.prepareStatement(
            "INSERT INTO sources(id,kind,label,unit,basis,raw,normalized,source_ref,version,parent_id,created_seq,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)"
        ).use { ps ->
            ps.setString(1, s.id); ps.setString(2, s.kind); ps.setString(3, s.label)
            ps.setString(4, s.unit); ps.setString(5, s.basis); ps.setString(6, s.raw)
            if (s.normalized == null) ps.setNull(7, java.sql.Types.VARCHAR) else ps.setString(7, s.normalized)
            ps.setString(8, s.sourceRef); ps.setInt(9, s.version)
            if (s.parentId == null) ps.setNull(10, java.sql.Types.VARCHAR) else ps.setString(10, s.parentId)
            ps.setInt(11, s.createdSeq); ps.setString(12, s.createdAt)
            ps.executeUpdate()
        }
    }

    fun insertResult(r: ResultRow) {
        conn.prepareStatement(
            "INSERT OR REPLACE INTO results(id,kind,algo_version,fingerprint,source_ids,payload,created_seq,created_at) VALUES(?,?,?,?,?,?,?,?)"
        ).use { ps ->
            ps.setString(1, r.id); ps.setString(2, r.kind); ps.setString(3, r.algoVersion)
            ps.setString(4, r.fingerprint); ps.setString(5, json.encodeToString(r.sourceIds))
            ps.setString(6, r.payload); ps.setInt(7, r.createdSeq); ps.setString(8, r.createdAt)
            ps.executeUpdate()
        }
    }

    private fun sourceFrom(rs: java.sql.ResultSet) = SourceRow(
        rs.getString("id"), rs.getString("kind"), rs.getString("label"), rs.getString("unit"),
        rs.getString("basis"), rs.getString("raw"), rs.getString("normalized"),
        rs.getString("source_ref"), rs.getInt("version"), rs.getString("parent_id"),
        rs.getInt("created_seq"), rs.getString("created_at"),
    )

    private fun eventFrom(rs: java.sql.ResultSet) = EventRow(
        rs.getInt("seq"), rs.getString("id"), rs.getString("at"),
        rs.getString("actor"), rs.getString("type"), rs.getString("payload"),
    )

    private fun resultFrom(rs: java.sql.ResultSet) = ResultRow(
        rs.getString("id"), rs.getString("kind"), rs.getString("algo_version"),
        rs.getString("fingerprint"),
        json.decodeFromString(rs.getString("source_ids")),
        rs.getString("payload"), rs.getInt("created_seq"), rs.getString("created_at"),
    )

    fun allSources(): List<SourceRow> = conn.createStatement().use { st ->
        st.executeQuery("SELECT * FROM sources ORDER BY label, version").use { rs ->
            generateSequence { if (rs.next()) sourceFrom(rs) else null }.toList()
        }
    }

    fun sourceById(id: String): SourceRow? = conn.prepareStatement("SELECT * FROM sources WHERE id=?").use { ps ->
        ps.setString(1, id)
        ps.executeQuery().use { rs -> if (rs.next()) sourceFrom(rs) else null }
    }

    fun sourcesByLabel(label: String): List<SourceRow> = conn.prepareStatement(
        "SELECT * FROM sources WHERE label=? ORDER BY version"
    ).use { ps ->
        ps.setString(1, label)
        ps.executeQuery().use { rs -> generateSequence { if (rs.next()) sourceFrom(rs) else null }.toList() }
    }

    /** Latest version per label, optionally restricted to rows created at or before [asOfSeq]. */
    fun currentSources(asOfSeq: Int = Int.MAX_VALUE): List<SourceRow> =
        allSources().filter { it.createdSeq <= asOfSeq }
            .groupBy { it.label }
            .map { (_, versions) -> versions.maxBy { it.version } }
            .sortedBy { it.label }

    fun allEvents(asOfSeq: Int = Int.MAX_VALUE): List<EventRow> = conn.createStatement().use { st ->
        st.executeQuery("SELECT * FROM events WHERE seq <= $asOfSeq ORDER BY seq").use { rs ->
            generateSequence { if (rs.next()) eventFrom(rs) else null }.toList()
        }
    }

    fun allResults(asOfSeq: Int = Int.MAX_VALUE): List<ResultRow> = conn.createStatement().use { st ->
        st.executeQuery("SELECT * FROM results WHERE created_seq <= $asOfSeq ORDER BY created_seq, id").use { rs ->
            generateSequence { if (rs.next()) resultFrom(rs) else null }.toList()
        }
    }

    fun resultById(id: String): ResultRow? = conn.prepareStatement("SELECT * FROM results WHERE id=?").use { ps ->
        ps.setString(1, id)
        ps.executeQuery().use { rs -> if (rs.next()) resultFrom(rs) else null }
    }

    fun export(): String {
        val bundle = ExportBundle(
            format = "kairo-workbench-export/1",
            sources = allSources(), events = allEvents(), results = allResults(),
        )
        return Json { prettyPrint = true }.encodeToString(bundle)
    }

    /** Wipe local database and import a bundle, preserving identities, order and provenance. */
    fun importBundle(text: String): ImportSummary {
        val bundle = Json { ignoreUnknownKeys = true }.decodeFromString<ExportBundle>(text)
        require(bundle.format == "kairo-workbench-export/1") { "unsupported export format: ${bundle.format}" }
        conn.autoCommit = false
        try {
            conn.createStatement().use { st ->
                st.executeUpdate("DELETE FROM results")
                st.executeUpdate("DELETE FROM events")
                st.executeUpdate("DELETE FROM sources")
                st.executeUpdate("DELETE FROM sqlite_sequence WHERE name='events'")
            }
            for (s in bundle.sources) insertSource(s)
            conn.prepareStatement("INSERT INTO events(seq,id,at,actor,type,payload) VALUES(?,?,?,?,?,?)").use { ps ->
                for (e in bundle.events) {
                    ps.setInt(1, e.seq); ps.setString(2, e.id); ps.setString(3, e.at)
                    ps.setString(4, e.actor); ps.setString(5, e.type); ps.setString(6, e.payload)
                    ps.addBatch()
                }
                ps.executeBatch()
            }
            for (r in bundle.results) insertResult(r)
            conn.commit()
        } catch (e: Exception) {
            conn.rollback()
            throw e
        } finally {
            conn.autoCommit = true
        }
        return ImportSummary(bundle.sources.size, bundle.events.size, bundle.results.size)
    }

    data class ImportSummary(val sources: Int, val events: Int, val results: Int)

    override fun close() = conn.close()
}
