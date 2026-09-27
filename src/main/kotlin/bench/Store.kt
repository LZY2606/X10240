package bench

import kotlinx.serialization.json.Json
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/** SQLite 事件日志：唯一持久化事实来源，状态由事件折叠重建。 */
class Store(dbPath: String) : AutoCloseable {
    private val conn: Connection

    init {
        if (dbPath != ":memory:") File(dbPath).absoluteFile.parentFile?.mkdirs()
        conn = DriverManager.getConnection("jdbc:sqlite:$dbPath")
        conn.createStatement().use { st ->
            st.executeUpdate(
                "CREATE TABLE IF NOT EXISTS events(" +
                    "seq INTEGER PRIMARY KEY, id TEXT NOT NULL UNIQUE, ts TEXT NOT NULL," +
                    "type TEXT NOT NULL, payload TEXT NOT NULL)"
            )
        }
    }

    @Synchronized
    fun loadEvents(): List<EventEnvelope> {
        val out = mutableListOf<EventEnvelope>()
        conn.createStatement().use { st ->
            val rs = st.executeQuery("SELECT seq, id, ts, type, payload FROM events ORDER BY seq")
            while (rs.next()) {
                out += EventEnvelope(
                    id = rs.getString("id"), seq = rs.getInt("seq"), ts = rs.getString("ts"),
                    type = rs.getString("type"), payload = Json.parseToJsonElement(rs.getString("payload")) as kotlinx.serialization.json.JsonObject
                )
            }
        }
        return out
    }

    @Synchronized
    fun append(e: EventEnvelope) {
        val ps = conn.prepareStatement("INSERT INTO events(seq, id, ts, type, payload) VALUES(?,?,?,?,?)")
        ps.use {
            it.setInt(1, e.seq)
            it.setString(2, e.id)
            it.setString(3, e.ts)
            it.setString(4, e.type)
            it.setString(5, e.payload.toString())
            it.executeUpdate()
        }
    }

    @Synchronized
    fun clear() {
        conn.createStatement().use { it.executeUpdate("DELETE FROM events") }
    }

    @Synchronized
    fun deleteFrom(afterSeq: Int) {
        val ps = conn.prepareStatement("DELETE FROM events WHERE seq > ?")
        ps.use { it.setInt(1, afterSeq); it.executeUpdate() }
    }

    @Synchronized
    fun count(): Int = conn.createStatement().use { st ->
        val rs = st.executeQuery("SELECT COUNT(*) FROM events")
        rs.next(); rs.getInt(1)
    }

    override fun close() = conn.close()
}
