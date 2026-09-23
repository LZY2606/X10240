package gsb

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.sql.Connection
import java.sql.DriverManager

/** SQLite-backed append-only store. Identities, ordering and provenance survive
 *  export -> delete -> import round trips. */
class Store(path: String) : AutoCloseable {
    val conn: Connection = DriverManager.getConnection("jdbc:sqlite:$path")

    init {
        conn.createStatement().use { st ->
            st.executeUpdate(
                """CREATE TABLE IF NOT EXISTS source_versions(
                     id TEXT PRIMARY KEY, source_id TEXT NOT NULL, kind TEXT NOT NULL,
                     version INTEGER NOT NULL, parent_id TEXT, unit TEXT NOT NULL,
                     provenance TEXT NOT NULL, raw TEXT NOT NULL, data_json TEXT,
                     diagnostics TEXT NOT NULL, created_seq INTEGER NOT NULL)"""
            )
            st.executeUpdate(
                """CREATE TABLE IF NOT EXISTS derived(
                     id TEXT PRIMARY KEY, kind TEXT NOT NULL, algo_version TEXT NOT NULL,
                     fingerprint TEXT NOT NULL, deps TEXT NOT NULL, payload TEXT NOT NULL,
                     created_seq INTEGER NOT NULL)"""
            )
            st.executeUpdate(
                """CREATE TABLE IF NOT EXISTS events(
                     seq INTEGER PRIMARY KEY AUTOINCREMENT, id TEXT NOT NULL,
                     type TEXT NOT NULL, actor TEXT NOT NULL, at TEXT NOT NULL,
                     payload TEXT NOT NULL)"""
            )
        }
    }

    override fun close() = conn.close()

    fun appendEvent(type: String, actor: String, at: String, payload: String): EventRecord {
        conn.prepareStatement("INSERT INTO events(id,type,actor,at,payload) VALUES(?,?,?,?,?)").use { ps ->
            val tmpId = "pending"
            ps.setString(1, tmpId); ps.setString(2, type); ps.setString(3, actor)
            ps.setString(4, at); ps.setString(5, payload)
            ps.executeUpdate()
        }
        val seq = conn.createStatement().use { st ->
            st.executeQuery("SELECT last_insert_rowid()").apply { next() }.getLong(1)
        }
        val id = "ev-$seq-" + sha256("$seq|$type|$payload").take(10)
        conn.prepareStatement("UPDATE events SET id=? WHERE seq=?").use { ps ->
            ps.setString(1, id); ps.setLong(2, seq); ps.executeUpdate()
        }
        return EventRecord(seq, id, type, actor, at, payload)
    }

    fun insertSourceVersion(sv: SourceVersion) {
        conn.prepareStatement(
            "INSERT OR IGNORE INTO source_versions(id,source_id,kind,version,parent_id,unit,provenance,raw,data_json,diagnostics,created_seq) VALUES(?,?,?,?,?,?,?,?,?,?,?)"
        ).use { ps ->
            ps.setString(1, sv.id); ps.setString(2, sv.sourceId); ps.setString(3, sv.kind)
            ps.setInt(4, sv.version); ps.setString(5, sv.parentId); ps.setString(6, sv.unit)
            ps.setString(7, sv.provenance); ps.setString(8, sv.raw); ps.setString(9, sv.dataJson)
            ps.setString(10, jsonFmt.encodeToString(ListSerializer(String.serializer()), sv.diagnostics))
            ps.setLong(11, sv.createdSeq)
            ps.executeUpdate()
        }
    }

    fun sourceVersions(asOfSeq: Long = Long.MAX_VALUE): List<SourceVersion> {
        val out = mutableListOf<SourceVersion>()
        conn.prepareStatement("SELECT * FROM source_versions WHERE created_seq<=? ORDER BY created_seq, id").use { ps ->
            ps.setLong(1, asOfSeq)
            val rs = ps.executeQuery()
            while (rs.next()) {
                out.add(
                    SourceVersion(
                        id = rs.getString("id"), sourceId = rs.getString("source_id"),
                        kind = rs.getString("kind"), version = rs.getInt("version"),
                        parentId = rs.getString("parent_id"), unit = rs.getString("unit"),
                        provenance = rs.getString("provenance"), raw = rs.getString("raw"),
                        dataJson = rs.getString("data_json"),
                        diagnostics = jsonFmt.decodeFromString(ListSerializer(String.serializer()), rs.getString("diagnostics")),
                        createdSeq = rs.getLong("created_seq")
                    )
                )
            }
        }
        return out
    }

