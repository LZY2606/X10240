package org.chronocarbon

import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager

class SqliteStore(private val path: Path) : EventStore {
    private val conn: Connection

    override val projectId: String
        get() = conn.createStatement().executeQuery("SELECT value FROM meta WHERE key='project_id'")
            .takeIf { it.next() }?.getString(1) ?: deriveProjectId()

    init {
        if (path.parent != null) Files.createDirectories(path.parent)
        conn = DriverManager.getConnection("jdbc:sqlite:$path")
        conn.createStatement().use { st ->
            st.executeUpdate("PRAGMA journal_mode=WAL")
            st.executeUpdate("PRAGMA foreign_keys=ON")
        }
        schema()
    }

    private fun deriveProjectId(): String {
        val id = "proj-file-" + Hashing.sha256Hex(path.toAbsolutePath().toString()).take(8)
        conn.prepareStatement("INSERT OR IGNORE INTO meta(key,value) VALUES('project_id',?)").use { it ->
            it.setString(1, id); it.executeUpdate()
        }
        return id
    }

    private fun schema() {
        conn.createStatement().use { st ->
            st.executeUpdate(
                """CREATE TABLE IF NOT EXISTS meta (
                    key TEXT PRIMARY KEY, value TEXT NOT NULL)""".trimIndent()
            )
            st.executeUpdate(
                """CREATE TABLE IF NOT EXISTS events (
                    seq INTEGER PRIMARY KEY,
                    event_id TEXT UNIQUE NOT NULL,
                    type TEXT NOT NULL,
                    at TEXT NOT NULL,
                    actor TEXT NOT NULL,
                    payload_json TEXT NOT NULL,
                    undone INTEGER NOT NULL DEFAULT 0,
                    undo_of_event_id TEXT)""".trimIndent()
            )
            st.executeUpdate(
                """CREATE TABLE IF NOT EXISTS derivations (
                    id TEXT PRIMARY KEY,
                    event_seq INTEGER NOT NULL,
                    kind TEXT NOT NULL,
                    request_fingerprint TEXT NOT NULL,
                    algorithm_version TEXT NOT NULL,
                    direct_sources TEXT NOT NULL,
                    result_json TEXT NOT NULL,
                    stale INTEGER NOT NULL DEFAULT 0,
                    superseded_by TEXT)""".trimIndent()
            )
            st.executeUpdate(
                """CREATE TABLE IF NOT EXISTS candidates (
                    id TEXT PRIMARY KEY,
                    analysis_id TEXT NOT NULL,
                    kind TEXT NOT NULL,
                    subject_id TEXT NOT NULL,
                    description TEXT NOT NULL,
                    proposed_offset TEXT,
                    status TEXT NOT NULL,
                    decided_by_event_id TEXT,
                    rejection TEXT,
                    created_seq INTEGER NOT NULL)""".trimIndent()
            )
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_deriv_kind ON derivations(kind)")
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_events_seq ON events(seq)")
        }
    }

    override fun nextSeq(): Long =
        conn.createStatement().executeQuery("SELECT COALESCE(MAX(seq)+1,1) FROM events").use { rs ->
            rs.getLong(1)
        }

    override fun append(envelope: EventEnvelope) {
        conn.prepareStatement(
            """INSERT INTO events(seq,event_id,type,at,actor,payload_json,undone,undo_of_event_id)
               VALUES(?,?,?,?,?,?,?,?)""".trimIndent()
        ).use { ps ->
            ps.setLong(1, envelope.seq)
            ps.setString(2, envelope.eventId)
            ps.setString(3, envelope.type)
            ps.setString(4, envelope.at)
            ps.setString(5, envelope.actor)
            ps.setString(6, envelope.payloadJson)
            ps.setInt(7, if (envelope.undone) 1 else 0)
            ps.setString(8, envelope.undoOfEventId)
            ps.executeUpdate()
        }
    }

    override fun events(atSeq: Long?): List<EventEnvelope> {
        val sql = buildString {
            append("SELECT * FROM events")
            if (atSeq != null) append(" WHERE seq<=$atSeq")
            append(" ORDER BY seq")
        }
        return conn.createStatement().executeQuery(sql).use(::readEnvelope)
    }

