package gsb.persist

import gsb.domain.Event
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** 导出/导入包：保持事件身份、顺序与来源关系。 */
@Serializable
data class ProjectPackage(
    val format: String = "gsb-radiocarbon-workbench/v1",
    val projectId: String,
    val exportedAt: String,
    val events: List<Event>,
    val manifest: PackageManifest
)

@Serializable
data class PackageManifest(
    val eventCount: Int,
    val sourceRefs: List<String>,
    val curveVersions: List<String>,
    val identityOrder: List<String> // event ids，按 seq 排序
)
