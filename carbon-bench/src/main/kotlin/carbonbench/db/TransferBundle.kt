package carbonbench.db

import carbonbench.curves.BuiltInCurves
import carbonbench.domain.Event
import carbonbench.state.Codec
import kotlinx.serialization.Serializable

@Serializable
data class CurveManifestEntry(
    val curveId: String,
    val version: String,
    val fingerprint: String,
    val minCalBP: Double,
    val maxCalBP: Double,
    val spacing: Double,
    val pointCount: Int,
    val sourceRef: String
)

@Serializable
data class TransferBundle(
    val bundleFormat: String = "carbon-bench-transfer/1",
    val projectId: String,
    val exportedAt: String,
    val eventCount: Int,
    val lastSeq: Long,
    val curves: List<CurveManifestEntry>,
    val events: List<Event>
)

object Transfer {
    fun buildBundle(projectId: String, store: EventStore, exportedAt: String): TransferBundle {
        val events = store.listEvents(projectId)
        val usedVersions = events
            .mapNotNull { ev -> ev.payload["curveVersion"]?.let { (it as kotlinx.serialization.json.JsonPrimitive).content } }
            .toSortedSet()
        val curves = usedVersions.map { v ->
            val c = BuiltInCurves.get(v)
            CurveManifestEntry(c.curveId, c.version, c.fingerprint, c.minCalBP, c.maxCalBP, c.spacing, c.points.size, c.sourceRef)
        }
        return TransferBundle(
            projectId = projectId,
            exportedAt = exportedAt,
            eventCount = events.size,
            lastSeq = events.lastOrNull()?.seq ?: 0L,
            curves = curves,
            events = events
        )
    }

    fun encode(bundle: TransferBundle): String = Codec.json.encodeToString(TransferBundle.serializer(), bundle)

    fun decode(text: String): TransferBundle = Codec.json.decodeFromString(TransferBundle.serializer(), text)

    sealed class ImportResult {
        data class Imported(val projectId: String, val eventCount: Int) : ImportResult()
        data class Rejected(val errors: List<String>) : ImportResult()
    }

    fun importBundle(bundle: TransferBundle, store: EventStore): ImportResult {
        val errors = mutableListOf<String>()
        if (bundle.bundleFormat != "carbon-bench-transfer/1")
            errors += "不支持的束格式：${bundle.bundleFormat}"
        if (bundle.events.isEmpty()) errors += "束中没有事件"
        bundle.events.forEachIndexed { idx, e ->
            if (e.projectId != bundle.projectId) errors += "事件 $idx 项目身份不一致：${e.id}"
            if (e.seq != idx.toLong() + 1L) errors += "事件顺序不连续：${e.id} seq=${e.seq} 期望 ${idx + 1}"
        }
        bundle.curves.forEach { m ->
            if (!BuiltInCurves.exists(m.version)) {
                errors += "缺少曲线版本：${m.curveId}@${m.version}"
            } else {
                val c = BuiltInCurves.get(m.version)
                if (c.fingerprint != m.fingerprint)
                    errors += "曲线指纹不匹配：${m.curveId}@${m.version}"
            }
        }
        if (errors.isNotEmpty()) return ImportResult.Rejected(errors)
        if (store.projectExists(bundle.projectId))
            errors += "目标库已存在项目 ${bundle.projectId}（删除后再导入以验证身份保持）"
        if (errors.isNotEmpty()) return ImportResult.Rejected(errors)
        bundle.events.forEach { store.appendEvent(it) }
        return ImportResult.Imported(bundle.projectId, bundle.events.size)
    }
}
