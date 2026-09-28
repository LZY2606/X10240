package org.chronobench

import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * SQLite 持久化。原始来源(source_versions)一经提交不可覆盖：修订写入新版本+父版本链。
 * 派生结果(derivations)带算法版本/参数指纹/直接来源；stale 判定由 ProjectState 完成。
 * events 为追加式历史，seq 单调递增，支持按事件回放（时间旅行）。
 */
class Database private constructor(val conn: Connection, val path: Path?) {
    val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
        encodeDefaults = true
    }

    companion object {
        fun open(dbPath: Path?): Database {
            Class.forName("org.sqlite.JDBC")
            val url = if (dbPath == null) "jdbc:sqlite::memory:" else "jdbc:sqlite:$dbPath"
            val c = DriverManager.getConnection(url)
            c.createStatement().use { s ->
                s.executeUpdate("PRAGMA foreign_keys=ON")
                s.executeUpdate("PRAGMA journal_mode=WAL")
            }
            val db = Database(c, dbPath)
            db.migrate()
            return db
        }

        private val SCHEMA = """
        CREATE TABLE IF NOT EXISTS projects(
          id TEXT PRIMARY KEY,
          name TEXT NOT NULL,
          description TEXT,
          created_at INTEGER NOT NULL
        );
        CREATE TABLE IF NOT EXISTS source_versions(
          id TEXT NOT NULL,
          project_id TEXT NOT NULL,
          kind TEXT NOT NULL,
          version INTEGER NOT NULL,
          parent_id TEXT,
          payload TEXT NOT NULL,
          submitted_at INTEGER NOT NULL,
          submitted_by TEXT NOT NULL,
          superseded INTEGER NOT NULL DEFAULT 0,
          provenance TEXT NOT NULL,
          PRIMARY KEY(id, version)
        );
        CREATE INDEX IF NOT EXISTS idx_src_project ON source_versions(project_id, kind);
        CREATE TABLE IF NOT EXISTS derivations(
          derivation_id TEXT PRIMARY KEY,
          project_id TEXT NOT NULL,
          kind TEXT NOT NULL,
          payload TEXT NOT NULL,
          algorithm_version TEXT NOT NULL,
          param_fingerprint TEXT NOT NULL,
          direct_sources TEXT NOT NULL,
          computed_at INTEGER NOT NULL,
          superseded_by_event_seq INTEGER
        );
        CREATE INDEX IF NOT EXISTS idx_der_project ON derivations(project_id, kind);
        CREATE TABLE IF NOT EXISTS events(
          seq INTEGER PRIMARY KEY AUTOINCREMENT,
          event_id TEXT NOT NULL UNIQUE,
          project_id TEXT NOT NULL,
          type TEXT NOT NULL,
          actor TEXT NOT NULL,
          at INTEGER NOT NULL,
          targets TEXT NOT NULL,
          inputs TEXT NOT NULL,
          outputs TEXT NOT NULL,
          detail TEXT,
          summary TEXT NOT NULL
        );
        CREATE INDEX IF NOT EXISTS idx_events_project ON events(project_id, seq);
        CREATE TABLE IF NOT EXISTS decisions(
          project_id TEXT NOT NULL,
          scope TEXT NOT NULL,
          target TEXT NOT NULL,
          decision TEXT NOT NULL,
          reason TEXT,
          event_seq INTEGER NOT NULL,
          PRIMARY KEY(project_id, scope, target)
        );
        """.trimIndent()
    }

    private fun migrate() {
        conn.createStatement().use { it.executeUpdate(SCHEMA) }
    }

    fun close() = conn.close()

    // ---------- projects ----------
    fun createProject(p: Project) {
        conn.prepareStatement(
            "INSERT INTO projects(id,name,description,created_at) VALUES(?,?,?,?)"
        ).use { ps ->
            ps.setString(1, p.id); ps.setString(2, p.name)
            ps.setString(3, p.description); ps.setLong(4, p.createdAt)
            ps.executeUpdate()
        }
    }

    fun listProjects(): List<Project> {
        conn.createStatement().executeQuery("SELECT id,name,description,created_at FROM projects ORDER BY created_at").use { rs ->
            val out = ArrayList<Project>()
            while (rs.next()) out += Project(rs.getString(1), rs.getString(2), rs.getLong(4), rs.getString(3))
            return out
        }
    }

    fun getProject(id: String): Project? {
        conn.prepareStatement("SELECT id,name,description,created_at FROM projects WHERE id=?").use { ps ->
            ps.setString(1, id)
            ps.executeQuery().use { rs ->
                if (rs.next()) return Project(rs.getString(1), rs.getString(2), rs.getLong(4), rs.getString(3))
            }
        }
        return null
    }

    // ---------- sources (immutable versions) ----------
    fun insertSource(v: SourceVersion) {
        conn.prepareStatement(
            """INSERT INTO source_versions(id,project_id,kind,version,parent_id,payload,submitted_at,
              submitted_by,superseded,provenance) VALUES(?,?,?,?,?,?,?,?,?,?)"""
        ).use { ps ->
            ps.setString(1, v.id); ps.setString(2, v.projectId); ps.setString(3, v.kind.name)
            ps.setInt(4, v.version); ps.setString(5, v.parentId)
            ps.setString(6, json.encodeToString(JsonObject.serializer(), v.payload))
            ps.setLong(7, v.submittedAt); ps.setString(8, v.submittedBy)
            ps.setInt(9, if (v.superseded) 1 else 0)
            ps.setString(10, json.encodeToString(Provenance.serializer(), v.provenance))
            ps.executeUpdate()
        }
    }

    fun markSuperseded(id: String, version: Int) {
        conn.prepareStatement("UPDATE source_versions SET superseded=1 WHERE id=? AND version=?").use { ps ->
            ps.setString(1, id); ps.setInt(2, version); ps.executeUpdate()
        }
    }

    fun maxSourceVersion(id: String): Int {
        conn.prepareStatement("SELECT COALESCE(MAX(version),0) FROM source_versions WHERE id=?").use { ps ->
            ps.setString(1, id); ps.executeQuery().use { rs -> return if (rs.next()) rs.getInt(1) else 0 }
        }
    }

    fun loadSource(id: String, version: Int): SourceVersion? {
        conn.prepareStatement(
            """SELECT id,project_id,kind,version,parent_id,payload,submitted_at,submitted_by,superseded,provenance
              FROM source_versions WHERE id=? AND version=?"""
        ).use { ps ->
            ps.setString(1, id); ps.setInt(2, version)
            ps.executeQuery().use { rs -> if (rs.next()) return rowToSource(rs) }
        }
        return null
    }

    fun listSourceVersions(projectId: String): List<SourceVersion> {
        conn.prepareStatement(
            """SELECT id,project_id,kind,version,parent_id,payload,submitted_at,submitted_by,superseded,provenance
              FROM source_versions WHERE project_id=? ORDER BY submitted_at, version"""
        ).use { ps ->
            ps.setString(1, projectId); ps.executeQuery().use { rs ->
                val out = ArrayList<SourceVersion>()
                while (rs.next()) out += rowToSource(rs)
                return out
            }
        }
    }

    private fun rowToSource(rs: ResultSet): SourceVersion {
        val payloadEl = json.parseToJsonElement(rs.getString(6))
        return SourceVersion(
            id = rs.getString(1), projectId = rs.getString(2),
            kind = SourceKind.valueOf(rs.getString(3)), version = rs.getInt(4),
            parentId = rs.getString(5), payload = payloadEl,
            submittedAt = rs.getLong(7), submittedBy = rs.getString(8),
            superseded = rs.getInt(9) == 1,
            provenance = json.decodeFromString(Provenance.serializer(), rs.getString(10))
        )
    }

    // ---------- derivations ----------
    fun insertDerivation(kind: String, r: CalibrationResult) {
        conn.prepareStatement(
            """INSERT INTO derivations(derivation_id,project_id,kind,payload,algorithm_version,
              param_fingerprint,direct_sources,computed_at) VALUES(?,?,?,?,?,?,?,?)"""
        ).use { ps ->
            ps.setString(1, r.derivationId); ps.setString(2, r.projectId); ps.setString(3, kind)
            ps.setString(4, json.encodeToString(CalibrationResult.serializer(), r))
            ps.setString(5, r.algorithmVersion); ps.setString(6, r.paramFingerprint)
            ps.setString(7, r.directSources.joinToString(",")); ps.setLong(8, r.computedAt)
            ps.executeUpdate()
        }
    }

    fun insertSequenceDerivation(r: SequenceResult) {
        conn.prepareStatement(
            """INSERT INTO derivations(derivation_id,project_id,kind,payload,algorithm_version,
              param_fingerprint,direct_sources,computed_at) VALUES(?,?,?,?,?,?,?,?)"""
        ).use { ps ->
            ps.setString(1, r.derivationId); ps.setString(2, r.projectId); ps.setString(3, "SEQUENCE")
            ps.setString(4, json.encodeToString(SequenceResult.serializer(), r))
            ps.setString(5, r.algorithmVersion); ps.setString(6, r.paramFingerprint)
            ps.setString(7, r.directSources.joinToString(",")); ps.setLong(8, r.computedAt)
            ps.executeUpdate()
        }
    }

    fun listDerivations(projectId: String, kind: String): List<String> {
        conn.prepareStatement("SELECT payload FROM derivations WHERE project_id=? AND kind=? ORDER BY computed_at").use { ps ->
            ps.setString(1, projectId); ps.setString(2, kind)
            ps.executeQuery().use { rs ->
                val out = ArrayList<String>()
                while (rs.next()) out += rs.getString(1)
                return out
            }
        }
    }

    // ---------- events ----------
    fun appendEvent(e: EventRecord): Long {
        var seq = -1L
        conn.prepareStatement(
            """INSERT INTO events(event_id,project_id,type,actor,at,targets,inputs,outputs,detail,summary)
              VALUES(?,?,?,?,?,?,?,?,?,?)""",
            java.sql.Statement.RETURN_GENERATED_KEYS
        ).use { ps ->
            ps.setString(1, e.eventId); ps.setString(2, e.projectId); ps.setString(3, e.type)
            ps.setString(4, e.actor); ps.setLong(5, e.at)
            ps.setString(6, e.targets.joinToString(",")); ps.setString(7, e.inputs.joinToString(","))
            ps.setString(8, e.outputs.joinToString(","))
            ps.setString(9, e.detail?.let { json.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), it) })
            ps.setString(10, e.summary)
            ps.executeUpdate()
            ps.generatedKeys.use { k -> if (k.next()) seq = k.getLong(1) }
        }
        return seq
    }

    fun listEvents(projectId: String, upToSeq: Long? = null): List<EventRecord> {
        val sql = "SELECT seq,event_id,project_id,type,actor,at,targets,inputs,outputs,detail,summary FROM events" +
            " WHERE project_id=?" + (if (upToSeq != null) " AND seq<=$upToSeq" else "") + " ORDER BY seq"
        conn.prepareStatement(sql).use { ps ->
            ps.setString(1, projectId)
            ps.executeQuery().use { rs ->
                val out = ArrayList<EventRecord>()
                while (rs.next()) out += EventRecord(
                    rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                    rs.getLong(6), split(rs.getString(7)), split(rs.getString(8)), split(rs.getString(9)),
                    rs.getString(10)?.let { json.parseToJsonElement(it) }, rs.getString(11)
                )
                return out
            }
        }
    }

    fun eventAt(projectId: String, seq: Long): EventRecord? = listEvents(projectId, seq).lastOrNull { it.seq == seq }

    // ---------- decisions ----------
    fun upsertDecision(projectId: String, scope: String, target: String, decision: String, reason: String?, seq: Long) {
        conn.prepareStatement(
            """INSERT INTO decisions(project_id,scope,target,decision,reason,event_seq) VALUES(?,?,?,?,?,?)
              ON CONFLICT(project_id,scope,target) DO UPDATE SET decision=excluded.decision,
                reason=excluded.reason, event_seq=excluded.event_seq"""
        ).use { ps ->
            ps.setString(1, projectId); ps.setString(2, scope); ps.setString(3, target)
            ps.setString(4, decision); ps.setString(5, reason); ps.setLong(6, seq)
            ps.executeUpdate()
        }
    }

    fun deleteDecision(projectId: String, scope: String, target: String) {
        conn.prepareStatement("DELETE FROM decisions WHERE project_id=? AND scope=? AND target=?").use { ps ->
            ps.setString(1, projectId); ps.setString(2, scope); ps.setString(3, target); ps.executeUpdate()
        }
    }

    /** target -> (decision, reason, eventSeq) */
    fun listDecisions(projectId: String, scope: String): Map<String, Triple<String, String?, Long>> {
        conn.prepareStatement("SELECT target,decision,reason,event_seq FROM decisions WHERE project_id=? AND scope=?").use { ps ->
            ps.setString(1, projectId); ps.setString(2, scope)
            ps.executeQuery().use { rs ->
                val m = LinkedHashMap<String, Triple<String, String?, Long>>()
                while (rs.next()) m[rs.getString(1)] = Triple(rs.getString(2), rs.getString(3), rs.getLong(4))
                return m
            }
        }
    }

    fun listDecisionsByPrefix(projectId: String, scopePrefix: String): Map<String, Triple<String, String?, Long>> {
        conn.prepareStatement("SELECT scope,target,decision,reason,event_seq FROM decisions WHERE project_id=? AND scope LIKE ? ESCAPE '\\'").use { ps ->
            ps.setString(1, projectId); ps.setString(2, scopePrefix.replace("%","\\%").replace("_","\\_") + "%")
            ps.executeQuery().use { rs ->
                val m = LinkedHashMap<String, Triple<String, String?, Long>>()
                while (rs.next()) m[rs.getString(1) + "/" + rs.getString(2)] = Triple(rs.getString(3), rs.getString(4), rs.getLong(5))
                return m
            }
        }
    }

    private fun split(s: String?): List<String> =
        if (s.isNullOrEmpty()) emptyList() else s.split(",").filter { it.isNotEmpty() }
}
