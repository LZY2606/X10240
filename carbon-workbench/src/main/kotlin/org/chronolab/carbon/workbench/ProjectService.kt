package org.chronolab.carbon.workbench

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import org.chronolab.carbon.model.Branch
import org.chronolab.carbon.model.DecisionVerb
import org.chronolab.carbon.model.SourceKind
import org.chronolab.carbon.store.EventStore
import org.chronolab.carbon.store.ProjectState
import org.chronolab.carbon.store.VirtualClock
import java.nio.file.Path

/**
 * 面向 HTTP/测试的用例服务。一次导入保留原测量、曲线版本、偏移分布、年代系统、层序约束与排除决定各自来源。
 */
class ProjectService(
    val wb: Workbench,
    val projectId: String = "demo-project"
) {
    fun importBundle(bundleText: String, branchId: String = "main"): List<String> {
        val samples = Importer.parseBundle(bundleText)
        val nodeIds = samples.map { ps ->
            wb.commitSource(ps.logicalId, ps.kind, ps.eraSystem, ps.origin, ps.fields, ps.raw, ps.diagnostics, branchId).nodeId
        }
        return nodeIds
    }

    /** 对所有测量按“当前曲线 + 各自偏移”生成校准，再评估全部层序来源。 */
    fun computeAll(branchId: String = "main") {
        val state = wb.state(branchId)
        state.measurements.forEach { m ->
            val reservoirId = m.fields["reservoirModel"]?.let { el ->
                el.jsonPrimitive.content.let { state.source(it)?.logicalId }
            }
            wb.calibrate(m.logicalId, reservoirLogicalId = reservoirId, branchId = branchId)
        }
        state.currentSources().filter { it.kind == SourceKind.SEQUENCE_LINKS }.forEach { s ->
            wb.assessSequence(s.logicalId, branchId)
        }
    }

    /** 导入一个新版本来源（源数据局部改动/参数修订），并只让依赖项过期。 */
    fun reviseSource(
        logicalId: String,
        bundleItemJson: String,
        branchId: String = "main"
    ): Pair<String, List<String>> {
        val wrapped = """{"samples":[$bundleItemJson]}"""
        val parsed = Importer.parseBundle(wrapped).single()
        val old = wb.state(branchId).source(logicalId)?.nodeId
        val node = wb.commitSource(parsed.logicalId, parsed.kind, parsed.eraSystem, parsed.origin,
            parsed.fields, parsed.raw, parsed.diagnostics, branchId)
        val stale = wb.markStaleDerivations(node.nodeId, branchId)
        return node.nodeId to stale
    }

    fun recomputeStale(branchId: String = "main") {
        val state = wb.state(branchId)
        state.measurements.forEach { m ->
            val cur = state.currentCalibration(m.logicalId)
            if (cur == null || cur.status != "CURRENT") {
                val reservoirId = m.fields["reservoirModel"]?.jsonPrimitive?.content
                wb.calibrate(m.logicalId, reservoirLogicalId = reservoirId, branchId = branchId)
            }
        }
        // 重新评估层序
        state.currentSources().filter { it.kind == SourceKind.SEQUENCE_LINKS }.forEach { s ->
            val cur = state.currentAssessment(s.logicalId)
            if (cur == null || cur.status != "CURRENT") wb.assessSequence(s.logicalId, branchId)
        }
    }

    fun exportBundle(): String = ExportImport.exportBundle(projectId, wb)
}
