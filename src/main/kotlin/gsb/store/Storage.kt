package gsb.store

import gsb.model.*
import kotlinx.serialization.json.Json
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Statement

val JSON = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    prettyPrint = false
}

class Storage(private val path: String) {
    val conn: Connection = DriverManager.getConnection("jdbc:sqlite:$path").apply {
        createStatement().execute("PRAGMA foreign_keys=ON")
    }

    init { migrate() }

    private fun migrate(stmt: Statement = conn.createStatement()) {
        stmt.executeUpdate("""
            CREATE TABLE IF NOT EXISTS meta(
              key TEXT PRIMARY KEY, value TEXT NOT NULL
            )""")
        stmt.executeUpdate("""
            CREATE TABLE IF NOT EXISTS nodes(
              id TEXT PRIMARY KEY, kind TEXT NOT NULL, name TEXT NOT NULL,
              origin TEXT NOT NULL, created_at TEXT NOT NULL,
              current_version_id TEXT NOT NULL, version_ids TEXT NOT NULL,
              project_id TEXT NOT NULL)""")
        stmt.executeUpdate("""
            CREATE TABLE IF NOT EXISTS versions(
              id TEXT PRIMARY KEY, node_id TEXT NOT NULL, parent_version_id TEXT,
              revision INTEGER NOT NULL, created_at TEXT NOT NULL, author TEXT NOT NULL,
              note TEXT NOT NULL, payload_ref TEXT NOT NULL, kind TEXT NOT NULL,
              payload_json TEXT NOT NULL, project_id TEXT NOT NULL)""")
        stmt.executeUpdate("""
            CREATE TABLE IF NOT EXISTS events(
              id TEXT PRIMARY KEY, seq INTEGER NOT NULL, at TEXT NOT NULL,
              type TEXT NOT NULL, project_id TEXT NOT NULL, subject_id TEXT,
              detail TEXT NOT NULL, sources TEXT NOT NULL, evidence TEXT)""")
        stmt.executeUpdate("""
            CREATE TABLE IF NOT EXISTS artifacts(
              id TEXT PRIMARY KEY, project_id TEXT NOT NULL, kind TEXT NOT NULL,
              payload_json TEXT NOT NULL, status TEXT NOT NULL, stale_reason TEXT,
              created_event_id TEXT NOT NULL)""")
        stmt.executeUpdate("""
            CREATE TABLE IF NOT EXISTS decisions(
              id TEXT PRIMARY KEY, project_id TEXT NOT NULL, at TEXT NOT NULL,
              action TEXT NOT NULL, subject_id TEXT NOT NULL, target_candidate TEXT,
              note TEXT NOT NULL, withdrawn INTEGER NOT NULL DEFAULT 0,
              parent_decision_id TEXT)""")
        stmt.executeUpdate("CREATE UNIQUE INDEX IF NOT EXISTS uq_event_seq ON events(seq)")
    }

    fun meta(key: String): String? =
        conn.prepareStatement("SELECT value FROM meta WHERE key=?").use { ps ->
            ps.setString(1, key); val rs = ps.executeQuery(); if (rs.next()) rs.getString(1) else null
        }
    fun setMeta(key: String, value: String) =
        conn.prepareStatement("INSERT INTO meta(key,value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value")
            .use { it.setString(1, key); it.setString(2, value); it.executeUpdate() }

    // ---- nodes / versions ----
    fun upsertNode(n: InputNode, projectId: String) {
        conn.prepareStatement("""INSERT INTO nodes(id,kind,name,origin,created_at,current_version_id,version_ids,project_id)
            VALUES(?,?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET name=excluded.name,
            current_version_id=excluded.current_version_id, version_ids=excluded.version_ids""")
            .use { ps ->
                ps.setString(1, n.id); ps.setString(2, n.kind.name); ps.setString(3, n.name)
                ps.setString(4, n.origin); ps.setString(5, n.createdAt)
                ps.setString(6, n.currentVersionId); ps.setString(7, JSON.encodeToString(n.versionIds))
                ps.setString(8, projectId); ps.executeUpdate()
            }
    }

    fun node(id: String): InputNode? =
        conn.prepareStatement("SELECT * FROM nodes WHERE id=?").use { ps ->
            ps.setString(1, id); val rs = ps.executeQuery()
            if (rs.next()) InputNode(
                rs.getString("id"), NodeKind.valueOf(rs.getString("kind")),
                rs.getString("name"), rs.getString("origin"), rs.getString("created_at"),
                rs.getString("current_version_id"),
                JSON.decodeFromString(rs.getString("version_ids"))
            ) else null
        }

