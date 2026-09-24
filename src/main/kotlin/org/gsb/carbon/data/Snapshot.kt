package org.gsb.carbon.data

import kotlinx.serialization.Serializable
import java.io.File

/**
 * 导出档案：完整事件日志 + 全部投影表 + 元信息。
 * 导入到全新数据库后，身份（event_id / run_id / interval_id 等）、
 * 事件顺序与来源关系必须逐字保持；本格式不做任何重编号。
 */
@Serializable
data class Snapshot(
    val format: String = "carbon-gsb-snapshot",
    val formatVersion: Int = 1,
    val projectId: String,
    val projectName: String,
    val casePackId: String? = null,
    val events: List<MapRow> = emptyList(),
    val curves: List<MapRow> = emptyList(),
    val measurements: List<MapRow> = emptyList(),
    val offsets: List<MapRow> = emptyList(),
    val eraSystems: List<MapRow> = emptyList(),
    val sequenceEdges: List<MapRow> = emptyList(),
    val exclusions: List<MapRow> = emptyList(),
    val calibrationRuns: List<MapRow> = emptyList(),
    val sequenceEvaluations: List<MapRow> = emptyList(),
    val adjustmentCandidates: List<MapRow> = emptyList(),
    val decisions: List<MapRow> = emptyList(),
    val caseArchive: List<MapRow> = emptyList(),
)

@Serializable
data class MapRow(val columns: Map<String, String>)

object SnapshotIO {
    fun export(repo: Repository, conn: java.sql.Connection): Snapshot {
        val project = conn.createStatement().executeQuery(
            "SELECT project_id, name, COALESCE(case_pack_id, '') FROM projects LIMIT 1",
        ).use { rs ->
            rs.next()
            Triple(rs.getString(1), rs.getString(2), rs.getString(3).ifBlank { null })
        }
        return Snapshot(
            projectId = project.first,
            projectName = project.second,
            casePackId = project.third,
            events = rows(conn, "SELECT event_id, seq, type, clock, actor, summary, payload_json, sources_json FROM events ORDER BY seq"),
            curves = rows(conn, "SELECT version_id, curve_id, version, parent_version, payload_json, recorded_event_seq FROM curves ORDER BY recorded_event_seq, version_id"),
            measurements = rows(conn, "SELECT id_version, measurement_id, version, parent_version, payload_json, recorded_event_seq FROM measurements ORDER BY recorded_event_seq, id_version"),
            offsets = rows(conn, "SELECT offset_id, payload_json, recorded_event_seq FROM offsets ORDER BY recorded_event_seq, offset_id"),
            eraSystems = rows(conn, "SELECT era_system_id, payload_json, recorded_event_seq FROM era_systems ORDER BY recorded_event_seq"),
            sequenceEdges = rows(conn, "SELECT edge_id, payload_json, recorded_event_seq FROM sequence_edges ORDER BY recorded_event_seq"),
            exclusions = rows(conn, "SELECT exclusion_id, payload_json, recorded_event_seq FROM exclusions ORDER BY recorded_event_seq"),
            calibrationRuns = rows(conn, "SELECT run_id, payload_json, created_event_seq, current, stale_reason FROM calibration_runs ORDER BY created_event_seq, run_id"),
            sequenceEvaluations = rows(conn, "SELECT evaluation_id, payload_json, created_event_seq, current, stale_reason FROM sequence_evaluations ORDER BY created_event_seq, evaluation_id"),
            adjustmentCandidates = rows(conn, "SELECT candidate_id, evaluation_id, payload_json FROM adjustment_candidates ORDER BY candidate_id"),
            decisions = rows(conn, "SELECT decision_id, payload_json, created_event_seq FROM decisions ORDER BY created_event_seq, decision_id"),
            caseArchive = rows(conn, "SELECT archive_id, stage, payload_json, event_seq FROM case_archive ORDER BY event_seq"),
        )
    }

    private fun rows(conn: java.sql.Connection, sql: String): List<MapRow> {
        val out = mutableListOf<MapRow>()
        conn.createStatement().executeQuery(sql).use { rs ->
            val meta = rs.metaData
            while (rs.next()) {
                val map = linkedMapOf<String, String>()
                for (i in 1..meta.columnCount) {
                    val v = rs.getObject(i)
                    map[meta.getColumnName(i)] = if (v == null) "" else v.toString()
                }
                out += MapRow(map)
            }
        }
        return out
    }

    fun write(snapshot: Snapshot, file: File) {
        file.writeText(JsonCodec.pretty.encodeToString(Snapshot.serializer(), snapshot))
    }

    fun read(file: File): Snapshot {
        val text = file.readText()
        val snapshot = JsonCodec.json.decodeFromString(Snapshot.serializer(), text)
        require(snapshot.format == "carbon-gsb-snapshot") { "不是 carbon-gsb 快照文件" }
        require(snapshot.formatVersion == 1) { "不支持的快照版本: ${snapshot.formatVersion}" }
        return snapshot
    }

