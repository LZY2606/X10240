package org.chronology.workbench

import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager

class SqliteEventStore(private val databasePath: Path) {
    private var connection: Connection

    init {
        Files.createDirectories(databasePath.parent ?: Path.of("."))
        connection = DriverManager.getConnection("jdbc:sqlite:${databasePath.toAbsolutePath()}")
        connection.autoCommit = false
        connection.createStatement().use { statement ->
            statement.executeUpdate("PRAGMA foreign_keys = ON")
            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS events (
                    seq INTEGER PRIMARY KEY AUTOINCREMENT,
                    event_id TEXT NOT NULL UNIQUE,
                    event_json TEXT NOT NULL
                )
            """)
            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS kv (
                    key TEXT PRIMARY KEY,
                    value TEXT NOT NULL
                )
            """)
            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS applied_stages (
                    stage_id TEXT PRIMARY KEY,
                    applied_at TEXT NOT NULL
                )
            """)
        }
        connection.commit()
    }

    @Synchronized
    fun append(event: EventRecord, projectId: String): EventRecord {
        val existing = findEvent(event.id)
        if (existing != null) return existing
        connection.prepareStatement("INSERT INTO events(event_id, event_json) VALUES (?, ?)").use { statement ->
            statement.setString(1, event.id)
            statement.setString(2, workbenchJson.encodeToString(EventRecord.serializer(), event.copy(seq = 0)))
            statement.executeUpdate()
        }
        setKv("project_id", projectId)
        connection.commit()
        return event.copy(seq = lastSeq())
    }

    @Synchronized
    fun appendAll(events: List<EventRecord>, projectId: String, replace: Boolean = false): Long {
        if (replace) connection.createStatement().use { it.executeUpdate("DELETE FROM events; DELETE FROM sqlite_sequence WHERE name='events'; DELETE FROM applied_stages") }
        var count = 0L
        val seen = mutableSetOf<String>()
        connection.prepareStatement("INSERT INTO events(event_id, event_json) VALUES (?, ?)").use { statement ->
            events.forEach { event ->
                if (seen.add(event.id) && findEvent(event.id) == null) {
                    statement.setString(1, event.id)
                    statement.setString(2, workbenchJson.encodeToString(EventRecord.serializer(), event.copy(seq = 0)))
                    statement.addBatch()
                    count++
                }
            }
            statement.executeBatch()
        }
        setKv("project_id", projectId)
        connection.commit()
        return count
    }

    @Synchronized
    fun events(): List<EventRecord> {
        connection.createStatement().executeQuery("SELECT event_json FROM events ORDER BY seq ASC").use { rs ->
            val result = mutableListOf<EventRecord>()
            var seq = 1L
            while (rs.next()) {
                result.add(workbenchJson.decodeFromString(EventRecord.serializer(), rs.getString(1)).copy(seq = seq++))
            }
            return result
        }
    }

    @Synchronized
    fun findEvent(id: String): EventRecord? =
        connection.prepareStatement("SELECT event_json FROM events WHERE event_id = ?").use { statement ->
            statement.setString(1, id)
            statement.executeQuery().use { rs ->
                if (rs.next()) workbenchJson.decodeFromString(EventRecord.serializer(), rs.getString(1)) else null
            }
        }

    @Synchronized
    fun lastSeq(): Long = connection.createStatement().executeQuery("SELECT COALESCE(MAX(seq), 0) FROM events").use { rs ->
        rs.next()
        rs.getLong(1)
    }

    @Synchronized
    fun setKv(key: String, value: String) {
        connection.prepareStatement("INSERT INTO kv(key,value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value").use {
            it.setString(1, key)
            it.setString(2, value)
            it.executeUpdate()
        }
    }

    @Synchronized
    fun getKv(key: String): String? = connection.prepareStatement("SELECT value FROM kv WHERE key=?").use {
        it.setString(1, key)
        it.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
    }

    @Synchronized
    fun markStage(stageId: String, at: String) {
        connection.prepareStatement("INSERT OR IGNORE INTO applied_stages(stage_id, applied_at) VALUES(?,?)").use {
            it.setString(1, stageId)
            it.setString(2, at)
            it.executeUpdate()
        }
        connection.commit()
    }

    @Synchronized
    fun stages(): Set<String> = connection.createStatement().executeQuery("SELECT stage_id FROM applied_stages").use { rs ->
        val values = mutableSetOf<String>()
        while (rs.next()) values.add(rs.getString(1))
        values
    }

    fun close() = connection.close()
}
