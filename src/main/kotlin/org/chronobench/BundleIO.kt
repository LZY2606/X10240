package org.chronobench

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * 项目档案：原始来源(全版本+父链)、事件(有序)、派生结果、决定。
 * 导出/删除本地库/再导入后，稳定身份、版本顺序、来源关系必须保持。
 */
@Serializable
data class ProjectBundle(
    val bundleFormat: String = "chronobench-bundle-v1",
    val exportedAt: Long,
    val exportedBy: String,
    val project: Project,
    val sources: List<SourceVersion>,
    val derivations: List<StoredDerivation>,
    val events: List<EventRecord>,
    val decisions: List<StoredDecision>
)

@Serializable
data class StoredDerivation(val kind: String, val payload: JsonObject)

@Serializable
data class StoredDecision(
    val scope: String,
    val target: String,
    val decision: String,
    val reason: String? = null,
    val eventSeq: Long
)

class BundleIO(val db: Database) {
    private val json get() = db.json

    fun exportBundle(projectId: String, exportedBy: String, at: Long): ProjectBundle {
        val project = db.getProject(projectId) ?: throw ImportException(listOf("项目不存在: $projectId"))
        val sources = db.listSourceVersions(projectId).sortedWith(compareBy({ it.submittedAt }, { it.id }, { it.version }))
        val derivations = mutableListOf<StoredDerivation>()
        for (kind in listOf("CALIBRATION", "SEQUENCE")) {
            db.listDerivations(projectId, kind).forEach { p ->
                derivations += StoredDerivation(kind, json.parseToJsonElement(p) as JsonObject)
            }
        }
        val events = db.listEvents(projectId)
        val decisions = mutableListOf<StoredDecision>()
        connDecisionRows(projectId) { scope, target, decision, reason, seq ->
            decisions += StoredDecision(scope, target, decision, reason, seq)
        }
        return ProjectBundle(
            exportedAt = at, exportedBy = exportedBy, project = project,
            sources = sources, derivations = derivations, events = events, decisions = decisions
        )
    }

    /**
     * 导入：默认重建一个新项目（新 projectId），但保留所有来源逻辑 id、版本号、顺序、父链；
     * 派生结果与事件的 seq 严格按原序重放。identityMode=preserveProject 时连 projectId 也保留（需库内无冲突）。
     */
    fun importBundle(bundle: ProjectBundle, identityMode: String = "newProject", actor: String = "import"): Project {
        if (bundle.bundleFormat != "chronobench-bundle-v1")
            throw ImportException(listOf("不支持的档案格式: ${bundle.bundleFormat}"))
        if (bundle.sources.any { it.version < 1 }) throw ImportException(listOf("来源版本号非法"))

        val targetProjectId = if (identityMode == "preserveProject" && db.getProject(bundle.project.id) == null)
            bundle.project.id else "P-" + IdGen.token()
        val project = if (targetProjectId == bundle.project.id) bundle.project
        else bundle.project.copy(id = targetProjectId)
        db.createProject(project)

        // 1) 来源按 (id, version) 顺序原样写入（含 provenance 与原始表示）
        bundle.sources.sortedWith(compareBy({ it.submittedAt }, { it.id }, { it.version })).forEach { sv ->
            val remappedParent = sv.parentId
            db.insertSource(sv.copy(projectId = targetProjectId, parentId = remappedParent))
        }

        // 2) 派生结果原样写入（身份、指纹、直接来源不改写）
        bundle.derivations.forEach { d ->
            when (d.kind) {
                "CALIBRATION" -> {
                    val r = json.decodeFromJsonElement(CalibrationResult.serializer(), d.payload)
                    db.insertDerivation("CALIBRATION", r.copy(projectId = targetProjectId))
                }
                "SEQUENCE" -> {
                    val r = json.decodeFromJsonElement(SequenceResult.serializer(), d.payload)
                    db.insertSequenceDerivation(r.copy(projectId = targetProjectId))
                }
                else -> throw ImportException(listOf("未知派生类型: ${d.kind}"))
            }
        }

        // 3) 事件按原 seq 顺序重放：新库 AUTOINCREMENT 必须给出相同 seq，保证稳定顺序
        bundle.events.sortedBy { it.seq }.forEach { e ->
            val insertSeq = db.appendEvent(e.copy(seq = 0, projectId = targetProjectId))
            if (insertSeq != e.seq)
                throw ImportException(listOf("事件顺序无法保持: 期望 seq=${e.seq} 实际=$insertSeq"))
        }

        // 4) 决定原样恢复（scope/target/决定/事件 seq）
        bundle.decisions.forEach { d ->
            db.upsertDecision(targetProjectId, d.scope, d.target, d.decision, d.reason, d.eventSeq)
        }
        return project
    }

    private fun connDecisionRows(projectId: String, fn: (String, String, String, String?, Long) -> Unit) {
        db.conn.prepareStatement("SELECT scope,target,decision,reason,event_seq FROM decisions WHERE project_id=? ORDER BY event_seq")
            .use { ps ->
                ps.setString(1, projectId)
                ps.executeQuery().use { rs ->
                    while (rs.next()) fn(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getLong(5))
                }
            }
    }
}
