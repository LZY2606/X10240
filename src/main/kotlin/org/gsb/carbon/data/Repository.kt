package org.gsb.carbon.data

import org.gsb.carbon.GsbClock
import org.gsb.carbon.model.AdjustmentCandidate
import org.gsb.carbon.model.CalibrationCurve
import org.gsb.carbon.model.CalibrationRun
import org.gsb.carbon.model.Decision
import org.gsb.carbon.model.EraSystem
import org.gsb.carbon.model.Event
import org.gsb.carbon.model.EventPayload
import org.gsb.carbon.model.EventType
import org.gsb.carbon.model.Exclusion
import org.gsb.carbon.model.Measurement
import org.gsb.carbon.model.OffsetDistribution
import org.gsb.carbon.model.SequenceEdge
import org.gsb.carbon.model.SequenceEvaluation
import java.sql.Connection

/**
 * SQLite 仓库。事件日志是唯一的事实来源（append-only）；
 * 实体表是可由事件重放重建的投影，保存派生结果与过期标记。
 */
class Repository(
    private val conn: Connection,
    private val clock: GsbClock,
    private val actor: String = "reviewer",
) {
    private var projectId: String? = null

    fun projectId(): String = projectId ?: loadProjectId()

    private fun loadProjectId(): String {
        conn.prepareStatement("SELECT project_id FROM projects LIMIT 1").executeQuery().use { rs ->
            if (rs.next()) {
                projectId = rs.getString(1)
                return projectId!!
            }
        }
        error("数据库中不存在项目")
    }

    fun isInitialized(): Boolean {
        conn.prepareStatement("SELECT COUNT(*) FROM projects").executeQuery().use { return it.getInt(1) > 0 }
    }

    @Synchronized
    fun appendEvent(type: EventType, summary: String, payload: EventPayload, sources: List<String> = emptyList()): Event {
        val seq = nextSeq()
        val pid = projectId ?: "unassigned"
        val event = Event(
            eventId = "$pid-e${"%04d".format(seq)}",
            seq = seq,
            type = type,
            clock = clock.nowMillis(),
            actor = actor,
            summary = summary,
            payload = payload,
            sources = sources,
        )
        conn.prepareStatement(
            """
            INSERT INTO events(event_id, type, clock, actor, summary, payload_json, sources_json)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """,
        ).use { ps ->
            ps.setString(1, event.eventId)
            ps.setString(2, event.type.name)
            ps.setLong(3, event.clock)
            ps.setString(4, event.actor)
            ps.setString(5, event.summary)
            ps.setString(6, JsonCodec.encodePayload(payload))
            ps.setString(7, JsonCodec.encode(sources))
            ps.executeUpdate()
        }
        return event
    }

    private fun nextSeq(): Int {
        conn.prepareStatement("SELECT COALESCE(MAX(seq), 0) + 1 FROM events").executeQuery().use { return it.getInt(1) }
    }

    fun createProject(projectId: String, name: String, casePackId: String?): Event {
        check(!isInitialized()) { "项目已存在，原始项目记录不可覆盖" }
        this.projectId = projectId
        val event = appendEvent(
            EventType.PROJECT_CREATED,
            "创建项目 $name",
            org.gsb.carbon.model.ProjectCreated(projectId, name, casePackId),
        )
        conn.prepareStatement(
            "INSERT INTO projects(project_id, name, case_pack_id, created_at_event_seq) VALUES (?, ?, ?, ?)",
        ).use { ps ->
            ps.setString(1, projectId)
            ps.setString(2, name)
            ps.setString(3, casePackId)
            ps.setInt(4, event.seq)
            ps.executeUpdate()
        }
        return event
    }

    fun saveCurve(curve: CalibrationCurve, eventSeq: Int) {
        conn.prepareStatement(
            """
            INSERT OR REPLACE INTO curves(version_id, curve_id, version, parent_version, payload_json, recorded_event_seq)
            VALUES (?, ?, ?, ?, ?, ?)
            """,
        ).use { ps ->
            ps.setString(1, curve.versionId)
            ps.setString(2, curve.curveId)
            ps.setString(3, curve.version)
            ps.setString(4, curve.parentVersion)
            ps.setString(5, JsonCodec.encode(curve))
            ps.setInt(6, eventSeq)
            ps.executeUpdate()
        }
    }

    fun saveMeasurement(m: Measurement, eventSeq: Int) {
        conn.prepareStatement(
            """
            INSERT OR REPLACE INTO measurements(id_version, measurement_id, version, parent_version, payload_json, recorded_event_seq)
            VALUES (?, ?, ?, ?, ?, ?)
            """,
        ).use { ps ->
            ps.setString(1, m.idVersion)
            ps.setString(2, m.measurementId)
            ps.setInt(3, m.version)
            ps.setObject(4, m.parentVersion)
            ps.setString(5, JsonCodec.encode(m))
            ps.setInt(6, eventSeq)
            ps.executeUpdate()
        }
    }

    fun saveOffset(offset: OffsetDistribution, eventSeq: Int) {
        conn.prepareStatement("INSERT OR REPLACE INTO offsets(offset_id, payload_json, recorded_event_seq) VALUES (?, ?, ?)").use {
            it.setString(1, offset.offsetId)
            it.setString(2, JsonCodec.encode(offset))
            it.setInt(3, eventSeq)
            it.executeUpdate()
        }
    }

    fun saveEra(era: EraSystem, eventSeq: Int) {
        conn.prepareStatement("INSERT OR REPLACE INTO era_systems(era_system_id, payload_json, recorded_event_seq) VALUES (?, ?, ?)").use {
            it.setString(1, era.eraSystemId)
            it.setString(2, JsonCodec.encode(era))
            it.setInt(3, eventSeq)
            it.executeUpdate()
        }
    }

    fun saveEdge(edge: SequenceEdge, eventSeq: Int) {
        conn.prepareStatement("INSERT OR REPLACE INTO sequence_edges(edge_id, payload_json, recorded_event_seq) VALUES (?, ?, ?)").use {
            it.setString(1, edge.edgeId)
            it.setString(2, JsonCodec.encode(edge))
            it.setInt(3, eventSeq)
            it.executeUpdate()
        }
    }

    fun saveExclusion(exclusion: Exclusion, eventSeq: Int) {
        conn.prepareStatement("INSERT OR REPLACE INTO exclusions(exclusion_id, payload_json, recorded_event_seq) VALUES (?, ?, ?)").use {
            it.setString(1, exclusion.exclusionId)
            it.setString(2, JsonCodec.encode(exclusion))
            it.setInt(3, eventSeq)
            it.executeUpdate()
        }
    }

    fun saveRun(run: CalibrationRun) {
        conn.prepareStatement(
            """
            INSERT OR REPLACE INTO calibration_runs(run_id, payload_json, created_event_seq, current, stale_reason)
            VALUES (?, ?, ?, 1, NULL)
            """,
        ).use {
            it.setString(1, run.runId)
            it.setString(2, JsonCodec.encode(run))
            it.setInt(3, run.createdAtEventSeq)
            it.executeUpdate()
        }
    }

    fun saveEvaluation(evaluation: SequenceEvaluation, candidates: List<AdjustmentCandidate>) {
        conn.prepareStatement(
            """
            INSERT OR REPLACE INTO sequence_evaluations(evaluation_id, payload_json, created_event_seq, current, stale_reason)
            VALUES (?, ?, ?, 1, NULL)
            """,
        ).use {
            it.setString(1, evaluation.evaluationId)
            it.setString(2, JsonCodec.encode(evaluation))
            it.setInt(3, evaluation.createdAtEventSeq)
            it.executeUpdate()
        }
        for (candidate in candidates) saveCandidate(candidate)
    }

    fun saveCandidate(candidate: AdjustmentCandidate) {
        conn.prepareStatement(
            "INSERT OR REPLACE INTO adjustment_candidates(candidate_id, evaluation_id, payload_json) VALUES (?, ?, ?)",
        ).use {
            it.setString(1, candidate.candidateId)
            it.setString(2, candidate.evaluationId)
            it.setString(3, JsonCodec.encode(candidate))
            it.executeUpdate()
        }
    }

    fun saveDecision(decision: Decision) {
        conn.prepareStatement(
            "INSERT OR REPLACE INTO decisions(decision_id, payload_json, created_event_seq) VALUES (?, ?, ?)",
        ).use {
            it.setString(1, decision.decisionId)
            it.setString(2, JsonCodec.encode(decision))
            it.setInt(3, decision.createdAtEventSeq)
            it.executeUpdate()
        }
    }

    fun markRunsStale(runIds: Collection<String>, reason: String) {
        for (id in runIds) {
            conn.prepareStatement("UPDATE calibration_runs SET current = 0, stale_reason = ? WHERE run_id = ?").use {
                it.setString(1, reason)
                it.setString(2, id)
                it.executeUpdate()
            }
        }
    }

    fun markEvaluationsStale(ids: Collection<String>, reason: String) {
        for (id in ids) {
            conn.prepareStatement("UPDATE sequence_evaluations SET current = 0, stale_reason = ? WHERE evaluation_id = ?").use {
                it.setString(1, reason)
                it.setString(2, id)
                it.executeUpdate()
            }
        }
    }

    fun events(): List<Event> {
        val out = mutableListOf<Event>()
        conn.prepareStatement("SELECT event_id, seq, type, clock, actor, summary, payload_json, sources_json FROM events ORDER BY seq").executeQuery().use { rs ->
            while (rs.next()) {
                out += Event(
                    eventId = rs.getString(1),
                    seq = rs.getInt(2),
                    type = EventType.valueOf(rs.getString(3)),
                    clock = rs.getLong(4),
                    actor = rs.getString(5),
                    summary = rs.getString(6),
                    payload = JsonCodec.decodePayload(rs.getString(7)),
                    sources = JsonCodec.decode(rs.getString(8)),
                )
            }
        }
        return out
    }

    fun curves(): List<CalibrationCurve> = loadAll("SELECT payload_json FROM curves ORDER BY recorded_event_seq, version_id")

    fun measurements(): List<Measurement> = loadAll("SELECT payload_json FROM measurements ORDER BY recorded_event_seq, id_version")

    fun offsets(): List<OffsetDistribution> = loadAll("SELECT payload_json FROM offsets ORDER BY recorded_event_seq, offset_id")

    fun eras(): List<EraSystem> = loadAll("SELECT payload_json FROM era_systems ORDER BY recorded_event_seq")

    fun edges(): List<SequenceEdge> = loadAll("SELECT payload_json FROM sequence_edges ORDER BY recorded_event_seq")

    fun exclusions(): List<Exclusion> = loadAll("SELECT payload_json FROM exclusions ORDER BY recorded_event_seq")

    fun runs(onlyCurrent: Boolean = false): List<RunRow> {
        val sql = buildString {
            append("SELECT payload_json, current, COALESCE(stale_reason, '') FROM calibration_runs")
            if (onlyCurrent) append(" WHERE current = 1")
            append(" ORDER BY created_event_seq")
        }
        val rows = mutableListOf<RunRow>()
        conn.prepareStatement(sql).executeQuery().use { rs ->
            while (rs.next()) {
                rows += RunRow(JsonCodec.decode(rs.getString(1)), rs.getInt(2) == 1, rs.getString(3))
            }
        }
        return rows
    }

    data class RunRow(val run: CalibrationRun, val current: Boolean, val staleReason: String)

    fun evaluations(onlyCurrent: Boolean = false): List<EvaluationRow> {
        val sql = buildString {
            append("SELECT payload_json, current, COALESCE(stale_reason, '') FROM sequence_evaluations")
            if (onlyCurrent) append(" WHERE current = 1")
            append(" ORDER BY created_event_seq")
        }
        val rows = mutableListOf<EvaluationRow>()
        conn.prepareStatement(sql).executeQuery().use { rs ->
            while (rs.next()) {
                rows += EvaluationRow(JsonCodec.decode(rs.getString(1)), rs.getInt(2) == 1, rs.getString(3))
            }
        }
        return rows
    }

    data class EvaluationRow(val evaluation: SequenceEvaluation, val current: Boolean, val staleReason: String)

    fun candidates(evaluationId: String? = null): List<AdjustmentCandidate> {
        val sql = if (evaluationId == null) {
            "SELECT payload_json FROM adjustment_candidates ORDER BY candidate_id"
        } else {
            "SELECT payload_json FROM adjustment_candidates WHERE evaluation_id = ? ORDER BY candidate_id"
        }
        val out = mutableListOf<AdjustmentCandidate>()
        conn.prepareStatement(sql).use { ps ->
            if (evaluationId != null) ps.setString(1, evaluationId)
            ps.executeQuery().use { rs ->
                while (rs.next()) out += JsonCodec.decode(rs.getString(1))
            }
        }
        return out
    }

    fun decisions(): List<Decision> = loadAll("SELECT payload_json FROM decisions ORDER BY created_event_seq, decision_id")

    fun archiveStages(archiveId: String): List<String> {
        conn.prepareStatement("SELECT payload_json FROM case_archive WHERE archive_id = ? ORDER BY event_seq").use {
            it.setString(1, archiveId)
            it.executeQuery().use { rs ->
                val out = mutableListOf<String>()
                while (rs.next()) out += rs.getString(1)
                return out
            }
        }
    }

    fun saveArchiveStage(archiveId: String, stage: String, payloadJson: String, eventSeq: Int) {
        conn.prepareStatement(
            "INSERT OR REPLACE INTO case_archive(archive_id, stage, payload_json, event_seq) VALUES (?, ?, ?, ?)",
        ).use {
            it.setString(1, archiveId)
            it.setString(2, stage)
            it.setString(3, payloadJson)
            it.setInt(4, eventSeq)
            it.executeUpdate()
        }
    }

    private inline fun <reified T> loadAll(sql: String): List<T> {
        val out = mutableListOf<T>()
        conn.prepareStatement(sql).executeQuery().use { rs ->
            while (rs.next()) out += JsonCodec.decode(rs.getString(1))
        }
        return out
    }
}
