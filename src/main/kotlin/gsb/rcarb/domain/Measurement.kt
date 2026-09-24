package gsb.rcarb.domain

import kotlinx.serialization.Serializable

/**
 * 实验室原始测量。提交后不可覆盖；修订以新版本 + parentVersion 表达。
 *
 * 单位：age14c 与 sigma14c 均为 14C yr BP（实验室“距今”年龄，1950 锚点）。
 * rawText 保留导入时的原始表示；解析失败时 age14c=null，材料留在原位并附诊断，
 * 绝不因画图需要而丢弃。
 */
@Serializable
data class Measurement(
    val measurementId: String,
    val version: Int,
    val parentVersion: Int?,
    val labCode: String,
    val age14c: Double?,
    val sigma14c: Double?,
    val rawText: String,
    val material: String,
    val sourceRef: SourceRef,
    val diagnostics: List<String> = emptyList()
) {
    val valid: Boolean get() = age14c != null && sigma14c != null && sigma14c > 0.0
}

/** 测量与一条曲线版本、一个储库偏移版本的“校准输入组合”（审阅假设）。 */
@Serializable
data class CalibrationInput(
    val inputId: String,
    val measurementId: String,
    val measurementVersion: Int,
    val curveId: String,
    val curveVersion: String,
    val reservoirId: String?,
    val reservoirVersion: Int?,
    val createdBy: String
)
