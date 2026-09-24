package workbench

import kotlinx.serialization.json.Json
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant

interface Clock {
    fun now(): String
}

class MutableClock(start: Instant = Instant.parse("2026-01-01T00:00:00Z")) : Clock {
    private var t: Instant = start
    override fun now(): String = t.toString()
    fun advance(seconds: Long) {
        t = t.plusSeconds(seconds)
    }
}

object SystemClock : Clock {
    override fun now(): String = Instant.now().toString()
}

interface IdGen {
    fun next(): String
}

class SeqIds : IdGen {
    private var n = 0
    override fun next(): String = "id-%05d".format(++n)
}

object UuidIds : IdGen {
    override fun next(): String = java.util.UUID.randomUUID().toString()
}

class Store(private val dbFile: String, val clock: Clock, val ids: IdGen) {
    val json = Json { classDiscriminator = "type"; ignoreUnknownKeys = true }
    lateinit var conn: Connection

    fun open() {
        Class.forName("org.sqlite.JDBC")
        conn = DriverManager.getConnection("jdbc:sqlite:$dbFile")
        conn.createStatement().use { st ->
            st.execute(
                "CREATE TABLE IF NOT EXISTS events(" +
                    "seq INTEGER PRIMARY KEY, id TEXT UNIQUE NOT NULL, at TEXT NOT NULL, payload TEXT NOT NULL)"
            )
        }
    }

    fun append(payload: Payload, fixedId: String? = null, fixedAt: String? = null, fixedSeq: Long? = null): Envelope {
        val id = fixedId ?: ids.next()
        val at = fixedAt ?: clock.now()
        val pjson = json.encodeToString(Payload.serializer(), payload)
        val seq: Long
        if (fixedSeq != null) {
            conn.prepareStatement("INSERT INTO events(seq,id,at,payload) VALUES(?,?,?,?)").use { ps ->
                ps.setLong(1, fixedSeq); ps.setString(2, id); ps.setString(3, at); ps.setString(4, pjson)
                ps.executeUpdate()
            }
            seq = fixedSeq
        } else {
            conn.prepareStatement("INSERT INTO events(id,at,payload) VALUES(?,?,?)").use { ps ->
                ps.setString(1, id); ps.setString(2, at); ps.setString(3, pjson)
                ps.executeUpdate()
            }
            conn.createStatement().use { st ->
                st.executeQuery("SELECT last_insert_rowid()").use { rs -> rs.next(); seq = rs.getLong(1) }
            }
        }
        return Envelope(seq, id, at, payload)
    }

    fun events(): List<Envelope> {
        val out = mutableListOf<Envelope>()
        conn.createStatement().use { st ->
            st.executeQuery("SELECT seq,id,at,payload FROM events ORDER BY seq").use { rs ->
                while (rs.next()) {
                    out.add(
                        Envelope(
                            rs.getLong(1), rs.getString(2), rs.getString(3),
                            json.decodeFromString(Payload.serializer(), rs.getString(4))
                        )
                    )
                }
            }
        }
        return out
    }

    fun exportJson(): String = Json { prettyPrint = true }.encodeToString(ExportFile(events = events()))

    fun importJson(text: String) {
        val file = Json { ignoreUnknownKeys = true }.decodeFromString(ExportFile.serializer(), text)
        require(file.format == "rcbench-export-1") { "未知导出格式：${file.format}" }
        reset()
        for (e in file.events.sortedBy { it.seq }) {
            append(e.payload, fixedId = e.id, fixedAt = e.at, fixedSeq = e.seq)
        }
    }

    fun reset() {
        conn.createStatement().use { it.execute("DELETE FROM events") }
    }

    fun close() {
        if (::conn.isInitialized) conn.close()
    }
}
