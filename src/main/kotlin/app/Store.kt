package app

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import java.sql.Connection
import java.sql.DriverManager

@Serializable
data class SourceRow(
    val id: String,
    val skey: String,
    val type: String,
    val version: Int,
    val parent: String? = null,
    val payload: String,
    val raw: String,
    val diagnostic: String? = null,
    val at: String
)

@Serializable
data class ResultRow(
    val id: String,
    val kind: String,
    val algo: String,
    val fingerprint: String,
    val inputs: List<String>,
    val payload: String,
    val at: String
)

@Serializable
data class EventRow(val seq: Long, val id: String, val type: String, val payload: String, val at: String)

@Serializable
data class Archive(val format: String = "gsb-archive/1", val events: List<EventRow>)

/**
 * SQLite 事件存储：事件为唯一事实来源；sources/results 为事件负载的物化。
 * 每行带创建它的 seq，使"回到任意旧事件"可重放当时状态。
 */
class Store(path: String) {
    val conn: Connection

    init {
        if (path != ":memory:") {
            java.io.File(path).parentFile?.mkdirs()
        }
        conn = DriverManager.getConnection("jdbc:sqlite:$path")
        conn.autoCommit = true
        conn.createStatement().use { st ->
            st.executeUpdate("PRAGMA journal_mode=WAL")
            st.executeUpdate(
                """CREATE TABLE IF NOT EXISTS events(
                     seq INTEGER PRIMARY KEY AUTOINCREMENT,
                     id TEXT UNIQUE NOT NULL,
                     type TEXT NOT NULL,
                     payload TEXT NOT NULL,
                     at TEXT NOT NULL)"""
            )
            st.executeUpdate(
                """CREATE TABLE IF NOT EXISTS sources(
                     id TEXT PRIMARY KEY,
                     skey TEXT NOT NULL,
                     type TEXT NOT NULL,
                     version INTEGER NOT NULL,
                     parent TEXT,
                     payload TEXT NOT NULL,
                     raw TEXT NOT NULL,
                     diagnostic TEXT,
                     at TEXT NOT NULL,
                     seq INTEGER NOT NULL)"""
            )
            st.executeUpdate(
                """CREATE TABLE IF NOT EXISTS results(
                     id TEXT PRIMARY KEY,
                     kind TEXT NOT NULL,
                     algo TEXT NOT NULL,
                     fingerprint TEXT NOT NULL,
                     inputs TEXT NOT NULL,
                     payload TEXT NOT NULL,
                     at TEXT NOT NULL,
                     seq INTEGER NOT NULL)"""
            )
        }
    }

    fun close() = conn.close()

    /** 追加事件；id 冲突时幂等返回已有 seq。 */
    fun appendEvent(id: String, type: String, payload: String, at: String): Long {
        conn.prepareStatement("INSERT OR IGNORE INTO events(id,type,payload,at) VALUES(?,?,?,?)").use { ps ->
            ps.setString(1, id); ps.setString(2, type); ps.setString(3, payload); ps.setString(4, at)
            ps.executeUpdate()
        }
        conn.prepareStatement("SELECT seq FROM events WHERE id=?").use { ps ->
            ps.setString(1, id)
            val rs = ps.executeQuery(); rs.next(); return rs.getLong(1)
        }
    }

    fun insertSource(row: SourceRow, seq: Long) {
        conn.prepareStatement(
            "INSERT OR IGNORE INTO sources(id,skey,type,version,parent,payload,raw,diagnostic,at,seq) VALUES(?,?,?,?,?,?,?,?,?,?)"
        ).use { ps ->
            ps.setString(1, row.id); ps.setString(2, row.skey); ps.setString(3, row.type)
            ps.setInt(4, row.version); ps.setString(5, row.parent); ps.setString(6, row.payload)
            ps.setString(7, row.raw); ps.setString(8, row.diagnostic); ps.setString(9, row.at)
            ps.setLong(10, seq)
            ps.executeUpdate()
        }
    }

    fun insertResult(row: ResultRow, seq: Long) {
        conn.prepareStatement(
            "INSERT OR IGNORE INTO results(id,kind,algo,fingerprint,inputs,payload,at,seq) VALUES(?,?,?,?,?,?,?,?)"
        ).use { ps ->
            ps.setString(1, row.id); ps.setString(2, row.kind); ps.setString(3, row.algo)
            ps.setString(4, row.fingerprint); ps.setString(5, json.encodeToString(row.inputs))
            ps.setString(6, row.payload); ps.setString(7, row.at); ps.setLong(8, seq)
            ps.executeUpdate()
        }
    }

    fun events(maxSeq: Long = Long.MAX_VALUE): List<EventRow> {
        val out = mutableListOf<EventRow>()
        conn.prepareStatement("SELECT seq,id,type,payload,at FROM events WHERE seq<=? ORDER BY seq").use { ps ->
            ps.setLong(1, maxSeq)
            val rs = ps.executeQuery()
            while (rs.next()) out.add(EventRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)))
        }
        return out
    }

    fun sources(maxSeq: Long = Long.MAX_VALUE): List<SourceRow> {
        val out = mutableListOf<SourceRow>()
        conn.prepareStatement(
            "SELECT id,skey,type,version,parent,payload,raw,diagnostic,at FROM sources WHERE seq<=? ORDER BY skey,version"
        ).use { ps ->
            ps.setLong(1, maxSeq)
            val rs = ps.executeQuery()
            while (rs.next()) out.add(
                SourceRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4),
                    rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9))
            )
        }
        return out
    }

    fun results(maxSeq: Long = Long.MAX_VALUE): List<ResultRow> {
        val out = mutableListOf<ResultRow>()
        conn.prepareStatement(
            "SELECT id,kind,algo,fingerprint,inputs,payload,at FROM results WHERE seq<=? ORDER BY seq"
        ).use { ps ->
            ps.setLong(1, maxSeq)
            val rs = ps.executeQuery()
            while (rs.next()) out.add(
                ResultRow(
                    rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                    json.decodeFromString(rs.getString(5)), rs.getString(6), rs.getString(7)
                )
            )
        }
        return out
    }

    fun maxSeq(): Long =
        conn.createStatement().use { st ->
            val rs = st.executeQuery("SELECT COALESCE(MAX(seq),0) FROM events")
            rs.next(); rs.getLong(1)
        }

    /** 每个 skey 在指定 seq 时的头部版本。 */
    fun headsAt(maxSeq: Long): Map<String, SourceRow> =
        sources(maxSeq).groupBy { it.skey }.mapValues { (_, v) -> v.maxBy { it.version } }

    fun reset() {
        conn.createStatement().use { st ->
            st.executeUpdate("DELETE FROM events")
            st.executeUpdate("DELETE FROM sources")
            st.executeUpdate("DELETE FROM results")
            st.executeUpdate("DELETE FROM sqlite_sequence WHERE name='events'")
        }
    }
}
