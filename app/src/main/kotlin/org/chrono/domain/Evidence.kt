package org.chrono.domain

import kotlinx.serialization.Serializable

/** 提交即不可变的原始测量；后续修订以新版本 + 父版本关系表达。 */
@Serializable
data class Measurement(
    val measurementId: String,
    val version: Int,
    val parentVersion: Int? = null,
    val labCode: String,
    val material: String,
    val age: Quantity,                 // 14C age，原始表示留在 raw
    val sigma: Quantity,
    val curveId: String,
    val curveVersion: String,
    val reservoir: ReservoirModel? = null,
    val sourceRef: String,
    val diagnostics: List<String> = emptyList()
) {
    val valid: Boolean get() = age.convertible && sigma.convertible && diagnostics.none { it.startsWith("ERROR") }
}

/** 层序（地层/年代先后）约束：A 应早于 B。 */
@Serializable
data class SequenceEdge(
    val edgeId: String,
    val olderId: String,
    val youngerId: String,
    val sourceRef: String
)

/** 人工排除决定（如老碳效应/污染），同样不可变、有来源、可撤销（新事件）。 */
@Serializable
data class ExclusionDecision(
    val decisionId: String,
    val measurementId: String,
    val exclude: Boolean,
    val reason: String,
    val decidedAt: String,
    val sourceRef: String
)
