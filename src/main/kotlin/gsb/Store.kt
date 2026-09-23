package gsb

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

const val GENESIS = "genesis"

data class Event(
    val seq: Long,
    val id: String,
    val prevId: String,
    val ts: String,
    val type: String,
    val payload: JsonObject,
)

fun eventId(prevId: String, ts: String, type: String, payload: JsonObject): String =
    "evt-" + sha256("$prevId|$ts|$type|${canonical(payload)}").take(16)

/** Append-only event log in SQLite. Event identity is a hash chain, so export/import preserves ids and order. */
class Store(dbFile: File, private val clock: Clock) : AutoCloseable {
    private val conn: Connection

    init {
        dbFile.parentFile?.mkdirs()
        conn = DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}")
        conn.createStatement().use { st ->
            st.execute(
                """CREATE TABLE IF NOT EXISTS events(
                     seq INTEGER PRIMARY KEY AUTOINCREMENT,
                     id TEXT NOT NULL UNIQUE,
                     prev_id TEXT NOT NULL,
                     ts TEXT NOT NULL,
                     type TEXT NOT NULL,
                     payload TEXT NOT NULL)"""
            )
        }
    }

    override fun close() = conn.close()

    fun headId(): String = conn.createStatement().use { st ->
        st.executeQuery("SELECT id FROM events ORDER BY seq DESC LIMIT 1").use { rs ->
            if (rs.next()) rs.getString(1) else GENESIS
        }
    }

    fun count(): Long = conn.createStatement().use { st ->
        st.executeQuery("SELECT COUNT(*) FROM events").use { rs -> rs.next(); rs.getLong(1) }
    }

    @Synchronized
    fun append(type: String, payload: JsonObject): Event {
        val prev = headId()
        val ts = clock.now()
        val id = eventId(prev, ts, type, payload)
        conn.prepareStatement("INSERT INTO events(id, prev_id, ts, type, payload) VALUES(?,?,?,?,?)").use { ps ->
            ps.setString(1, id)
            ps.setString(2, prev)
            ps.setString(3, ts)
            ps.setString(4, type)
            ps.setString(5, payload.toString())
            ps.executeUpdate()
        }
        return Event(count(), id, prev, ts, type, payload)
    }

    fun events(): List<Event> {
        val out = mutableListOf<Event>()
        conn.createStatement().use { st ->
            st.executeQuery("SELECT seq, id, prev_id, ts, type, payload FROM events ORDER BY seq").use { rs ->
                while (rs.next()) {
                    out += Event(
                        rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), Json.parseToJsonElement(rs.getString(6)).jsonObject,
                    )
                }
            }
        }
        return out
    }

    /** Replace the whole log with an imported one, verifying the hash chain first. */
    @Synchronized
    fun replaceAll(imported: List<Event>) {
        var prev = GENESIS
        imported.forEachIndexed { idx, e ->
            require(e.prevId == prev) { "broken chain at position $idx: expected prev=$prev got ${e.prevId}" }
            val recomputed = eventId(e.prevId, e.ts, e.type, e.payload)
            require(recomputed == e.id) { "event id mismatch at position $idx: ${e.id} != $recomputed" }
            prev = e.id
        }
        conn.createStatement().use { it.execute("DELETE FROM events") }
        for (e in imported) {
            conn.prepareStatement("INSERT INTO events(id, prev_id, ts, type, payload) VALUES(?,?,?,?,?)").use { ps ->
                ps.setString(1, e.id)
                ps.setString(2, e.prevId)
                ps.setString(3, e.ts)
                ps.setString(4, e.type)
                ps.setString(5, e.payload.toString())
                ps.executeUpdate()
            }
        }
    }

    fun exportJson(): JsonObject {
        val evts = events()
        return buildJsonObject {
            put("format", JsonPrimitive("gsb-export/1"))
            put("count", JsonPrimitive(evts.size))
            put("head", JsonPrimitive(headId()))
            put("events", kotlinx.serialization.json.JsonArray(evts.map { e ->
                buildJsonObject {
                    put("seq", JsonPrimitive(e.seq))
                    put("id", JsonPrimitive(e.id))
                    put("prevId", JsonPrimitive(e.prevId))
                    put("ts", JsonPrimitive(e.ts))
                    put("type", JsonPrimitive(e.type))
                    put("payload", e.payload)
                }
            }))
        }
    }

    companion object {
        fun parseExport(json: JsonObject): List<Event> {
            require(json["format"]?.jsonPrimitive?.content == "gsb-export/1") { "unsupported export format" }
            return json["events"]!!.jsonArray.map { el ->
                val o = el.jsonObject
                Event(
                    o["seq"]!!.jsonPrimitive.long,
                    o["id"]!!.jsonPrimitive.content,
                    o["prevId"]!!.jsonPrimitive.content,
                    o["ts"]!!.jsonPrimitive.content,
                    o["type"]!!.jsonPrimitive.content,
                    o["payload"]!!.jsonObject,
                )
            }
        }
    }
}
