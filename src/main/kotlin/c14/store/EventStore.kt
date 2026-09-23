package c14.store

import c14.domain.Event
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager

/**
 * SQLite-backed event log. The database is a materialized copy of the
 * append-only hash chain; deleting the file and re-importing restores
 * identities, order and source relations exactly.
 */
class EventStore(private val dbPath: Path) {
    private val url: String = "jdbc:sqlite:" + dbPath.toAbsolutePath()

    init {
        Files.createDirectories(dbPath.toAbsolutePath().parent)
        DriverManager.getConnection(url).use { connection ->
            connection.createStatement().use { st ->
                st.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS projects (
                        project_id TEXT PRIMARY KEY,
                        title TEXT NOT NULL
                    )
                    """.trimIndent()
                )
                st.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS events (
                        project_id TEXT NOT NULL,
                        seq INTEGER NOT NULL,
                        id TEXT NOT NULL,
                        type TEXT NOT NULL,
                        payload_json TEXT NOT NULL,
                        source_id TEXT,
                        actor TEXT NOT NULL,
                        at_millis INTEGER NOT NULL,
                        parent_hash TEXT NOT NULL,
                        hash TEXT NOT NULL,
                        PRIMARY KEY (project_id, seq)
                    )
                    """.trimIndent()
                )
                st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_events_project ON events(project_id, seq)")
            }
        }
    }

    fun upsertProject(projectId: String, title: String) {
        DriverManager.getConnection(url).use { c ->
            c.prepareStatement(
                "INSERT INTO projects(project_id, title) VALUES(?, ?) " +
                    "ON CONFLICT(project_id) DO UPDATE SET title = excluded.title"
            ).use { ps ->
                ps.setString(1, projectId)
                ps.setString(2, title)
                ps.executeUpdate()
            }
        }
    }

    fun insertEvent(event: Event, projectId: String) {
        DriverManager.getConnection(url).use { c ->
            c.autoCommit = false
            try {
                c.prepareStatement(
                    """
                    INSERT INTO events(project_id, seq, id, type, payload_json, source_id, actor, at_millis, parent_hash, hash)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent()
                ).use { ps ->
                    ps.setString(1, projectId)
                    ps.setInt(2, event.seq)
                    ps.setString(3, event.id)
                    ps.setString(4, event.type)
                    ps.setString(5, event.payloadJson)
                    ps.setString(6, event.sourceId)
                    ps.setString(7, event.actor)
                    ps.setLong(8, event.atMillis)
                    ps.setString(9, event.parentHash)
                    ps.setString(10, event.hash)
                    ps.executeUpdate()
                }
                c.commit()
            } catch (t: Throwable) {
                c.rollback()
                throw t
            }
        }
    }

    fun loadProjects(): Map<String, String> =
        DriverManager.getConnection(url).use { c ->
            c.createStatement().executeQuery("SELECT project_id, title FROM projects").use { rs ->
                buildMap {
                    while (rs.next()) put(rs.getString(1), rs.getString(2))
                }
            }
        }

    fun loadEvents(projectId: String): List<Event> =
        DriverManager.getConnection(url).use { c ->
            c.prepareStatement(
                "SELECT seq, id, type, payload_json, source_id, actor, at_millis, parent_hash, hash " +
                    "FROM events WHERE project_id = ? ORDER BY seq"
            ).use { ps ->
                ps.setString(1, projectId)
                ps.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(
                                Event(
                                    seq = rs.getInt(1),
                                    id = rs.getString(2),
                                    type = rs.getString(3),
                                    payloadJson = rs.getString(4),
                                    sourceId = rs.getString(5),
                                    actor = rs.getString(6),
                                    atMillis = rs.getLong(7),
                                    parentHash = rs.getString(8),
                                    hash = rs.getString(9)
                                )
                            )
                        }
                    }
                }
            }
        }

    fun deleteProject(projectId: String) {
        DriverManager.getConnection(url).use { c ->
            c.autoCommit = false
            try {
                c.prepareStatement("DELETE FROM events WHERE project_id = ?").use { it.apply { setString(1, projectId) }.executeUpdate() }
                c.prepareStatement("DELETE FROM projects WHERE project_id = ?").use { it.apply { setString(1, projectId) }.executeUpdate() }
                c.commit()
            } catch (t: Throwable) {
                c.rollback()
                throw t
            }
        }
    }

    fun countEvents(projectId: String): Int =
        DriverManager.getConnection(url).use { c ->
            c.prepareStatement("SELECT COUNT(*) FROM events WHERE project_id = ?").use { ps ->
                ps.setString(1, projectId)
                ps.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
            }
        }
}