    private fun readEnvelope(rs: java.sql.ResultSet): List<EventEnvelope> {
        val out = ArrayList<EventEnvelope>()
        while (rs.next()) {
            out += EventEnvelope(
                seq = rs.getLong("seq"),
                eventId = rs.getString("event_id"),
                type = rs.getString("type"),
                at = rs.getString("at"),
                actor = rs.getString("actor"),
                payloadJson = rs.getString("payload_json"),
                undone = rs.getInt("undone") == 1,
                undoOfEventId = rs.getString("undo_of_event_id")
            )
        }
        return out
    }

    override fun event(eventId: String): EventEnvelope? {
        conn.prepareStatement("SELECT * FROM events WHERE event_id=?").use { ps ->
            ps.setString(1, eventId)
            return ps.executeQuery().use(::readEnvelope).firstOrNull()
        }
    }

    override fun saveDerivation(record: DerivationRecord): DerivationRecord {
        conn.prepareStatement(
            """INSERT OR REPLACE INTO derivations
               (id,event_seq,kind,request_fingerprint,algorithm_version,direct_sources,result_json,stale,superseded_by)
               VALUES(?,?,?,?,?,?,?,?,?)""".trimIndent()
        ).use { ps ->
            ps.setString(1, record.id)
            ps.setLong(2, record.eventSeq)
            ps.setString(3, record.kind)
            ps.setString(4, record.requestFingerprint)
            ps.setString(5, record.algorithmVersion)
            ps.setString(6, JsonCodec.encode(record.directSources))
            ps.setString(7, record.resultJson)
            ps.setInt(8, if (record.stale) 1 else 0)
            ps.setString(9, record.supersededByDerivationId)
            ps.executeUpdate()
        }
        return record
    }

    private fun readDerivation(rs: java.sql.ResultSet) = DerivationRecord(
        id = rs.getString("id"),
        eventSeq = rs.getLong("event_seq"),
        kind = rs.getString("kind"),
        requestFingerprint = rs.getString("request_fingerprint"),
        algorithmVersion = rs.getString("algorithm_version"),
        directSources = JsonCodec.decode(rs.getString("direct_sources")),
        resultJson = rs.getString("result_json"),
        stale = rs.getInt("stale") == 1,
        supersededByDerivationId = rs.getString("superseded_by")
    )

    override fun derivations(kind: String?): List<DerivationRecord> {
        val sql = if (kind == null) "SELECT * FROM derivations ORDER BY event_seq"
        else "SELECT * FROM derivations WHERE kind='$kind' ORDER BY event_seq"
        val out = ArrayList<DerivationRecord>()
        conn.createStatement().executeQuery(sql).use { rs ->
            while (rs.next()) out += readDerivation(rs)
        }
        return out
    }

    override fun derivation(id: String): DerivationRecord? {
        conn.prepareStatement("SELECT * FROM derivations WHERE id=?").use { ps ->
            ps.setString(1, id)
            return ps.executeQuery().use { rs -> if (rs.next()) readDerivation(rs) else null }
        }
    }

    override fun markCalibrationsStale(predicate: (DerivationRecord) -> Boolean) {
        val updates = derivations("calibration").filter(predicate).filterNot { it.stale }
        conn.prepareStatement("UPDATE derivations SET stale=1 WHERE id=?").use { ps ->
            for (record in updates) { ps.setString(1, record.id); ps.executeUpdate() }
        }
    }

    override fun markSequenceStale(predicate: (DerivationRecord) -> Boolean) {
        val updates = derivations("sequence").filter(predicate).filterNot { it.stale }
        conn.prepareStatement("UPDATE derivations SET stale=1 WHERE id=?").use { ps ->
            for (record in updates) { ps.setString(1, record.id); ps.executeUpdate() }
        }
    }

    override fun saveCandidate(candidate: AdjustmentCandidate) {
        conn.prepareStatement(
            """INSERT OR REPLACE INTO candidates
               (id,analysis_id,kind,subject_id,description,proposed_offset,status,decided_by_event_id,rejection,created_seq)
               VALUES(?,?,?,?,?,?,?,?,?,?)""".trimIndent()
        ).use { ps ->
            ps.setString(1, candidate.id)
            ps.setString(2, candidate.analysisId)
            ps.setString(3, candidate.kind)
            ps.setString(4, candidate.subjectId)
            ps.setString(5, candidate.description)
            ps.setString(6, candidate.proposedOffset?.let { JsonCodec.encode(it) })
            ps.setString(7, candidate.status)
            ps.setString(8, candidate.decidedByEventId)
            ps.setString(9, candidate.rejection?.let { JsonCodec.encode(it) })
            ps.setLong(10, candidate.createdAtEventSeq)
            ps.executeUpdate()
        }
    }