    fun allNodes(projectId: String): List<InputNode> {
        val out = mutableListOf<InputNode>()
        conn.prepareStatement("SELECT * FROM nodes WHERE project_id=? ORDER BY id").use { ps ->
            ps.setString(1, projectId); val rs = ps.executeQuery()
            while (rs.next()) out += InputNode(
                rs.getString("id"), NodeKind.valueOf(rs.getString("kind")),
                rs.getString("name"), rs.getString("origin"), rs.getString("created_at"),
                rs.getString("current_version_id"),
                JSON.decodeFromString(rs.getString("version_ids"))
            )
        }
        return out
    }

    fun insertVersion(v: InputVersion, kind: NodeKind, payloadJson: String, projectId: String) {
        conn.prepareStatement("""INSERT INTO versions(id,node_id,parent_version_id,revision,created_at,author,note,payload_ref,kind,payload_json,project_id)
            VALUES(?,?,?,?,?,?,?,?,?,?,?)""").use { ps ->
            ps.setString(1, v.id); ps.setString(2, v.nodeId); ps.setString(3, v.parentVersionId)
            ps.setInt(4, v.revision); ps.setString(5, v.createdAt); ps.setString(6, v.author)
            ps.setString(7, v.note); ps.setString(8, v.payloadRef); ps.setString(9, kind.name)
            ps.setString(10, payloadJson); ps.setString(11, projectId); ps.executeUpdate()
        }
    }

    fun version(id: String): Triple<InputVersion, NodeKind, String>? =
        conn.prepareStatement("SELECT * FROM versions WHERE id=?").use { ps ->
            ps.setString(1, id); val rs = ps.executeQuery()
            if (rs.next()) Triple(InputVersion(
                rs.getString("id"), rs.getString("node_id"), rs.getString("parent_version_id"),
                rs.getInt("revision"), rs.getString("created_at"), rs.getString("author"),
                rs.getString("note"), rs.getString("payload_ref")),
                NodeKind.valueOf(rs.getString("kind")), rs.getString("payload_json"))
            else null
        }

    fun versionsOf(nodeId: String): List<Triple<InputVersion, NodeKind, String>> {
        val out = mutableListOf<Triple<InputVersion, NodeKind, String>>()
        conn.prepareStatement("SELECT * FROM versions WHERE node_id=? ORDER BY revision").use { ps ->
            ps.setString(1, nodeId); val rs = ps.executeQuery()
            while (rs.next()) out += Triple(InputVersion(
                rs.getString("id"), rs.getString("node_id"), rs.getString("parent_version_id"),
                rs.getInt("revision"), rs.getString("created_at"), rs.getString("author"),
                rs.getString("note"), rs.getString("payload_ref")),
                NodeKind.valueOf(rs.getString("kind")), rs.getString("payload_json"))
        }
        return out
    }

    // ---- events ----
    fun nextEventSeq(projectId: String): Long =
        conn.prepareStatement("SELECT COALESCE(MAX(seq),0)+1 AS s FROM events WHERE project_id=?").use { ps ->
            ps.setString(1, projectId); val rs = ps.executeQuery(); rs.next(); rs.getLong(1)
        }

    fun appendEvent(e: Event) {
        conn.prepareStatement("""INSERT INTO events(id,seq,at,type,project_id,subject_id,detail,sources,evidence)
            VALUES(?,?,?,?,?,?,?,?,?)""").use { ps ->
            ps.setString(1, e.id); ps.setLong(2, e.seq); ps.setString(3, e.at)
            ps.setString(4, e.type.name); ps.setString(5, e.projectId); ps.setString(6, e.subjectId)
            ps.setString(7, e.detail); ps.setString(8, JSON.encodeToString(e.sources))
            ps.setString(9, e.evidence); ps.executeUpdate()
        }
    }

    fun events(projectId: String): List<Event> {
        val out = mutableListOf<Event>()
        conn.prepareStatement("SELECT * FROM events WHERE project_id=? ORDER BY seq").use { ps ->
            ps.setString(1, projectId); val rs = ps.executeQuery()
            while (rs.next()) out += Event(
                rs.getString("id"), rs.getLong("seq"), rs.getString("at"),
                EventType.valueOf(rs.getString("type")), rs.getString("project_id"),
                rs.getString("subject_id"), rs.getString("detail"),
                JSON.decodeFromString(rs.getString("sources")),
                rs.getString("evidence"))
        }
        return out
    }

