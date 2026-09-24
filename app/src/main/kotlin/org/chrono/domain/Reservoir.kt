package org.chrono.domain

import kotlinx.serialization.Serializable
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * 储库偏移 ΔR 分布：一等数据。
 *  - Gaussian：均值 + 标准差（14C yr）
 *  - Empirical：实验室/区域对照表给出的离散加权样本（海洋曲线组合等）
 * 离散化在固定网格上确定性完成，不使用随机采样，保证可重现。
 */
@Serializable
sealed class ReservoirModel {
    abstract val sourceRef: String
    abstract val support(): ClosedFloatingPointRange<Double>
    abstract fun density(delta: Double): Double

    @Serializable
    data class Gaussian(val mean: Double, val sd: Double, override val sourceRef: String) : ReservoirModel() {
        init { require(sd >= 0.0) { "reservoir sd must be non-negative" } }
        override fun support() = mean - 6.0 * sd..mean + 6.0 * sd
        override fun density(delta: Double): Double {
            if (sd == 0.0) return if (kotlin.math.abs(delta - mean) < 1e-12) 1e12 else 0.0
            val z = (delta - mean) / sd
            return exp(-0.5 * z * z) / (sqrt(2.0 * PI) * sd)
        }
    }

    @Serializable
    data class Empirical(val samples: List<OffsetSample>, override val sourceRef: String) : ReservoirModel() {
        @Serializable
        data class OffsetSample(val delta: Double, val sd: Double, val weight: Double = 1.0)
        init { require(samples.isNotEmpty()) { "empirical reservoir needs samples" } }
        private val ws = samples.sumOf { it.weight }
        override fun support(): ClosedFloatingPointRange<Double> {
            val lo = samples.minOf { it.delta - 4.0 * it.sd }
            val hi = samples.maxOf { it.delta + 4.0 * it.sd }
            return lo..hi
        }
        override fun density(delta: Double): Double =
            samples.sumOf { s ->
                val z = (delta - s.delta) / if (s.sd == 0.0) 1.0 else s.sd
                val g = if (s.sd == 0.0) (if (kotlin.math.abs(delta - s.delta) < 1e-12) 1e9 else 0.0)
                else exp(-0.5 * z * z) / (sqrt(2.0 * PI) * s.sd)
                s.weight / ws * g
            }
    }
}

/** 将任意 ΔR 分布确定性离散为 (delta, weight>0) 网格，权重随后归一化。 */
fun ReservoirModel.discretize(step: Double = 5.0): List<Pair<Double, Double>> {
    val (lo, hi) = support().start to support().endInclusive
    val n = Math.round((hi - lo) / step).toInt().coerceAtLeast(1)
    val out = ArrayList<Pair<Double, Double>>(n + 1)
    var i = 0
    while (i <= n) {
        val d = lo + i * step
        val w = density(d)
        if (w > 0.0) out += d to w
        i++
    }
    if (out.isEmpty()) out += (lo + hi) / 2.0 to 1.0
    return out
}
