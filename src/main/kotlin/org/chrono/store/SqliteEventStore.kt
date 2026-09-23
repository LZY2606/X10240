package org.chrono.store

import org.chrono.model.DomainEvent
import org.chrono.model.EventHashing
import org.chrono.model.StoredEvent
import org.chrono.model.canonicalJson
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException

class EventStoreException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * Append-only SQLite event log. The file lives in an explicitly chosen directory (tests use
 * temp directories, never developer-machine paths) and deleting the file is a full reset.
 */
class SqliteEventStore(private val dbPath: Path) : AutoCloseable {
    private val connection: Connection

    init {
        Files.createDirectories(dbPath.parent)
        connection = DriverManager.getConnection("jdbc:sqlite:${dbPath.toAbsolutePath()}").apply {
            createStatement().use { statement ->
                statement.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS event_log (
                        seq INTEGER PRIMARY KEY AUTOINCREMENT,
                        event_id TEXT NOT NULL UNIQUE,
                        prev_id TEXT,
                        event_type TEXT NOT NULL,
                        body_json TEXT NOT NULL,
                        sha TEXT NOT NULL UNIQUE,
                        created_at TEXT NOT NULL
                    )
                    """.trimIndent(),
                )
            }
            autoCommit = false
        }
    }

    @Synchronized
    fun append(event: DomainEvent): StoredEvent {
        val last = lastEventUnlocked()
        val seq = (last?.seq ?: 0L) + 1L
        val envelope = EventHashing.envelope(seq, last?.eventId, event)
        try {
            connection.prepareStatement(
                "INSERT INTO event_log(seq, event_id, prev_id, event_type, body_json, sha, created_at) VALUES (?,?,?,?,?,?,?)",
            ).use { statement ->
                statement.setLong(1, envelope.seq)
                statement.setString(2, envelope.eventId)
                statement.setString(3, envelope.prevId)
                statement.setString(4, envelope.eventType)
                statement.setString(5, EventHashing.body(event))
                statement.setString(6, envelope.sha)
                statement.setString(7, event.at)
                statement.executeUpdate()
            }
            connection.commit()
        } catch (ex: SQLException) {
            connection.rollback()
            throw EventStoreException("failed to append event at seq $seq: ${ex.message}", ex)
        }
        return envelope
    }

    @Synchronized
    fun appendAll(events: List<DomainEvent>): List<StoredEvent> {
        val out = mutableListOf<StoredEvent>()
        try {
            var previous = lastEventUnlocked()
            var seq = previous?.seq ?: 0L
            events.forEach { event ->
                seq += 1L
                val envelope = EventHashing.envelope(seq, previous?.eventId, event)
                connection.prepareStatement(
                    "INSERT INTO event_log(seq, event_id, prev_id, event_type, body_json, sha, created_at) VALUES (?,?,?,?,?,?,?)",
                ).use { statement ->
                    statement.setLong(1, envelope.seq)
                    statement.setString(2, envelope.eventId)
                    statement.setString(3, envelope.prevId)
                    statement.setString(4, envelope.eventType)
                    statement.setString(5, EventHashing.body(event))
                    statement.setString(6, envelope.sha)
                    statement.setString(7, event.at)
                    statement.executeUpdate()
                }
                out += envelope
                previous = envelope
            }
            connection.commit()
        } catch (ex: SQLException) {
            connection.rollback()
            throw EventStoreException("batch append failed: ${ex.message}", ex)
        }
        return out
    }

    /** The database must be empty for a package import so identities are reproduced exactly. */
    @Synchronized
    fun importEnvelopes(envelopes: List<StoredEvent>) {
        check(count() == 0) { "import requires an empty event log; current size=${count()}" }
        SqliteEventStore.verifyImport(envelopes)
        try {
            envelopes.forEach { envelope ->
                connection.prepareStatement(
                    "INSERT INTO event_log(seq, event_id, prev_id, event_type, body_json, sha, created_at) VALUES (?,?,?,?,?,?,?)",
                ).use { statement ->
                    statement.setLong(1, envelope.seq)
                    statement.setString(2, envelope.eventId)
                    statement.setString(3, envelope.prevId)
                    statement.setString(4, envelope.eventType)
                    statement.setString(5, EventHashing.body(envelope.payload))
                    statement.setString(6, envelope.sha)
                    statement.setString(7, envelope.payload.at)
                    statement.executeUpdate()
                }
            }
            connection.commit()
        } catch (ex: SQLException) {
            connection.rollback()
            throw EventStoreException("import failed: ${ex.message}", ex)
        }
    }

    @Synchronized
    fun clearAll() {
        connection.createStatement().use { it.executeUpdate("DELETE FROM event_log") }
        connection.commit()
    }

    @Synchronized
    fun loadAll(): List<StoredEvent> {
        val result = mutableListOf<StoredEvent>()
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT event_id, prev_id, event_type, body_json, sha FROM event_log ORDER BY seq ASC")
                .use { rows ->
                    while (rows.next()) {
                        val eventId = rows.getString(1)
                        val prevId = rows.getString(2)
                        val eventType = rows.getString(3)
                        val bodyJson = rows.getString(4)
                        val sha = rows.getString(5)
                        val payload = canonicalJson.decodeFromString<DomainEvent>(bodyJson)
                        val seq = result.size + 1L
                        val stored = StoredEvent(seq, eventId, prevId, eventType, payload, sha)
                        EventHashing.verify(stored)
                        result += stored
                    }
                }
        }
        verifyChain(result)
        return result
    }

    @Synchronized
    fun count(): Int {
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT COUNT(*) FROM event_log").use { rows ->
                rows.next()
                return rows.getInt(1)
            }
        }
    }

    private fun lastEventUnlocked(): StoredEvent? {
        connection.createStatement().use { statement ->
            statement.executeQuery(
                "SELECT event_id, prev_id, event_type, body_json, sha, seq FROM event_log ORDER BY seq DESC LIMIT 1",
            ).use { rows ->
                if (!rows.next()) return null
                val eventId = rows.getString(1)
                val prevId = rows.getString(2)
                val eventType = rows.getString(3)
                val payload = canonicalJson.decodeFromString<DomainEvent>(rows.getString(4))
                val sha = rows.getString(5)
                val seq = rows.getLong(6)
                return StoredEvent(seq, eventId, prevId, eventType, payload, sha)
            }
        }
    }

    override fun close() {
        connection.close()
    }

    companion object {
        fun verifyChain(events: List<StoredEvent>) {
            events.forEachIndexed { index, envelope ->
                EventHashing.verify(envelope)
                val expectedSeq = index + 1L
                check(envelope.seq == expectedSeq) { "event seq gap: ${envelope.seq} != $expectedSeq" }
                if (index > 0) {
                    check(envelope.prevId == events[index - 1].eventId) {
                        "event chain broken at seq ${envelope.seq}"
                    }
                }
            }
        }

        fun verifyImport(envelopes: List<StoredEvent>) {
            verifyChain(envelopes)
            val uniqueIds = envelopes.map { it.eventId }.toHashSet()
            check(uniqueIds.size == envelopes.size) { "imported event ids are not unique" }
        }
    }
}
