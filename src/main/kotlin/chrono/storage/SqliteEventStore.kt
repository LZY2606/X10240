package chrono.storage

import chrono.domain.canonicalJson
import chrono.domain.decodeJson
import chrono.state.Event
import java.sql.Connection
import java.sql.DriverManager
import java.util.Properties

class SqliteEventStore(private val dbPath: String) : EventStore {

    private val url: String =
        if (dbPath == ":memory:") "jdbc:sqlite::memory:"
        else "jdbc:sqlite:$dbPath"

    private val connectionProps = Properties().apply {
        setProperty("foreign_keys", "true")
    }

    init {
        Class.forName("org.sqlite.JDBC")
    }

    private val conn: Connection by lazy {
        DriverManager.getConnection(url, connectionProps).apply {
            createStatement().use { st ->
                st.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS events (
                        seq         INTEGER NOT NULL,
                        event_id    TEXT NOT NULL,
                        project_id  TEXT NOT NULL,
                        type        TEXT NOT NULL,
                        actor       TEXT NOT NULL,
                        at          TEXT NOT NULL,
                        basis       TEXT,
                        payload     TEXT NOT NULL,
                        PRIMARY KEY (project_id, seq)
                    )
                    """.trimIndent()
                )
                st.executeUpdate(
                    "CREATE UNIQUE INDEX IF NOT EXISTS idx_event_id ON events(event_id)"
                )
                st.executeUpdate(
                    "CREATE INDEX IF NOT EXISTS idx_project ON events(project_id, seq)"
                )
            }
        }
    }

    @Synchronized
    override fun append(event: Event): Event = appendAll(listOf(event)).first()

    @Synchronized
    override fun appendAll(events: List<Event>): List<Event> {
        if (events.isEmpty()) return emptyList()
        conn.autoCommit = false
        try {
            val out = mutableListOf<Event>()
            conn.prepareStatement(
                """INSERT INTO events(seq,event_id,project_id,type,actor,at,basis,payload)
                   VALUES(?,?,?,?,?,?,?,?)"""
            ).use { ps ->
                for (e in events) {
                    ps.setLong(1, e.seq)
                    ps.setString(2, e.eventId)
                    ps.setString(3, e.projectId)
                    ps.setString(4, e.type)
                    ps.setString(5, e.actor)
                    ps.setString(6, e.at.toString())
                    ps.setString(7, e.basis)
                    ps.setString(8, e.payloadJson)
                    ps.addBatch()
                    out += e
                }
                ps.executeBatch()
            }
            conn.commit()
            return out
        } catch (t: Throwable) {
            conn.rollback()
            throw t
        } finally {
            conn.autoCommit = true
        }
    }

    @Synchronized
    override fun loadProject(projectId: String): List<Event> = loadProjectAt(projectId, Long.MAX_VALUE)

    @Synchronized
    override fun loadProjectAt(projectId: String, uptoSeq: Long): List<Event> {
        val result = mutableListOf<Event>()
        conn.prepareStatement(
            "SELECT seq,event_id,project_id,type,actor,at,basis,payload FROM events " +
                "WHERE project_id=? AND seq<=? ORDER BY seq ASC"
        ).use { ps ->
            ps.setString(1, projectId)
            ps.setLong(2, uptoSeq)
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    result += Event(
                        seq = rs.getLong(1),
                        eventId = rs.getString(2),
                        projectId = rs.getString(3),
                        type = rs.getString(4),
                        actor = rs.getString(5),
                        at = java.time.Instant.parse(rs.getString(6)),
                        basis = rs.getString(7),
                        payloadJson = rs.getString(8)
                    )
                }
            }
        }
        return result
    }

    @Synchronized
    override fun listProjects(): List<String> {
        val result = mutableListOf<String>()
        conn.createStatement().use { st ->
            st.executeQuery("SELECT DISTINCT project_id FROM events ORDER BY project_id").use { rs ->
                while (rs.next()) result += rs.getString(1)
            }
        }
        return result
    }

    @Synchronized
    override fun nextSeq(projectId: String): Long = countProject(projectId) + 1L

    @Synchronized
    override fun countProject(projectId: String): Long =
        conn.prepareStatement("SELECT COALESCE(MAX(seq),0) FROM events WHERE project_id=?").use { ps ->
            ps.setString(1, projectId)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else 0L }
        }

    @Synchronized
    override fun deleteProject(projectId: String) {
        conn.prepareStatement("DELETE FROM events WHERE project_id=?").use { ps ->
            ps.setString(1, projectId)
            ps.executeUpdate()
        }
    }

    @Synchronized
    override fun allEvents(): List<Event> {
        val result = mutableListOf<Event>()
        conn.createStatement().use { st ->
            st.executeQuery(
                "SELECT seq,event_id,project_id,type,actor,at,basis,payload FROM events ORDER BY project_id,seq"
            ).use { rs ->
                while (rs.next()) {
                    result += Event(
                        rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), java.time.Instant.parse(rs.getString(6)),
                        rs.getString(7), rs.getString(8)
                    )
                }
            }
        }
        return result
    }

    fun close() = conn.close()
}

@Suppress("unused")
private val keepSerializer = canonicalJson
@Suppress("unused")
private inline fun <reified T> unused() { null as T? }
