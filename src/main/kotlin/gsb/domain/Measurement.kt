package gsb.domain

import kotlinx.serialization.Serializable

/** 实验室原始 14C 测量：不可变原始输入；修订产生新版本。 */
@Serializable
data class RadiocarbonMeasurement(
    val id: String,
    val parentId: String? = null,
    val version: Int = 1,
    val age: Quantity,
    val sigma: Quantity,
    val labCode: String,
    val material: String,
    val source: SourceRef,
    val note: String = ""
) {
    val usable: Boolean get() = age.normalized && sigma.normalized && age.unit == UNIT && sigma.unit == UNIT
    val diagnosis: String?
        get() = when {
            !age.normalized -> "年龄无法规范化：${age.diagnostic}"
            !sigma.normalized -> "标准差无法规范化：${sigma.diagnostic}"
            age.unit != UNIT -> "年龄单位为 ${age.unit}，期望 $UNIT"
            sigma.unit != UNIT -> "标准差单位为 ${sigma.unit}，期望 $UNIT"
            else -> null
        }
    companion object { const val UNIT = "14C yr BP" }
}
