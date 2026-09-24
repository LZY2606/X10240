package gsb.rcarb.domain

import kotlinx.serialization.Serializable

/**
 * 层序关系（地层学/年代学先验），固定版本。
 * direction=OlderThan 表示 [olderId] 的日历年代早于（calBP 更大）[youngerId]。
 */
@Serializable
data class SequenceConstraint(
    val constraintId: String,
    val version: Int,
    val olderId: String,
    val youngerId: String,
    val relation: Relation,
    val sourceRef: SourceRef,
    val note: String = ""
) {
    enum class Relation { OlderThan, Contemporary }
}
