package carbonbench.db

import carbonbench.domain.Event
import carbonbench.state.Codec
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager

class SqliteEventStore(private val dbPath: Path) : EventStore {

    private val connection: Connection

    init {
        Files.createDirectories(dbPath.toAbsolutePath().parent)
        connection = DriverManager.getConnection("jdbc:sqlite:${dbPath.toAbsolutePath()}")
        connection.autoCommit = false
        connection.createStatement().use { st ->
            st.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS projects (
                    project_id TEXT PRIMARY KEY,
                    created_seq INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent()
            )
            st.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS events (
                    project_id TEXT NOT NULL,
                    seq INTEGER NOT NULL,
                    event_id TEXT NOT NULL,
                    payload TEXT NOT NULL,
                    PRIMARY KEY (project_id, seq),
                    UNIQUE (project_id, event_id)
                )
                """.trimIndent()
            )
            st.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS seq_counters (
                    project_id TEXT PRIMARY KEY,
                    next_seq INTEGER NOT NULL
                )
                """.trimIndent()
            )
        }
        connection.commit()
    }

    override fun projectExists(projectId: String): Boolean =
        connection.prepareStatement("SELECT 1 FROM seq_counters WHERE project_id = ?").use { ps ->
            ps.setString(1, projectId)
            ps.executeQuery().use { it.next() }
        }

    override fun nextSeq(projectId: String): Long {
        connection.prepareStatement(
            "INSERT INTO seq_counters(project_id, next_seq) VALUES(?, 1) ON CONFLICT(project_id) DO NOTHING"
        ).use { it.setString(1, projectId); it.executeUpdate() }
        val v = connection.prepareStatement("SELECT next_seq FROM seq_counters WHERE project_id = ?").use { ps ->
            ps.setString(1, projectId)
            ps.executeQuery().use { rs -> rs.next(); rs.getLong(1) }
        }
        return v
    }

    override fun appendEvent(event: Event) {
        val next = nextSeq(event.projectId)
        if (event.seq != next) {
            // 导入保留原身份与顺序：序号必须严格衔接
            if (event.seq > next) {
                connection.rollback()
                throw IllegalStateException("事件序号不衔接：expected=$next got=${event.seq} id=${event.id}")
            }
        }
        val expectedSeq = if (event.seq == next) next else event.seq
        val payload = Codec.compact.encodeToString(Event.serializer(), event)
        connection.prepareStatement(
            "INSERT INTO events(project_id, seq, event_id, payload) VALUES(?, ?, ?, ?)"
        ).use { ps ->
            ps.setString(1, event.projectId)
            ps.setLong(2, expectedSeq)
            ps.setString(3, event.id)
            ps.setString(4, payload)
            ps.executeUpdate()
        }
        connection.prepareStatement(
            "INSERT INTO seq_counters(project_id, next_seq) VALUES(?, ?) ON CONFLICT(project_id) DO UPDATE SET next_seq = excluded.next_seq"
        ).use { ps ->
            ps.setString(1, event.projectId)
            ps.setLong(2, expectedSeq + 1)
            ps.executeUpdate()
        }
        connection.prepareStatement(
            "INSERT INTO projects(project_id, created_seq) VALUES(?, 1) ON CONFLICT(project_id) DO NOTHING"
        ).use { ps -> ps.setString(1, event.projectId); ps.executeUpdate() }
        connection.commit()
    }

    override fun listEvents(projectId: String): List<Event> =
        connection.prepareStatement("SELECT payload FROM events WHERE project_id = ? ORDER BY seq ASC").use { ps ->
            ps.setString(1, projectId)
            ps.executeQuery().use { rs ->
                val out = ArrayList<Event>()
                while (rs.next()) {
                    out.add(Codec.compact.decodeFromString(Event.serializer(), rs.getString(1)))
                }
                out
            }
        }

    override fun listProjectIds(): List<String> =
        connection.createStatement().use { st ->
            st.executeQuery("SELECT project_id FROM projects ORDER BY project_id").use { rs ->
                val out = ArrayList<String>()
                while (rs.next()) out.add(rs.getString(1))
                out
            }
        }

    override fun deleteProject(projectId: String) {
        connection.prepareStatement("DELETE FROM events WHERE project_id = ?").use { it.setString(1, projectId); it.executeUpdate() }
        connection.prepareStatement("DELETE FROM seq_counters WHERE project_id = ?").use { it.setString(1, projectId); it.executeUpdate() }
        connection.prepareStatement("DELETE FROM projects WHERE project_id = ?").use { it.setString(1, projectId); it.executeUpdate() }
        connection.commit()
    }

    override fun close() {
        connection.close()
    }
}
