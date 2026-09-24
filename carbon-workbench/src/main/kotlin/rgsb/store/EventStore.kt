package rgsb.store

import rgsb.domain.Hashing
import rgsb.model.BranchRow
import rgsb.model.EventEnvelope
import rgsb.model.EventPayload
import rgsb.model.ProjectBundle
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Statement

/**
 * Append-only SQLite event store.
 *
 * Identity guarantees:
 *  - eventId = sha256(project|seq|branch|parents|virtualTime|actor|payloadHash), so the
 *    same replayed event has the same stable identity after export/import;
 *  - derived ids produced by the engine are content-based and therefore stable too;
 *  - events are only ever inserted; original inputs can never be overwritten.
 */
class EventStore(
    private val dbPath: Path,
    private val projectId: String,
    private val clock: VClock = SystemClock,
    private val actor: String = "reviewer",
) {
    private val conn: Connection

    init {
        Files.createDirectories(dbPath.toAbsolutePath().parent)
        conn = DriverManager.getConnection("jdbc:sqlite:$dbPath")
        conn.autoCommit = false
        conn.createStatement().use { st ->
            st.executeUpdate("PRAGMA journal_mode=WAL")
            st.executeUpdate("PRAGMA foreign_keys=ON")
            schema(st)
        }
        conn.commit()
    }

    private fun schema(st: Statement) {
        st.executeUpdate(
            """
            CREATE TABLE IF NOT EXISTS events (
              seq INTEGER PRIMARY KEY,
              event_id TEXT NOT NULL UNIQUE,
              branch TEXT NOT NULL,
              parent_event_ids TEXT NOT NULL,
              virtual_time TEXT NOT NULL,
              actor TEXT NOT NULL,
              payload_json TEXT NOT NULL,
              payload_hash TEXT NOT NULL
            )
            """.trimIndent()
        )
        st.executeUpdate(
            """
            CREATE TABLE IF NOT EXISTS branches (
              branch TEXT PRIMARY KEY,
              tip_event_id TEXT NOT NULL,
              fork_event_id TEXT
            )
            """.trimIndent()
        )
        st.executeUpdate(
            """
            CREATE TABLE IF NOT EXISTS meta (
              key TEXT PRIMARY KEY,
              value TEXT NOT NULL
            )
            """.trimIndent()
        )
    }

    fun close() = conn.close()

    @Synchronized
    fun nextSeq(): Long {
        val rs = conn.createStatement().executeQuery("SELECT COALESCE(MAX(seq), 0) FROM events")
        rs.next(); val v = rs.getLong(1); rs.close(); return v + 1
    }

    @Synchronized
    fun append(
        payload: EventPayload,
        branch: String = MAIN,
        parents: List<String>? = null,
        atVirtualTime: String? = null,
        actorOverride: String? = null,
    ): EventEnvelope {
        val payloadJson = Hashing.canonicalJson.encodeToString(EventPayload.serializer(), payload)
        val payloadHash = Hashing.sha256Hex(payloadJson)
        val seq = nextSeq()
        val virtualTime = atVirtualTime ?: clock.now().toString()
        val useActor = actorOverride ?: actor
        val parentIds = parents ?: listOf(tip(branch) ?: forkPoint(branch)).filterNotNull()
        val eventId = Hashing.sha256Hex(
            listOf(projectId, seq.toString(), branch, parentIds.joinToString(","), virtualTime, useActor, payloadHash).joinToString("|")
        )
        val env = EventEnvelope(
            eventId = eventId, seq = seq, branch = branch,
            parentEventIds = parentIds, virtualTime = virtualTime, actor = useActor,
            payload = payload, payloadHash = payloadHash,
        )
        conn.prepareStatement(
            "INSERT INTO events(seq,event_id,branch,parent_event_ids,virtual_time,actor,payload_json,payload_hash) VALUES(?,?,?,?,?,?,?,?)"
        ).apply {
            setLong(1, seq); setString(2, eventId); setString(3, branch)
            setString(4, Hashing.canonicalJson.encodeToString(kotlinx.serialization.builtins.ListSerializer(kotlinx.serialization.serializer<String>()), parentIds))
            setString(5, virtualTime); setString(6, useActor); setString(7, payloadJson); setString(8, payloadHash)
        }.use { it.executeUpdate() }
        conn.prepareStatement(
            "INSERT INTO branches(branch,tip_event_id,fork_event_id) VALUES(?,?,?) " +
                "ON CONFLICT(branch) DO UPDATE SET tip_event_id=excluded.tip_event_id"
        ).apply {
            setString(1, branch); setString(2, eventId)
            setString(3, if (branch == MAIN) null else forkPoint(branch))
        }.use { it.executeUpdate() }
        conn.commit()
        return env
    }

    private fun forkPoint(branch: String): String? {
        if (branch == MAIN) return tip(MAIN)
        val rs = conn.createStatement().executeQuery("SELECT fork_event_id FROM branches WHERE branch='$branch'")
        val v = if (rs.next()) rs.getString(1) else null
        rs.close(); return v
    }

    @Synchronized
    fun tip(branch: String = MAIN): String? {
        val rs = conn.createStatement().executeQuery("SELECT tip_event_id FROM branches WHERE branch='$branch'")
        val v = if (rs.next()) rs.getString(1) else null
        rs.close(); return v
    }

    @Synchronized
    fun branches(): List<BranchRow> {
        val out = mutableListOf<BranchRow>()
        val rs = conn.createStatement().executeQuery("SELECT branch,tip_event_id,fork_event_id FROM branches ORDER BY branch")
        while (rs.next()) out += BranchRow(rs.getString(1), rs.getString(2), rs.getString(3))
        rs.close(); return out
    }

    @Synchronized
    fun allEvents(): List<EventEnvelope> {
        val out = mutableListOf<EventEnvelope>()
        val rs = conn.createStatement().executeQuery(
            "SELECT event_id,seq,branch,parent_event_ids,virtual_time,actor,payload_json,payload_hash FROM events ORDER BY seq"
        )
        while (rs.next()) out += row(rs)
        rs.close(); return out
    }

    private fun row(rs: java.sql.ResultSet): EventEnvelope {
        val payload = Hashing.canonicalJson.decodeFromString(EventPayload.serializer(), rs.getString(7))
        return EventEnvelope(
            eventId = rs.getString(1), seq = rs.getLong(2), branch = rs.getString(3),
            parentEventIds = Hashing.canonicalJson.decodeFromString(
                kotlinx.serialization.builtins.ListSerializer(kotlinx.serialization.serializer<String>()), rs.getString(4)
            ),
            virtualTime = rs.getString(5), actor = rs.getString(6),
            payload = payload, payloadHash = rs.getString(8),
        )
    }

    /** Events visible on a branch: ancestors of its tip reachable via parent links. */
    @Synchronized
    fun eventsOnBranch(branch: String = MAIN): List<EventEnvelope> {
        val all = allEvents()
        val byId = all.associateBy { it.eventId }
        val tipId = tip(branch) ?: return emptyList()
        val visible = linkedSetOf<String>()
        val stack = ArrayDeque<String>(); stack += tipId
        while (stack.isNotEmpty()) {
            val id = stack.removeLast()
            if (!visible.add(id)) continue
            byId[id]?.parentEventIds?.forEach { stack += it }
        }
        return all.filter { it.eventId in visible }
    }

    @Synchronized
    fun eventsUntil(eventId: String): List<EventEnvelope> {
        val all = allEvents()
        val byId = all.associateBy { it.eventId }
        if (byId[eventId] == null) return all
        val seen = linkedSetOf<String>()
        val stack = ArrayDeque<String>(); stack += eventId
        while (stack.isNotEmpty()) {
            val id = stack.removeLast()
            if (!seen.add(id)) continue
            byId[id]?.parentEventIds?.forEach { stack += it }
        }
        return all.filter { it.eventId in seen }
    }

    @Synchronized
    fun createBranch(name: String, fromEventId: String): BranchRow {
        require(name != MAIN) { "cannot fork main" }
        require(allEvents().any { it.eventId == fromEventId }) { "fork point unknown" }
        conn.prepareStatement(
            "INSERT INTO branches(branch,tip_event_id,fork_event_id) VALUES(?,?,?) " +
                "ON CONFLICT(branch) DO UPDATE SET tip_event_id=?, fork_event_id=?"
        ).apply {
            setString(1, name); setString(2, fromEventId); setString(3, fromEventId)
            setString(4, fromEventId); setString(5, fromEventId)
        }.use { it.executeUpdate() }
        conn.commit()
        return BranchRow(name, fromEventId, fromEventId)
    }

    @Synchronized
    fun exportBundle(name: String, seed: Long): ProjectBundle =
        ProjectBundle("rgsb-bundle-1", projectId, name, seed, allEvents(), branches())

    @Synchronized
    fun replaceWith(bundle: ProjectBundle) {
        conn.createStatement().use { st ->
            st.executeUpdate("DELETE FROM events")
            st.executeUpdate("DELETE FROM branches")
        }
        val insertEvent = conn.prepareStatement(
            "INSERT INTO events(seq,event_id,branch,parent_event_ids,virtual_time,actor,payload_json,payload_hash) VALUES(?,?,?,?,?,?,?,?)"
        )
        for (e in bundle.events.sortedBy { it.seq }) {
            val payloadJson = Hashing.canonicalJson.encodeToString(EventPayload.serializer(), e.payload)
            check(Hashing.sha256Hex(payloadJson) == e.payloadHash) { "payload hash mismatch on import for event ${e.eventId}" }
            insertEvent.clearParameters()
            insertEvent.setLong(1, e.seq); insertEvent.setString(2, e.eventId); insertEvent.setString(3, e.branch)
            insertEvent.setString(4, Hashing.canonicalJson.encodeToString(kotlinx.serialization.builtins.ListSerializer(kotlinx.serialization.serializer<String>()), e.parentEventIds))
            insertEvent.setString(5, e.virtualTime); insertEvent.setString(6, e.actor)
            insertEvent.setString(7, payloadJson); insertEvent.setString(8, e.payloadHash)
            insertEvent.executeUpdate()
        }
        val insertBranch = conn.prepareStatement(
            "INSERT INTO branches(branch,tip_event_id,fork_event_id) VALUES(?,?,?)"
        )
        for (b in bundle.branches) {
            insertBranch.clearParameters()
            insertBranch.setString(1, b.branch); insertBranch.setString(2, b.tipEventId); insertBranch.setString(3, b.forkEventId)
            insertBranch.executeUpdate()
        }
        conn.commit()
    }

    fun meta(key: String): String? {
        val rs = conn.createStatement().executeQuery("SELECT value FROM meta WHERE key='${key.replace("'", "''")}'")
        val v = if (rs.next()) rs.getString(1) else null
        rs.close(); return v
    }

    fun setMeta(key: String, value: String) {
        conn.prepareStatement("INSERT INTO meta(key,value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value")
            .apply { setString(1, key); setString(2, value) }.use { it.executeUpdate() }
        conn.commit()
    }

    companion object {
        const val MAIN = "main"
    }
}
