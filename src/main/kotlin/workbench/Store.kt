package workbench

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import java.sql.Connection
import java.sql.DriverManager

@Serializable
data class StoredEvent(
    val seq: Long,
    val id: String,
    val type: String,
    val vtime: String,
    val payload: JsonObject
)

@Serializable
data class ExportEnvelope(
    val format: String = "rcwb-export-v1",
    val project: String = "radiocarbon-workbench",
    val events: List<StoredEvent>
)

class Store(private val dbPath: String, private val clock: Clock) : AutoCloseable {
    private var conn: Connection = open(dbPath)

    private fun open(path: String): Connection {
        val c = DriverManager.getConnection("jdbc:sqlite:$path")
        c.createStatement().use { st ->
            st.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS events(
                  seq INTEGER PRIMARY KEY AUTOINCREMENT,
                  id TEXT NOT NULL UNIQUE,
                  type TEXT NOT NULL,
                  vtime TEXT NOT NULL,
                  payload TEXT NOT NULL
                )
                """.trimIndent()
            )
            st.executeUpdate("CREATE TABLE IF NOT EXISTS meta(key TEXT PRIMARY KEY, value TEXT NOT NULL)")
        }
        return c
    }

    @Synchronized
    fun append(type: String, payload: JsonObject): StoredEvent {
        val vtime = clock.now()
        conn.prepareStatement("INSERT INTO events(type, vtime, payload) VALUES(?,?,?)").use { ps ->
            ps.setString(1, type)
            ps.setString(2, vtime)
            ps.setString(3, payload.toString())
            ps.executeUpdate()
        }
        val seq = conn.createStatement().use { st ->
            st.executeQuery("SELECT last_insert_rowid()").use { rs -> rs.next(); rs.getLong(1) }
        }
        val id = "evt-" + seq.toString().padStart(5, '0')
        conn.prepareStatement("UPDATE events SET id=? WHERE seq=?").use { ps ->
            ps.setString(1, id)
            ps.setLong(2, seq)
            ps.executeUpdate()
        }
        return StoredEvent(seq, id, type, vtime, payload)
    }

    @Synchronized
    fun events(upTo: Long = Long.MAX_VALUE): List<StoredEvent> {
        val out = mutableListOf<StoredEvent>()
        conn.prepareStatement("SELECT seq,id,type,vtime,payload FROM events WHERE seq<=? ORDER BY seq").use { ps ->
            ps.setLong(1, upTo)
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    out.add(
                        StoredEvent(
                            rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                            json.parseToJsonElement(rs.getString(5)) as JsonObject
                        )
                    )
                }
            }
        }
        return out
    }

    @Synchronized
    fun exportJson(): String = json.encodeToString(ExportEnvelope.serializer(), ExportEnvelope(events = events()))

    @Synchronized
    fun replaceWith(exportJsonText: String): Int {
        val envelope = json.decodeFromString(ExportEnvelope.serializer(), exportJsonText)
        require(envelope.format == "rcwb-export-v1") { "unsupported export format: ${envelope.format}" }
        conn.close()
        java.io.File(dbPath).delete()
        conn = open(dbPath)
        conn.autoCommit = false
        try {
            for (e in envelope.events.sortedBy { it.seq }) {
                conn.prepareStatement("INSERT INTO events(seq,id,type,vtime,payload) VALUES(?,?,?,?,?)").use { ps ->
                    ps.setLong(1, e.seq)
                    ps.setString(2, e.id)
                    ps.setString(3, e.type)
                    ps.setString(4, e.vtime)
                    ps.setString(5, e.payload.toString())
                    ps.executeUpdate()
                }
            }
            conn.commit()
        } catch (ex: Exception) {
            conn.rollback()
            throw ex
        } finally {
            conn.autoCommit = true
        }
        return envelope.events.size
    }

    override fun close() {
        conn.close()
    }
}
