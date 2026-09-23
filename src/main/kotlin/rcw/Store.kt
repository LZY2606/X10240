package rcw

import java.io.File
import java.sql.Connection
import java.sql.DriverManager

data class SourceRow(
    val id: String, val series: String, val kind: String, val version: Int,
    val parent: String?, val ord: Long, val content: String, val createdSeq: Long
)

data class DerivedRow(
    val id: String, val kind: String, val sample: String?, val fingerprint: String,
    val algo: String, val inputs: String, val content: String,
    val ord: Long, val createdSeq: Long, val staleSeq: Long?
)

data class CandidateRow(
    val id: String, val kind: String, val status: String, val payload: String,
    val ord: Long, val createdSeq: Long, val decidedSeq: Long?
)

data class EventRow(val seq: Long, val id: String, val type: String, val at: Long, val payload: String)

class Store(private val dbFile: File) {
    private val conn: Connection

    init {
        Class.forName("org.sqlite.JDBC")
        dbFile.absoluteFile.parentFile?.mkdirs()
        conn = DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}")
        conn.createStatement().use { st ->
            st.executeUpdate("PRAGMA journal_mode=WAL")
            st.executeUpdate("PRAGMA foreign_keys=ON")
        }
        migrate()
    }

    private fun migrate() {
        conn.createStatement().use { st ->
            st.executeUpdate(
                """CREATE TABLE IF NOT EXISTS meta(k TEXT PRIMARY KEY, v TEXT NOT NULL)"""
            )
            st.executeUpdate(
                """CREATE TABLE IF NOT EXISTS source(
                id TEXT PRIMARY KEY, series TEXT NOT NULL, kind TEXT NOT NULL, version INTEGER NOT NULL,
                parent TEXT, ord INTEGER NOT NULL, content TEXT NOT NULL, created_seq INTEGER NOT NULL)"""
            )
            st.executeUpdate(
                """CREATE TABLE IF NOT EXISTS derived(
                id TEXT PRIMARY KEY, kind TEXT NOT NULL, sample TEXT, fingerprint TEXT NOT NULL,
                algo TEXT NOT NULL, inputs TEXT NOT NULL, content TEXT NOT NULL,
                ord INTEGER NOT NULL, created_seq INTEGER NOT NULL, stale_seq INTEGER)"""
            )
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_derived_kind ON derived(kind, sample)")
            st.executeUpdate(
                """CREATE TABLE IF NOT EXISTS candidate(
                id TEXT PRIMARY KEY, kind TEXT NOT NULL, status TEXT NOT NULL, payload TEXT NOT NULL,
                ord INTEGER NOT NULL, created_seq INTEGER NOT NULL, decided_seq INTEGER)"""
            )
            st.executeUpdate(
                """CREATE TABLE IF NOT EXISTS event(
                seq INTEGER PRIMARY KEY, id TEXT NOT NULL, type TEXT NOT NULL,
                at INTEGER NOT NULL, payload TEXT NOT NULL)"""
            )
        }
    }

    fun close() = conn.close()
    fun path(): File = dbFile

    @Synchronized fun meta(k: String): String? =
        conn.prepareStatement("SELECT v FROM meta WHERE k=?").use { ps ->
            ps.setString(1, k); ps.executeQuery().use { if (it.next()) it.getString(1) else null }
        }

    @Synchronized fun putMeta(k: String, v: String) {
        conn.prepareStatement("INSERT INTO meta(k,v) VALUES(?,?) ON CONFLICT(k) DO UPDATE SET v=excluded.v").use { ps ->
            ps.setString(1, k); ps.setString(2, v); ps.executeUpdate()
        }
    }

    @Synchronized fun nextCounter(name: String): Long {
        conn.autoCommit = false
        try {
            val cur = conn.prepareStatement("SELECT v FROM meta WHERE k=?").use { ps ->
                ps.setString(1, "ctr.$name"); ps.executeQuery().use { if (it.next()) it.getString(1).toLong() else 0L }
            }
            val nxt = cur + 1
            conn.prepareStatement("INSERT INTO meta(k,v) VALUES(?,?) ON CONFLICT(k) DO UPDATE SET v=excluded.v").use { ps ->
                ps.setString(1, "ctr.$name"); ps.setString(2, nxt.toString()); ps.executeUpdate()
            }
            conn.commit()
            return nxt
        } finally { conn.autoCommit = true }
    }

    @Synchronized fun appendEvent(type: String, at: Long, payload: String): EventRow {
        conn.autoCommit = false
        try {
            val seq = conn.prepareStatement("SELECT COALESCE(MAX(seq),0)+1 FROM event").use { it.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else 1L } }
            val id = "evt-%04d".format(seq)
            conn.prepareStatement("INSERT INTO event(seq,id,type,at,payload) VALUES(?,?,?,?,?)").use { ps ->
                ps.setLong(1, seq); ps.setString(2, id); ps.setString(3, type)
                ps.setLong(4, at); ps.setString(5, payload); ps.executeUpdate()
            }
            conn.commit()
            return EventRow(seq, id, type, at, payload)
        } finally { conn.autoCommit = true }
    }

    @Synchronized fun listEvents(uptoSeq: Long? = null): List<EventRow> {
        val sql = if (uptoSeq == null) "SELECT seq,id,type,at,payload FROM event ORDER BY seq"
        else "SELECT seq,id,type,at,payload FROM event WHERE seq<=? ORDER BY seq"
        return conn.prepareStatement(sql).use { ps ->
            if (uptoSeq != null) ps.setLong(1, uptoSeq)
            ps.executeQuery().use { rs ->
                val out = mutableListOf<EventRow>()
                while (rs.next()) out += EventRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getLong(4), rs.getString(5))
                out
            }
        }
    }

    @Synchronized fun insertSource(r: SourceRow) {
        conn.prepareStatement(
            "INSERT INTO source(id,series,kind,version,parent,ord,content,created_seq) VALUES(?,?,?,?,?,?,?,?)"
        ).use { ps ->
            ps.setString(1, r.id); ps.setString(2, r.series); ps.setString(3, r.kind); ps.setInt(4, r.version)
            ps.setString(5, r.parent); ps.setLong(6, r.ord); ps.setString(7, r.content); ps.setLong(8, r.createdSeq)
            ps.executeUpdate()
        }
    }

    @Synchronized fun getSource(id: String): SourceRow? =
        conn.prepareStatement("SELECT id,series,kind,version,parent,ord,content,created_seq FROM source WHERE id=?").use { ps ->
            ps.setString(1, id); ps.executeQuery().use { rs -> if (rs.next()) sourceRow(rs) else null }
        }

    private fun sourceRow(rs: java.sql.ResultSet) = SourceRow(
        rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4),
        rs.getString(5), rs.getLong(6), rs.getString(7), rs.getLong(8)
    )

    @Synchronized fun currentSource(series: String): SourceRow? =
        conn.prepareStatement("SELECT id,series,kind,version,parent,ord,content,created_seq FROM source WHERE series=? ORDER BY version DESC LIMIT 1")
            .use { ps -> ps.setString(1, series); ps.executeQuery().use { rs -> if (rs.next()) sourceRow(rs) else null } }

    @Synchronized fun sourceAt(series: String, uptoSeq: Long): SourceRow? =
        conn.prepareStatement(
            "SELECT id,series,kind,version,parent,ord,content,created_seq FROM source WHERE series=? AND created_seq<=? ORDER BY version DESC LIMIT 1"
        ).use { ps ->
            ps.setString(1, series); ps.setLong(2, uptoSeq)
            ps.executeQuery().use { rs -> if (rs.next()) sourceRow(rs) else null }
        }

    @Synchronized fun listSources(): List<SourceRow> =
        conn.createStatement().use { st ->
            st.executeQuery("SELECT id,series,kind,version,parent,ord,content,created_seq FROM source ORDER BY ord").use { rs ->
                val out = mutableListOf<SourceRow>()
                while (rs.next()) out += sourceRow(rs); out
            }
        }

    @Synchronized fun listSourceSeries(): List<String> =
        conn.createStatement().use { st ->
            st.executeQuery("SELECT DISTINCT series FROM source ORDER BY series").use { rs ->
                val out = mutableListOf<String>()
                while (rs.next()) out += rs.getString(1); out
            }
        }

    @Synchronized fun insertDerived(r: DerivedRow) {
        conn.prepareStatement(
            "INSERT INTO derived(id,kind,sample,fingerprint,algo,inputs,content,ord,created_seq,stale_seq) VALUES(?,?,?,?,?,?,?,?,?,?)"
        ).use { ps ->
            ps.setString(1, r.id); ps.setString(2, r.kind); ps.setString(3, r.sample); ps.setString(4, r.fingerprint)
            ps.setString(5, r.algo); ps.setString(6, r.inputs); ps.setString(7, r.content); ps.setLong(8, r.ord)
            ps.setLong(9, r.createdSeq)
            if (r.staleSeq == null) ps.setNull(10, java.sql.Types.INTEGER) else ps.setLong(10, r.staleSeq)
            ps.executeUpdate()
        }
    }

    private fun derivedRow(rs: java.sql.ResultSet): DerivedRow {
        val stale = rs.getObject(10)
        return DerivedRow(
            rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
            rs.getString(6), rs.getString(7), rs.getLong(8), rs.getLong(9),
            if (stale == null) null else (stale as Number).toLong()
        )
    }

    @Synchronized fun listDerived(kind: String, sample: String? = null, includeStale: Boolean = true): List<DerivedRow> {
        var sql = "SELECT id,kind,sample,fingerprint,algo,inputs,content,ord,created_seq,stale_seq FROM derived WHERE kind=?"
        if (sample != null) sql += " AND sample=?"
        if (!includeStale) sql += " AND stale_seq IS NULL"
        sql += " ORDER BY ord"
        return conn.prepareStatement(sql).use { ps ->
            ps.setString(1, kind); if (sample != null) ps.setString(2, sample)
            ps.executeQuery().use { rs ->
                val out = mutableListOf<DerivedRow>()
                while (rs.next()) out += derivedRow(rs); out
            }
        }
    }

    @Synchronized fun getDerived(id: String): DerivedRow? =
        conn.prepareStatement("SELECT id,kind,sample,fingerprint,algo,inputs,content,ord,created_seq,stale_seq FROM derived WHERE id=?")
            .use { ps ->
                ps.setString(1, id); ps.executeQuery().use { rs -> if (rs.next()) derivedRow(rs) else null }
            }

    @Synchronized fun derivedAtOrBefore(kind: String, sample: String, uptoSeq: Long): List<DerivedRow> =
        conn.prepareStatement(
            "SELECT id,kind,sample,fingerprint,algo,inputs,content,ord,created_seq,stale_seq FROM derived WHERE kind=? AND sample=? AND created_seq<=? ORDER BY ord"
        ).use { ps ->
            ps.setString(1, kind); ps.setString(2, sample); ps.setLong(3, uptoSeq)
            ps.executeQuery().use { rs ->
                val out = mutableListOf<DerivedRow>()
                while (rs.next()) out += derivedRow(rs); out
            }
        }

    @Synchronized fun listAllDerived(): List<DerivedRow> =
        conn.createStatement().use { st ->
            st.executeQuery("SELECT id,kind,sample,fingerprint,algo,inputs,content,ord,created_seq,stale_seq FROM derived ORDER BY ord").use { rs ->
                val out = mutableListOf<DerivedRow>()
                while (rs.next()) out += derivedRow(rs); out
            }
        }

    @Synchronized fun markStale(derivedId: String, seq: Long) {
        conn.prepareStatement("UPDATE derived SET stale_seq=? WHERE id=? AND stale_seq IS NULL").use { ps ->
            ps.setLong(1, seq); ps.setString(2, derivedId); ps.executeUpdate()
        }
    }

    @Synchronized fun insertCandidate(r: CandidateRow) {
        conn.prepareStatement("INSERT INTO candidate(id,kind,status,payload,ord,created_seq,decided_seq) VALUES(?,?,?,?,?,?,?)").use { ps ->
            ps.setString(1, r.id); ps.setString(2, r.kind); ps.setString(3, r.status); ps.setString(4, r.payload)
            ps.setLong(5, r.ord); ps.setLong(6, r.createdSeq)
            if (r.decidedSeq == null) ps.setNull(7, java.sql.Types.INTEGER) else ps.setLong(7, r.decidedSeq)
            ps.executeUpdate()
        }
    }

    @Synchronized fun updateCandidate(id: String, status: String, decidedSeq: Long?) {
        conn.prepareStatement("UPDATE candidate SET status=?, decided_seq=? WHERE id=?").use { ps ->
            ps.setString(1, status)
            if (decidedSeq == null) ps.setNull(2, java.sql.Types.INTEGER) else ps.setLong(2, decidedSeq)
            ps.setString(3, id); ps.executeUpdate()
        }
    }

    private fun candidateRow(rs: java.sql.ResultSet) = CandidateRow(
        rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
        rs.getLong(5), rs.getLong(6), rs.getObject(7)?.let { (it as Number).toLong() }
    )

    @Synchronized fun listCandidates(): List<CandidateRow> =
        conn.createStatement().use { st ->
            st.executeQuery("SELECT id,kind,status,payload,ord,created_seq,decided_seq FROM candidate ORDER BY ord").use { rs ->
                val out = mutableListOf<CandidateRow>()
                while (rs.next()) out += candidateRow(rs); out
            }
        }

    @Synchronized fun getCandidate(id: String): CandidateRow? =
        conn.prepareStatement("SELECT id,kind,status,payload,ord,created_seq,decided_seq FROM candidate WHERE id=?").use { ps ->
            ps.setString(1, id); ps.executeQuery().use { rs -> if (rs.next()) candidateRow(rs) else null }
        }

    @Synchronized fun wipeAll() {
        conn.createStatement().use { st ->
            listOf("event", "candidate", "derived", "source", "meta").forEach { st.executeUpdate("DELETE FROM $it") }
        }
    }

    @Synchronized fun rawInsert(table: String, columns: List<String>, values: List<Any?>) {
        val ph = columns.joinToString(",") { "?" }
        conn.prepareStatement("INSERT INTO $table(${columns.joinToString(",")}) VALUES($ph)").use { ps ->
            values.forEachIndexed { i, v ->
                when (v) {
                    null -> ps.setNull(i + 1, java.sql.Types.VARCHAR)
                    is Long -> ps.setLong(i + 1, v)
                    is Int -> ps.setInt(i + 1, v)
                    else -> ps.setString(i + 1, v.toString())
                }
            }
            ps.executeUpdate()
        }
    }
}
