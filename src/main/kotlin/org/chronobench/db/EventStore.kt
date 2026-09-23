package org.chronobench.db

import org.chronobench.events.Event
import org.chronobench.events.Identity
import java.sql.Connection

class DuplicateProjectException(val exportProjectId: String) :
    RuntimeException("project $exportProjectId already exists; import as a new revision or wipe first")

/**
 * Append-only event store over SQLite. Raw inputs are never updated: append() assigns the
 * next seq, virtual timestamp and content-addressed event id. Importing an export re-inserts
 * the exact same event ids/seq/timestamps, which is what preserves identity, order and
 * provenance after export -> delete local DB -> import.
 */
class EventStore(private val conn: Connection, private val clock: Clock) {
    fun projectIds(): List<String> {
        conn.createStatement().use { st ->
            st.executeQuery("SELECT DISTINCT project_id FROM event_log ORDER BY project_id").use { rs ->
                val out = mutableListOf<String>()
                while (rs.next()) out.add(rs.getString(1))
                return out
            }
        }
    }

    fun nextSeq(projectId: String): Int =
        conn.prepareStatement("SELECT COALESCE(MAX(seq), 0) + 1 FROM event_log WHERE project_id = ?").use { ps ->
            ps.setString(1, projectId)
            ps.executeQuery().use { it.takeIf { it.next() }?.getInt(1) ?: 1 }
        }

    /** Append an event produced by [factory]; identity and virtual time are assigned here. */
    fun <T : Event> append(projectId: String, causationId: String?, factory: (eventId: String, seq: Int, at: Long) -> T): T {
        val seq = nextSeq(projectId)
        val at = clock.nowEpochSecond()
        val provisionalId = "provisional"
        val probe = factory(provisionalId, seq, at)
        val payload = EventCodec.encode(probe)
        val eventId = Identity.eventId(projectId, seq, at, probe.type, payload)
        val event = factory(eventId, seq, at)
        persist(event)
        return event
    }

    fun appendExisting(event: Event) {
        require(event.seq > 0) { "imported event must carry seq" }
        persist(event)
    }

    private fun persist(event: Event) {
        val payload = EventCodec.encode(event)
        // Defensive: identity must match the canonical payload, or replay identity would drift.
        val expected = Identity.eventId(event.projectId, event.seq, event.atEpochSecond, event.type, payload)
        require(expected == event.eventId) {
            "event identity mismatch for ${event.type}: stored ${event.eventId} vs canonical $expected"
        }
        conn.prepareStatement(
            "INSERT INTO event_log(seq, project_id, event_id, event_type, at_epoch_second, causation_id, payload_json) " +
                "VALUES (?,?,?,?,?,?,?)"
        ).use { ps ->
            ps.setInt(1, event.seq)
            ps.setString(2, event.projectId)
            ps.setString(3, event.eventId)
            ps.setString(4, event.type)
            ps.setLong(5, event.atEpochSecond)
            ps.setString(6, event.causationId)
            ps.setString(7, payload)
            ps.executeUpdate()
        }
    }

    fun load(projectId: String): List<Event> =
        conn.prepareStatement(
            "SELECT payload_json FROM event_log WHERE project_id = ? ORDER BY seq ASC"
        ).use { ps ->
            ps.setString(1, projectId)
            ps.executeQuery().use { rs ->
                val out = mutableListOf<Event>()
                while (rs.next()) out.add(EventCodec.decode(rs.getString(1)))
                out
            }
        }

    fun assertProjectAbsent(projectId: String) {
        if (projectIds().contains(projectId)) throw DuplicateProjectException(projectId)
    }

    fun wipeAll() {
        conn.createStatement().use { it.executeUpdate("DELETE FROM event_log") }
    }
}
