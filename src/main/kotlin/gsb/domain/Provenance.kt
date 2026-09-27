package gsb.domain

import kotlinx.serialization.Serializable

/** 稳定来源引用：每条原始输入片段都能追到材料、出处与原文表示。 */
@Serializable
data class SourceRef(
    val materialId: String = "",
    val citation: String = "",
    val page: String = "",
    val rawText: String = ""
) {
    companion object {
        val UNKNOWN = SourceRef("unknown", "未登记来源", rawText = "")
    }
}

/** 所有领域量都携带单位或坐标基准。 */
@Serializable
data class Quantity(
    val value: Double,
    val unit: String,
    val rawText: String = "",
    /** 无法转换为规范单位时保留原值，诊断留在原位置。 */
    val normalized: Boolean = true,
    val diagnostic: String? = null
) {
    companion object {
        fun conv(value: Double, unit: String, rawText: String = value.toString()) =
            Quantity(value, unit, rawText, true, null)
        fun unconverted(rawText: String, wantedUnit: String, why: String) =
            Quantity(Double.NaN, wantedUnit, rawText, false, why)
    }
}
