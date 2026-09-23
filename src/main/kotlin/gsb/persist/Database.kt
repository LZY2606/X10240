package gsb.persist

import gsb.domain.Event
import gsb.domain.EventType
import kotlinx.serialization.builtins.ListSerializer
import java.sql.Connection
import java.sql.DriverManager

/**
 * SQLite 事件库。events 为唯一事实源；projects 为快速读取的投影。
 * 删除数据库文件再导入后，身份(id)、顺序(seq)与来源关系(payload 内 parentId/source)必须保持。
 */
class EventStore(private val path: String) {
    private val conn: Connection = DriverManager.getConnection("jdbc:sqlite:$path")

    init {
        conn.createStatement().use { st ->
            st.executeUpdate(
                """CREATE TABLE IF NOT EXISTS events (
                     seq INTEGER PRIMARY KEY,
                     id TEXT NOT NULL UNIQUE,
                     type TEXT NOT NULL,
                     occurred_at TEXT NOT NULL,
                     actor TEXT NOT NULL,
                     payload TEXT NOT NULL,
                     derived_ids TEXT NOT NULL,
                     note TEXT NOT NULL,
                     project_id TEXT NOT NULL
                   )""".trimIndent()
            )
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_events_project ON events(project_id, seq)")
            st.executeUpdate(
                """CREATE TABLE IF NOT EXISTS projects_meta (
                     project_id TEXT PRIMARY KEY,
                     name TEXT NOT NULL,
                     last_seq INTEGER NOT NULL
                   )""".trimIndent()
            )
        }
    }

    fun close() = conn.close()

    @Synchronized
    fun append(event: Event, projectId: String): Event {
        val seq = nextSeq(projectId)
        val stamped = event.copy(seq = seq)
        conn.prepareStatement(
            """INSERT INTO events(seq,id,type,occurred_at,actor,payload,derived_ids,note,project_id)
               VALUES(?,?,?,?,?,?,?,?,?)""".trimIndent()
        ).use { ps ->
            ps.setLong(1, stamped.seq)
            ps.setString(2, stamped.id)
            ps.setString(3, stamped.type.name)
            ps.setString(4, stamped.occurredAt)
            ps.setString(5, stamped.actor)
            ps.setString(6, kotlinx.serialization.json.Json.encodeToString(
                kotlinx.serialization.json.JsonElement.serializer(), stamped.payload))
            ps.setString(7, stamped.derivedIds.joinToString(","))
            ps.setString(8, stamped.note)
            ps.setString(9, projectId)
            ps.executeUpdate()
        }
        upsertMeta(projectId, seq, stamped)
        return stamped
    }

    private fun nextSeq(projectId: String): Long =
        conn.prepareStatement("SELECT COALESCE(MAX(seq),0)+1 AS n FROM events WHERE project_id=?").use { ps ->
            ps.setString(1, projectId)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getLong("n") else 1L }
        }

    private fun upsertMeta(projectId: String, seq: Long, event: Event) {
        var name = projectId
        if (event.type == EventType.PROJECT_CREATED) {
            val obj = event.payload as kotlinx.serialization.json.JsonObject
            name = (obj["name"] as kotlinx.serialization.json.JsonPrimitive).content
        }
        conn.prepareStatement(
            """INSERT INTO projects_meta(project_id,name,last_seq) VALUES(?,?,?)
               ON CONFLICT(project_id) DO UPDATE SET name=excluded.name, last_seq=excluded.last_seq"""
        ).use { ps ->
            ps.setString(1, projectId); ps.setString(2, name); ps.setLong(3, seq)
            ps.executeUpdate()
        }
    }

    @Synchronized
    fun load(projectId: String): List<Event> =
        conn.prepareStatement("SELECT * FROM events WHERE project_id=? ORDER BY seq").use { ps ->
            ps.setString(1, projectId)
            ps.executeQuery().use { rs ->
                val out = mutableListOf<Event>()
                while (rs.next()) out += rowToEvent(rs)
                out
            }
        }

    fun listProjects(): List<Triple<String, String, Long>> =
        conn.createStatement().use { st ->
            st.executeQuery("SELECT project_id,name,last_seq FROM projects_meta ORDER BY project_id").use { rs ->
                val out = mutableListOf<Triple<String, String, Long>>()
                while (rs.next()) out += Triple(rs.getString(1), rs.getString(2), rs.getLong(3))
                out
            }
        }

    private fun rowToEvent(rs: java.sql.ResultSet): Event {
        val payload = kotlinx.serialization.json.Json.parseToJsonElement(rs.getString("payload"))
        return Event(
            seq = rs.getLong("seq"),
            id = rs.getString("id"),
            type = EventType.valueOf(rs.getString("type")),
            occurredAt = rs.getString("occurred_at"),
            actor = rs.getString("actor"),
            payload = payload,
            derivedIds = rs.getString("derived_ids").split(",").filter { it.isNotBlank() },
            note = rs.getString("note")
        )
    }

    @Synchronized
    fun appendRawBatch(events: List<Event>, projectId: String): List<Event> {
        var auto = conn.autoCommit
        conn.autoCommit = false
        try {
            val out = events.map { append(it, projectId) }
            conn.commit()
            return out
        } catch (t: Throwable) {
            conn.rollback(); throw t
        } finally {
            conn.autoCommit = true
        }
    }
}
