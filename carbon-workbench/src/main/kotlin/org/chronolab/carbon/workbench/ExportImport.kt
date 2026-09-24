package org.chronolab.carbon.workbench

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.chronolab.carbon.model.Branch
import org.chronolab.carbon.model.ProjectEvent
import org.chronolab.carbon.store.EventStore
import org.chronolab.carbon.store.Projection
import org.chronolab.carbon.store.VirtualClock
import java.nio.file.Path

@Serializable
data class ExportEnvelope(
    val format: String,
    val formatVersion: String,
    val projectId: String,
    val exportedAtVirtualTime: String,
    val branches: List<Branch>,
    val events: List<ProjectEvent>
)

/** 导出 = 事件 + 分支（顺序即 seq）。删除本地数据库后导入，身份/顺序/来源完全保持。 */
object ExportImport {
    private val json = Json { prettyPrint = true; encodeDefaults = true; ignoreUnknownKeys = true }

    fun exportBundle(projectId: String, wb: Workbench): String {
        val env = ExportEnvelope(
            format = "carbon-workbench-bundle",
            formatVersion = "1.0.0",
            projectId = projectId,
            exportedAtVirtualTime = wb.clock.nowIso(),
            branches = wb.store.listBranches(),
            events = wb.store.allEvents()
        )
        return json.encodeToString(ExportEnvelope.serializer(), env)
    }

    /**
     * 导入到一个全新 EventStore。为保持事件 seq，直接按导出顺序以保留 ID 方式插入；
     * 分支 head 以导出值为准。
     */
    fun importBundle(text: String, target: EventStore, clock: VirtualClock) {
        val env = json.decodeFromString(ExportEnvelope.serializer(), text)
        require(env.format == "carbon-workbench-bundle") { "格式不匹配: ${env.format}" }
        val conn = target.conn
        // 清空（用于“删除本地数据库再导入”演练）
        conn.createStatement().use { it.executeUpdate("DELETE FROM events"); it.executeUpdate("DELETE FROM branches") }
        conn.commit()
        env.branches.forEach { b -> target.upsertBranch(b) }
        env.events.sortedBy { it.seq }.forEach { e ->
            conn.prepareStatement(
                "INSERT INTO events(seq,event_id,branch_id,virtual_time,type,payload_json,basis_json) VALUES(?,?,?,?,?,?,?)"
            ).use { ps ->
                ps.setLong(1, e.seq); ps.setString(2, e.eventId); ps.setString(3, e.branchId)
                ps.setString(4, e.virtualTime); ps.setString(5, e.type); ps.setString(6, e.payloadJson)
                if (e.basisJson == null) ps.setNull(7, java.sql.Types.VARCHAR) else ps.setString(7, e.basisJson)
                ps.executeUpdate()
            }
        }
        conn.commit()
        clock.set(java.time.Instant.parse(env.exportedAtVirtualTime))
    }
}
