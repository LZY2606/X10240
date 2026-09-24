package org.chrono.store

import kotlinx.serialization.json.Json
import org.chrono.domain.DomainEvent
import org.chrono.domain.ProjectState
import org.chrono.domain.StateProjection
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager

/**
 * SQLite 事件库：events 为唯一事实来源（append-only）；
 * 派生投影在读取时重放，保证「删除本地数据库再导入」后身份、顺序与来源关系一致。
 */
class EventStore(private val conn: Connection) {
    val json = Json {
        prettyPrint = false
        classDiscriminator = "type"
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    companion object {
        fun open(dbPath: Path): EventStore {
            Files.createDirectories(dbPath.toAbsolutePath().parent)
            val conn = DriverManager.getConnection("jdbc:sqlite:${dbPath.toAbsolutePath()}")
            return EventStore(conn).also { it.init() }
        }
    }

    fun init() {
        conn.createStatement().use {
            it.execute("PRAGMA journal_mode=WAL")
            it.execute("PRAGMA foreign_keys=ON")
            it.execute(
                """CREATE TABLE IF NOT EXISTS events (
                     seq INTEGER PRIMARY KEY,
                     event_id TEXT NOT NULL,
                     event_type TEXT NOT NULL,
                     payload TEXT NOT NULL,
                     content_hash TEXT NOT NULL,
                     created_at TEXT NOT NULL
                   )""".trimIndent()
            )
            it.execute(
                """CREATE TABLE IF NOT EXISTS replay_meta (
                     project_id TEXT PRIMARY KEY,
                     last_replayed_seq INTEGER NOT NULL
                   )""".trimIndent()
            )
        }
    }

    fun count(): Long =
        conn.createStatement().executeQuery("SELECT COUNT(*) FROM events").use { if (it.next()) it.getLong(1) else 0 }

    @Synchronized
    fun append(event: DomainEvent): Long {
        val payload = json.encodeToString(DomainEvent.serializer(), event)
        val hash = StableIds.typeCode("ev", payload)
        conn.prepareStatement(
            "INSERT INTO events(event_id,event_type,payload,content_hash,created_at) VALUES(?,?,?,?,?)"
        ).use { ps ->
            ps.setString(1, event.eventId)
            ps.setString(2, event::class.simpleName)
            ps.setString(3, payload)
            ps.setString(4, hash)
            ps.setString(5, event.occurredAt)
            ps.executeUpdate()
        }
        return conn.createStatement().executeQuery("SELECT last_insert_rowid()").use { it.getLong(1) }
    }

    data class StoredEvent(val seq: Long, val eventId: String, val hash: String, val event: DomainEvent)

    fun load(upToSeq: Long? = null): List<StoredEvent> {
        val sql = if (upToSeq == null) "SELECT seq,event_id,content_hash,payload FROM events ORDER BY seq"
        else "SELECT seq,event_id,content_hash,payload FROM events WHERE seq <= ? ORDER BY seq"
        val ps = conn.prepareStatement(sql)
        if (upToSeq != null) ps.setLong(1, upToSeq)
        ps.executeQuery().use { rs ->
            val out = ArrayList<StoredEvent>()
            while (rs.next()) {
                val ev = json.decodeFromString(DomainEvent.serializer(), rs.getString("payload"))
                out += StoredEvent(rs.getLong("seq"), rs.getString("event_id"), rs.getString("content_hash"), ev)
            }
            return out
        }
    }

    fun projectAt(upToSeq: Long? = null): ProjectState {
        var s = ProjectState()
        for (se in load(upToSeq)) s = StateProjection.apply(s, se.event)
        return s
    }

    /** 导入：在同一事务内按顺序重放追加；任何一条失败整体回滚，不留半截项目。 */
    @Synchronized
    fun importEvents(events: List<DomainEvent>): List<Long> {
        conn.autoCommit = false
        val seqs = ArrayList<Long>()
        try {
            for (e in events) {
                val before = projectAtQuiet()
                StateProjection.apply(before, e) // 校验（如版本链）
                seqs += append(e)
            }
            conn.commit()
        } catch (t: Throwable) {
            conn.rollback()
            throw t
        } finally {
            conn.autoCommit = true
        }
        return seqs
    }

    private fun projectAtQuiet(): ProjectState {
        var s = ProjectState()
        for (se in load()) s = StateProjection.apply(s, se.event)
        return s
    }

    fun exportBundles(): String {
        val list = load().map { it.event }
        return Json { prettyPrint = true; classDiscriminator = "type"; encodeDefaults = true }
            .encodeToString(kotlinx.serialization.builtins.ListSerializer(DomainEvent.serializer()), list)
    }

    fun close() = conn.close()
}
