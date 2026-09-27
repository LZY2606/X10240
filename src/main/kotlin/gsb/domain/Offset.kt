package gsb.domain

import kotlinx.serialization.Serializable
import kotlin.math.exp

/**
 * 储库偏移分布（14C 年单位：观测 14C 年龄 = 大气等效 + R）。
 * 支持点估计/高斯/离散混合；混合用于无法唯一确定偏移的情形。
 */
@Serializable
sealed class OffsetDistribution {
    abstract val id: String
    abstract val source: SourceRef

    @Serializable
    data class Delta(
        override val id: String, val mean: Double, val sigma: Double,
        override val source: SourceRef
    ) : OffsetDistribution()

    /** 离散混合：每个分量一对 (mean, sigma, weight)；weights 会被归一化。 */
    @Serializable
    data class Mixture(
        override val id: String, val components: List<Component>,
        override val source: SourceRef
    ) : OffsetDistribution() {
        @Serializable
        data class Component(val mean: Double, val sigma: Double, val weight: Double)
        val normWeights: DoubleArray by lazy {
            val s = components.sumOf { it.weight }
            DoubleArray(components.size) { components[it].weight / s }
        }
    }
}

fun OffsetDistribution.evaluate(r: Double): Double = when (this) {
    is OffsetDistribution.Delta -> {
        if (sigma <= 0.0) if (r == mean) 1.0 else 0.0
        else kotlin.math.exp(-0.5 * ((r - mean) / sigma).let { it * it }) /
            (sigma * kotlin.math.sqrt(2.0 * Math.PI))
    }
    is OffsetDistribution.Mixture -> {
        var acc = 0.0
        normWeights.forEachIndexed { i, w ->
            val c = components[i]
            if (c.sigma > 0.0) {
                val z = (r - c.mean) / c.sigma
                acc += w * exp(-0.5 * z * z) / (c.sigma * kotlin.math.sqrt(2.0 * Math.PI))
            } else if (r == c.mean) acc += w
        }
        acc
    }
}

/** 数值积分网格上的偏移分布样本：离散化后与似然做卷积。 */
fun OffsetDistribution.grid(minR: Double, maxR: Double, step: Double): OffsetGrid {
    val n = ((maxR - minR) / step).toInt() + 1
    val rs = DoubleArray(n) { minR + it * step }
    val ps = DoubleArray(n) { evaluate(rs[it]) }
    var total = 0.0
    for (v in ps) total += v
    if (total > 0) for (i in ps.indices) ps[i] /= total
    return OffsetGrid(rs, ps, step)
}

class OffsetGrid(val r: DoubleArray, val p: DoubleArray, val step: Double) {
    val support: Int get() = p.count { it > 0.0 }
}