    /**
     * 导入到全新数据库：先关闭自增（seq 显式给定），按原顺序写事件，
     * 再写所有投影表。身份与来源关系不做任何重写。
     */
    fun importInto(snapshot: Snapshot, conn: java.sql.Connection) {
        conn.autoCommit = false
        try {
            conn.createStatement().use { it.execute("DELETE FROM events") }
            insertRows(conn, "events", listOf("event_id", "seq", "type", "clock", "actor", "summary", "payload_json", "sources_json"), snapshot.events)

            conn.createStatement().use { it.execute("DELETE FROM projects") }
            conn.prepareStatement(
                "INSERT INTO projects(project_id, name, case_pack_id, created_at_event_seq) VALUES (?, ?, ?, ?)",
            ).use { ps ->
                ps.setString(1, snapshot.projectId)
                ps.setString(2, snapshot.projectName)
                ps.setString(3, snapshot.casePackId)
                val firstSeq = snapshot.events.firstOrNull { it.columns["type"] == "PROJECT_CREATED" }?.columns?.get("seq")?.toInt() ?: 1
                ps.setInt(4, firstSeq)
                ps.executeUpdate()
            }

            insertRows(conn, "curves", listOf("version_id", "curve_id", "version", "parent_version", "payload_json", "recorded_event_seq"), snapshot.curves)
            insertRows(conn, "measurements", listOf("id_version", "measurement_id", "version", "parent_version", "payload_json", "recorded_event_seq"), snapshot.measurements)
            insertRows(conn, "offsets", listOf("offset_id", "payload_json", "recorded_event_seq"), snapshot.offsets)
            insertRows(conn, "era_systems", listOf("era_system_id", "payload_json", "recorded_event_seq"), snapshot.eraSystems)
            insertRows(conn, "sequence_edges", listOf("edge_id", "payload_json", "recorded_event_seq"), snapshot.sequenceEdges)
            insertRows(conn, "exclusions", listOf("exclusion_id", "payload_json", "recorded_event_seq"), snapshot.exclusions)
            importRuns(conn, snapshot.calibrationRuns)
            importEvaluations(conn, snapshot.sequenceEvaluations)
            insertRows(conn, "adjustment_candidates", listOf("candidate_id", "evaluation_id", "payload_json"), snapshot.adjustmentCandidates)
            insertRows(conn, "decisions", listOf("decision_id", "payload_json", "created_event_seq"), snapshot.decisions)
            insertRows(conn, "case_archive", listOf("archive_id", "stage", "payload_json", "event_seq"), snapshot.caseArchive)

            val maxSeq = snapshot.events.maxOfOrNull { it.columns["seq"]?.toInt() ?: 0 } ?: 0
            conn.createStatement().use {
                it.execute("DELETE FROM sqlite_sequence WHERE name = 'events'")
                it.execute("INSERT INTO sqlite_sequence(name, seq) VALUES ('events', $maxSeq)")
            }
            conn.commit()
        } catch (t: Throwable) {
            conn.rollback()
            throw t
        } finally {
            conn.autoCommit = true
        }
    }

    private fun importRuns(conn: java.sql.Connection, rows: List<MapRow>) {
        conn.prepareStatement(
            "INSERT OR REPLACE INTO calibration_runs(run_id, payload_json, created_event_seq, current, stale_reason) VALUES (?, ?, ?, ?, ?)",
        ).use { ps ->
            for (row in rows) {
                val c = row.columns
                ps.setString(1, c["run_id"])
                ps.setString(2, c["payload_json"])
                ps.setInt(3, c["created_event_seq"]?.toInt() ?: 0)
                ps.setInt(4, (c["current"] ?: "1").toInt())
                val stale = c["stale_reason"]
                if (stale.isNullOrBlank()) ps.setNull(5, java.sql.Types.VARCHAR) else ps.setString(5, stale)
                ps.executeUpdate()
            }
        }
    }

    private fun importEvaluations(conn: java.sql.Connection, rows: List<MapRow>) {
        conn.prepareStatement(
            "INSERT OR REPLACE INTO sequence_evaluations(evaluation_id, payload_json, created_event_seq, current, stale_reason) VALUES (?, ?, ?, ?, ?)",
        ).use { ps ->
            for (row in rows) {
                val c = row.columns
                ps.setString(1, c["evaluation_id"])
                ps.setString(2, c["payload_json"])
                ps.setInt(3, c["created_event_seq"]?.toInt() ?: 0)
                ps.setInt(4, (c["current"] ?: "1").toInt())
                val stale = c["stale_reason"]
                if (stale.isNullOrBlank()) ps.setNull(5, java.sql.Types.VARCHAR) else ps.setString(5, stale)
                ps.executeUpdate()
            }
        }
    }

    private fun insertRows(
        conn: java.sql.Connection,
        table: String,
        columns: List<String>,
        rows: List<MapRow>,
    ) {
        val placeholders = columns.joinToString(",") { "?" }
        conn.prepareStatement("INSERT OR REPLACE INTO $table(${columns.joinToString(",")}) VALUES ($placeholders)").use { ps ->
            for (row in rows) {
                columns.forEachIndexed { idx, name ->
                    val value = row.columns[name]
                    val intCols = setOf("seq", "clock", "recorded_event_seq", "created_event_seq", "event_seq", "version")
                    when {
                        value == null || value == "" -> {
                            if (name in intCols) ps.setInt(idx + 1, 0) else ps.setNull(idx + 1, java.sql.Types.VARCHAR)
                        }
                        name in intCols -> ps.setInt(idx + 1, value.toInt())
                        else -> ps.setString(idx + 1, value)
                    }
                }
                ps.executeUpdate()
            }
        }
    }
}