    fun sourceVersionById(id: String): SourceVersion? =
        sourceVersions().firstOrNull { it.id == id }

    /** Latest version per source id, as of [asOfSeq]. */
    fun latestSources(asOfSeq: Long = Long.MAX_VALUE): Map<String, SourceVersion> =
        sourceVersions(asOfSeq).groupBy { it.sourceId }
            .mapValues { (_, v) -> v.maxByOrNull { it.version }!! }

    fun insertDerived(d: DerivedRecord) {
        conn.prepareStatement(
            "INSERT OR IGNORE INTO derived(id,kind,algo_version,fingerprint,deps,payload,created_seq) VALUES(?,?,?,?,?,?,?)"
        ).use { ps ->
            ps.setString(1, d.id); ps.setString(2, d.kind); ps.setString(3, d.algoVersion)
            ps.setString(4, d.fingerprint); ps.setString(5, jsonFmt.encodeToString(d.deps))
            ps.setString(6, d.payload); ps.setLong(7, d.createdSeq)
            ps.executeUpdate()
        }
    }

    fun derived(asOfSeq: Long = Long.MAX_VALUE): List<DerivedRecord> {
        val out = mutableListOf<DerivedRecord>()
        conn.prepareStatement("SELECT * FROM derived WHERE created_seq<=? ORDER BY created_seq, id").use { ps ->
            ps.setLong(1, asOfSeq)
            val rs = ps.executeQuery()
            while (rs.next()) {
                out.add(
                    DerivedRecord(
                        id = rs.getString("id"), kind = rs.getString("kind"),
                        algoVersion = rs.getString("algo_version"),
                        fingerprint = rs.getString("fingerprint"),
                        deps = jsonFmt.decodeFromString(rs.getString("deps")),
                        payload = rs.getString("payload"),
                        createdSeq = rs.getLong("created_seq")
                    )
                )
            }
        }
        return out
    }

    fun derivedById(id: String): DerivedRecord? = derived().firstOrNull { it.id == id }

    fun events(asOfSeq: Long = Long.MAX_VALUE): List<EventRecord> {
        val out = mutableListOf<EventRecord>()
        conn.prepareStatement("SELECT * FROM events WHERE seq<=? ORDER BY seq").use { ps ->
            ps.setLong(1, asOfSeq)
            val rs = ps.executeQuery()
            while (rs.next()) {
                out.add(
                    EventRecord(
                        seq = rs.getLong("seq"), id = rs.getString("id"),
                        type = rs.getString("type"), actor = rs.getString("actor"),
                        at = rs.getString("at"), payload = rs.getString("payload")
                    )
                )
            }
        }
        return out
    }

    fun exportJson(): String = jsonPretty.encodeToString(
        ExportBundle(
            format = "gsb-workbench-export",
            formatVersion = 1,
            sourceVersions = sourceVersions(),
            derived = derived(),
            events = events()
        )
    )

    /** Import preserving identity, order and provenance. Idempotent per id. */
    fun importJson(text: String): ExportBundle {
        val bundle = jsonFmt.decodeFromString<ExportBundle>(text)
        require(bundle.format == "gsb-workbench-export") { "unknown export format: ${bundle.format}" }
        bundle.sourceVersions.forEach { insertSourceVersion(it) }
        bundle.derived.forEach { insertDerived(it) }
        conn.prepareStatement("INSERT OR IGNORE INTO events(seq,id,type,actor,at,payload) VALUES(?,?,?,?,?,?)").use { ps ->
            for (ev in bundle.events) {
                ps.setLong(1, ev.seq); ps.setString(2, ev.id); ps.setString(3, ev.type)
                ps.setString(4, ev.actor); ps.setString(5, ev.at); ps.setString(6, ev.payload)
                ps.executeUpdate()
            }
        }
        runCatching {
            conn.createStatement().use { st ->
                st.executeUpdate("INSERT OR REPLACE INTO sqlite_sequence(name,seq) SELECT 'events', COALESCE((SELECT MAX(seq) FROM events),0)")
            }
        }
        return bundle
    }
}