    private fun readCandidate(rs: java.sql.ResultSet) = AdjustmentCandidate(
        id = rs.getString("id"),
        analysisId = rs.getString("analysis_id"),
        kind = rs.getString("kind"),
        subjectId = rs.getString("subject_id"),
        description = rs.getString("description"),
        proposedOffset = rs.getString("proposed_offset")?.let { JsonCodec.decode<OffsetDist>(it) },
        status = rs.getString("status"),
        decidedByEventId = rs.getString("decided_by_event_id"),
        rejection = rs.getString("rejection")?.let { JsonCodec.decode<Rejection>(it) },
        createdAtEventSeq = rs.getLong("created_seq")
    )

    override fun candidates(): List<AdjustmentCandidate> {
        val out = ArrayList<AdjustmentCandidate>()
        conn.createStatement().executeQuery("SELECT * FROM candidates ORDER BY created_seq,id").use { rs ->
            while (rs.next()) out += readCandidate(rs)
        }
        return out
    }

    override fun candidate(id: String): AdjustmentCandidate? {
        conn.prepareStatement("SELECT * FROM candidates WHERE id=?").use { ps ->
            ps.setString(1, id)
            return ps.executeQuery().use { rs -> if (rs.next()) readCandidate(rs) else null }
        }
    }

    override fun resolveCandidate(id: String, accept: Boolean, byEventId: String, reason: String?) {
        conn.prepareStatement(
            "UPDATE candidates SET status=?, decided_by_event_id=?, rejection=? WHERE id=?"
        ).use { ps ->
            ps.setString(1, if (accept) "accepted" else "rejected")
            ps.setString(2, byEventId)
            ps.setString(3, reason?.let { JsonCodec.encode(Rejection("reviewer_rejected", it)) })
            ps.setString(4, id)
            ps.executeUpdate()
        }
    }

    override fun markUndone(eventId: String) {
        conn.prepareStatement("UPDATE events SET undone=1 WHERE event_id=?").use { ps ->
            ps.setString(1, eventId); ps.executeUpdate()
        }
    }

    override fun markMeasurementSuperseded(measurementId: String) {
        val rows = events()
        for (envelope in rows) {
            val payload = runCatching { JsonCodec.decode<DomainEvent>(envelope.payloadJson) }.getOrNull()
            if (payload is DomainEvent.MeasurementSubmitted && payload.measurement.id == measurementId) {
                val updated = payload.copy(measurement = payload.measurement.copy(superseded = true))
                conn.prepareStatement("UPDATE events SET payload_json=? WHERE event_id=?").use { ps ->
                    ps.setString(1, JsonCodec.encode(updated as DomainEvent))
                    ps.setString(2, envelope.eventId)
                    ps.executeUpdate()
                }
            }
        }
    }

    override fun exportBundle(exportedAt: String): ExportBundle {
        val envelopes = events()
        val project = Project.reduce(envelopes).state
        return ExportBundle(
            projectId = project.projectId,
            exportedAt = exportedAt,
            events = envelopes,
            derivations = derivations(),
            candidates = candidates()
        )
    }


    /** Atomic replace used by bundle import: identities, sequence and links survive a DB deletion. */
    fun replaceAll(bundle: ExportBundle) {
        conn.autoCommit = false
        try {
            conn.createStatement().use { st ->
                st.executeUpdate("DELETE FROM events")
                st.executeUpdate("DELETE FROM derivations")
                st.executeUpdate("DELETE FROM candidates")
                st.executeUpdate("DELETE FROM meta")
            }
            conn.prepareStatement("INSERT INTO meta(key,value) VALUES('project_id',?)").use { ps ->
                ps.setString(1, bundle.projectId); ps.executeUpdate()
            }
            for (envelope in bundle.events) append(envelope)
            for (record in bundle.derivations) saveDerivation(record)
            for (candidate in bundle.candidates) saveCandidate(candidate)
            conn.commit()
        } catch (e: Exception) {
            conn.rollback()
            throw e
        } finally {
            conn.autoCommit = true
        }
    }

    override fun close() {
        conn.close()
    }
}
