package gsb.store

import gsb.model.DomainEvent
import gsb.model.ProjectSnapshot
import kotlinx.serialization.json.Json
import java.sql.Connection
import java.sql.DriverManager
import java.io.File

/** SQLite-backed append-only event log. Identity/order columns preserve the
 *  exact event sequence across export -> delete -> import. */
class EventStore(private val dbPath: String) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        classDiscriminator = "kind"
    }

    private fun connect(): Connection =
        DriverManager.getConnection("jdbc:sqlite:$dbPath")

    init {
        File(dbPath).absoluteFile.parentFile?.mkdirs()
        connect().use { c ->
            c.createStatement().use { s ->
                s.executeUpdate(
                    """CREATE TABLE IF NOT EXISTS events (
                        seq INTEGER PRIMARY KEY,
                        event_id TEXT NOT NULL UNIQUE,
                        kind TEXT NOT NULL,
                        payload TEXT NOT NULL
                    ) STRICT"""
                )
                s.executeUpdate(
                    """CREATE TABLE IF NOT EXISTS meta (
                        k TEXT PRIMARY KEY, v TEXT NOT NULL
                    ) STRICT"""
                )
            }
        }
    }

    fun meta(k: String): String? = connect().use { c ->
        c.prepareStatement("SELECT v FROM meta WHERE k=?").use { q ->
            q.setString(1, k); q.executeQuery().use { if (it.next()) it.getString(1) else null }
        }
    }

    fun putMeta(k: String, v: String) = connect().use { c ->
        c.prepareStatement("INSERT INTO meta(k,v) VALUES(?,?) ON CONFLICT(k) DO UPDATE SET v=excluded.v")
            .use { q -> q.setString(1, k); q.setString(2, v); q.executeUpdate() }
    }

    fun nextSeq(): Long = (connect().use { c ->
        c.createStatement().executeQuery("SELECT COALESCE(MAX(seq),0) FROM events")
            .use { it.getLong(1) }
    }) + 1

    fun append(event: DomainEvent): DomainEvent {
        val payload = json.encodeToString(DomainEvent.serializer(), event)
        connect().use { c ->
            c.prepareStatement("INSERT INTO events(seq,event_id,kind,payload) VALUES(?,?,?,?)").use { q ->
                q.setLong(1, event.seq); q.setString(2, event.eventId)
                q.setString(3, event::class.simpleName!!); q.setString(4, payload)
                q.executeUpdate()
            }
        }
        return event
    }

    fun allEvents(): List<DomainEvent> = connect().use { c ->
        c.createStatement().executeQuery("SELECT payload FROM events ORDER BY seq ASC").use { rs ->
            buildList { while (rs.next()) add(json.decodeFromString(DomainEvent.serializer(), rs.getString(1))) }
        }
    }

    fun eventCount(): Int = connect().use { c ->
        c.createStatement().executeQuery("SELECT COUNT(*) FROM events").use { it.getInt(1) }
    }

    fun exportJson(projectName: String, algorithmVersion: String): String {
        val snap = ProjectSnapshot(
            projectId = meta("projectId") ?: "unknown",
            name = projectName,
            algorithmVersion = algorithmVersion,
            events = allEvents()
        )
        return json.encodeToString(ProjectSnapshot.serializer(), snap)
    }

    companion object {
        private val importJson = Json {
            ignoreUnknownKeys = true; encodeDefaults = true; classDiscriminator = "kind"
        }

        /** Replay a snapshot into a fresh/empty db, preserving seq + identities. */
        fun importInto(dbPath: String, content: String): ProjectSnapshot {
            val snap = importJson.decodeFromString(ProjectSnapshot.serializer(), content)
            val store = EventStore(dbPath)
            require(store.eventCount() == 0) { "import target database must be empty" }
            store.putMeta("projectId", snap.projectId)
            snap.events.forEach { store.append(it) }
            return snap
        }
    }
}
