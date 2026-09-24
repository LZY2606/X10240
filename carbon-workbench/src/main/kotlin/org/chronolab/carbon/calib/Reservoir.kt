package org.chronolab.carbon.calib

import kotlinx.serialization.Serializable

/**
 * 储库偏移分布：以若干高斯成分的混合表示，不压成单一均值。
 * deltaR 为现代碳尺度偏移（年），正值表示“看起来更老”。
 */
@Serializable
data class ReservoirComponent(val meanYears: Double, val sigmaYears: Double, val weight: Double)

@Serializable
data class ReservoirModel(
    val reservoirId: String,
    val label: String,
    val marineReservoirYears: Double,
    val components: List<ReservoirComponent>
) {
    init {
        require(components.isNotEmpty()) { "reservoir mixture needs >= 1 component" }
        require(components.sumOf { it.weight } > 0.999) { "reservoir weights must sum to 1" }
    }
    companion object {
        val NONE = ReservoirModel("reservoir-none", "无储库校正", 0.0, listOf(ReservoirComponent(0.0, 0.0, 1.0)))
    }
}
