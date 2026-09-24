package org.gsb.carbon.data

import kotlinx.serialization.Serializable

/**
 * 离线案例包「碳年候选席：校准曲线、储库偏移与多峰年代证据」。
 * 领域专用格式（不是通用表格），每类材料一个来源文件。
 */
@Serializable
data class CasePack(
    val packId: String,
    val title: String,
    val description: String,
    val eraSystems: List<RawEraSystem> = emptyList(),
    val curves: List<RawCurveRef> = emptyList(),
    val measurements: List<RawMeasurement> = emptyList(),
    val offsets: List<RawOffset> = emptyList(),
    val sequence: List<RawEdge> = emptyList(),
    val exclusions: List<RawExclusion> = emptyList(),
)

@Serializable
data class RawEraSystem(val id: String, val name: String, val datum: String, val source: String)

@Serializable
data class RawCurveRef(
    val id: String,
    val version: String,
    val parentVersion: String? = null,
    val file: String,
    val source: String,
    val notes: String = "",
)

@Serializable
data class RawMeasurement(
    val id: String,
    val labCode: String,
    val sampleLabel: String,
    val age: RawMeasure,
    val sigma: RawMeasure,
    val material: String,
    val source: String,
)

@Serializable
data class RawMeasure(val raw: String, val unit: String)

@Serializable
data class RawOffset(
    val id: String,
    val label: String,
    val source: String,
    val components: List<RawOffsetComponent>,
)

@Serializable
data class RawOffsetComponent(val deltaR: Double, val sigma: Double, val weight: Double, val basis: String)

@Serializable
data class RawEdge(val id: String, val older: String, val younger: String, val source: String)

@Serializable
data class RawExclusion(val id: String, val measurement: String, val loBp: Double, val hiBp: Double, val reason: String, val source: String)

/** 导入诊断：无法转换的值留在原位置（converted=false），不丢弃、不阻塞其它材料导入。 */
@Serializable
data class ImportDiagnostic(
    val severity: Severity,
    val file: String,
    val objectId: String?,
    val message: String,
    val rawSnippet: String,
) {
    @Serializable
    enum class Severity { REJECTED, UNCONVERTED, WARN }
}

data class ParsedCurveFile(
    val ref: RawCurveRef,
    val pointsText: List<Triple<Double, Double, Double>>,
    val diagnostics: List<ImportDiagnostic>,
)
