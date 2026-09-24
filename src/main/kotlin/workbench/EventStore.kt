package workbench

import com.fasterxml.jackson.databind.JsonNode
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

interface TimeSource {
    fun now(): Instant
}

class VirtualClock(private var instant: Instant = Instant.parse("2026-01-01T00:00:00Z")) : TimeSource {
    override fun now(): Instant = instant
    fun advance(): Instant {
        instant = instant.plusSeconds(60)
        return instant
    }
}

class SystemTimeSource : TimeSource {
    override fun now(): Instant = Instant.now()
}

class EventStore(private val path: Path, private val clock: TimeSource) {
    private val formatter = DateTimeFormatter.ISO_INSTANT.withZone(ZoneOffset.UTC)

    init {
        path.parent?.let { Files.createDirectories(it) }
        DriverManager.getConnection("jdbc:sqlite:$path").use { connection ->
            connection.createStatement().execute(
                """
                create table if not exists events (
                  event_id text primary key,
                  seq integer not null unique,
                  at text not null,
                  actor text not null,
                  type text not null,
                  payload text not null,
                  parent_event_id text,
                  causal_hash text not null
                )
                """.trimIndent()
            )
        }
    }

    fun isEmpty(): Boolean = connect().use { it.createStatement().executeQuery("select count(*) from events").getLong(1) == 0L }

    fun loadEvents(): List<EventRecord> = connect().use { connection ->
        connection.createStatement().executeQuery("select event_id, seq, at, actor, type, payload, parent_event_id, causal_hash from events order by seq").use { rs ->
            val events = mutableListOf<EventRecord>()
            while (rs.next()) {
                events.add(
                    EventRecord(
                        eventId = rs.getString(1),
                        seq = rs.getLong(2),
                        at = rs.getString(3),
                        actor = rs.getString(4),
                        type = rs.getString(5),
                        payload = json.readTree(rs.getString(6)),
                        parentEventId = rs.getString(7),
                        causalHash = rs.getString(8)
                    )
                )
            }
            events
        }
    }

    fun append(type: String, payload: JsonNode, actor: String = "reviewer", parentEventId: String? = null, fixedId: String? = null): EventRecord {
        val events = loadEvents()
        val seq = events.size + 1L
        val at = when (clock) {
            is VirtualClock -> clock.advance()
            else -> clock.now()
        }
        val parent = parentEventId ?: events.lastOrNull()?.eventId
        val payloadText = json.writeValueAsString(payload)
        val causalHash = sha256(events.lastOrNull()?.causalHash ?: "GENESIS", type, seq.toString(), payloadText)
        val id = fixedId ?: causalHash.take(24)
        val record = EventRecord(id, seq, formatter.format(at), actor, type, payload, parent, causalHash)
        connect().use { connection ->
            connection.autoCommit = false
            connection.prepareStatement(
                "insert into events(event_id, seq, at, actor, type, payload, parent_event_id, causal_hash) values(?,?,?,?,?,?,?,?)"
            ).use { ps ->
                ps.setString(1, record.eventId); ps.setLong(2, record.seq); ps.setString(3, record.at)
                ps.setString(4, record.actor); ps.setString(5, record.type); ps.setString(6, payloadText)
                ps.setString(7, record.parentEventId); ps.setString(8, record.causalHash)
                ps.executeUpdate()
            }
            connection.commit()
        }
        return record
    }

    fun replaceAll(events: List<EventRecord>) {
        Files.deleteIfExists(path)
        Files.createDirectories(path.parent)
        connect().use { connection ->
            connection.createStatement().execute(
                """create table events (
                  event_id text primary key, seq integer not null unique, at text not null, actor text not null,
                  type text not null, payload text not null, parent_event_id text, causal_hash text not null)"""
            )
            connection.autoCommit = false
            connection.prepareStatement("insert into events values(?,?,?,?,?,?,?,?)").use { ps ->
                events.forEach { event ->
                    ps.setString(1, event.eventId); ps.setLong(2, event.seq); ps.setString(3, event.at)
                    ps.setString(4, event.actor); ps.setString(5, event.type); ps.setString(6, json.writeValueAsString(event.payload))
                    ps.setString(7, event.parentEventId); ps.setString(8, event.causalHash); ps.executeUpdate()
                }
            }
            connection.commit()
        }
    }

    fun deleteAll() {
        connect().use { it.createStatement().execute("delete from events") }
    }

    private fun connect(): Connection = DriverManager.getConnection("jdbc:sqlite:$path")
}