    fun eventAtSeq(projectId: String, seq: Long): Event? =
        conn.prepareStatement("SELECT * FROM events WHERE project_id=? AND seq<=? ORDER BY seq DESC LIMIT 1").use { ps ->
            ps.setString(1, projectId); ps.setLong(2, seq); val rs = ps.executeQuery()
            if (rs.next()) Event(
                rs.getString("id"), rs.getLong("seq"), rs.getString("at"),
                EventType.valueOf(rs.getString("type")), rs.getString("project_id"),
                rs.getString("subject_id"), rs.getString("detail"),
                JSON.decodeFromString(rs.getString("sources")), rs.getString("evidence"))
            else null
        }

    // ---- artifacts ----
    fun putArtifact(id: String, projectId: String, kind: String, payloadJson: String, status: ArtifactStatus, staleReason: String?, eventId: String) {
        conn.prepareStatement("""INSERT INTO artifacts(id,project_id,kind,payload_json,status,stale_reason,created_event_id)
            VALUES(?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET payload_json=excluded.payload_json,
            status=excluded.status, stale_reason=excluded.stale_reason""").use { ps ->
            ps.setString(1, id); ps.setString(2, projectId); ps.setString(3, kind)
            ps.setString(4, payloadJson); ps.setString(5, status.name)
            ps.setString(6, staleReason); ps.setString(7, eventId); ps.executeUpdate()
        }
    }

    fun artifact(id: String): Pair<String, String>? =
        conn.prepareStatement("SELECT kind,payload_json FROM artifacts WHERE id=?").use { ps ->
            ps.setString(1, id); val rs = ps.executeQuery()
            if (rs.next()) rs.getString("kind") to rs.getString("payload_json") else null
        }

    fun allArtifacts(projectId: String): List<Triple<String, String, ArtifactStatus>> {
        val out = mutableListOf<Triple<String, String, ArtifactStatus>>()
        conn.prepareStatement("SELECT kind,payload_json,status FROM artifacts WHERE project_id=? ORDER BY id").use { ps ->
            ps.setString(1, projectId); val rs = ps.executeQuery()
            while (rs.next()) out += Triple(
                rs.getString("kind"), rs.getString("payload_json"),
                ArtifactStatus.valueOf(rs.getString("status")))
        }
        return out
    }

    fun setArtifactStatus(id: String, status: ArtifactStatus, reason: String?) {
        conn.prepareStatement("UPDATE artifacts SET status=?, stale_reason=? WHERE id=?").use { ps ->
            ps.setString(1, status.name); ps.setString(2, reason); ps.setString(3, id); ps.executeUpdate()
        }
    }

    fun artifactCreatedEvent(id: String): String? =
        conn.prepareStatement("SELECT created_event_id FROM artifacts WHERE id=?").use { ps ->
            ps.setString(1, id); val rs = ps.executeQuery(); if (rs.next()) rs.getString(1) else null
        }

    // ---- decisions ----
    fun insertDecision(d: DecisionRecord) {
        conn.prepareStatement("""INSERT INTO decisions(id,project_id,at,action,subject_id,target_candidate,note,withdrawn,parent_decision_id)
            VALUES(?,?,?,?,?,?,?,?,?)""").use { ps ->
            ps.setString(1, d.id); ps.setString(2, d.projectId); ps.setString(3, d.at)
            ps.setString(4, d.action); ps.setString(5, d.subjectId); ps.setString(6, d.targetCandidate)
            ps.setString(7, d.note); ps.setInt(8, if (d.withdrawn) 1 else 0)
            ps.setString(9, d.parentDecisionId); ps.executeUpdate()
        }
    }
    fun setWithdrawn(id: String) = conn.prepareStatement("UPDATE decisions SET withdrawn=1 WHERE id=?")
        .use { it.setString(1, id); it.executeUpdate() }

    fun decisions(projectId: String): List<DecisionRecord> {
        val out = mutableListOf<DecisionRecord>()
        conn.prepareStatement("SELECT * FROM decisions WHERE project_id=? ORDER BY at,id").use { ps ->
            ps.setString(1, projectId); val rs = ps.executeQuery()
            while (rs.next()) out += DecisionRecord(
                rs.getString("id"), rs.getString("project_id"), rs.getString("at"),
                rs.getString("action"), rs.getString("subject_id"), rs.getString("target_candidate"),
                rs.getString("note"), rs.getInt("withdrawn") == 1, rs.getString("parent_decision_id"))
        }
        return out
    }

    fun close() = conn.close()
}

data class DecisionRecord(
    val id: String, val projectId: String, val at: String, val action: String,
    val subjectId: String, val targetCandidate: String?, val note: String,
    val withdrawn: Boolean, val parentDecisionId: String?
)
