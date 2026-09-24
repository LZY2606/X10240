package gsb.rcarb.domain

import kotlinx.serialization.Serializable
import kotlin.math.exp

/**
 * 储库（海洋/硬水/老碳）偏移分布。固定版本、不可变。
 *
 * deltaR 以 14C 年为单位（正值=表观年龄偏老）。支持：
 *  - Gaussian：deltaR ~ N(mean, sd)
 *  - Mixture：若干（weight, mean, sd）高斯成分，归一化后的混合分布
 *  - Discrete：显式离散表（offset/support），用于“无法唯一参数化、保留全部候选”的情形
 *
 * 与曲线卷积时不做蒙特卡洛采样：在固定网格上解析积分，保证可重现。
 */
@Serializable
sealed class ReservoirModel {
    abstract val reservoirId: String
    abstract val version: Int
    abstract val parentVersion: Int?
    abstract val sourceRef: SourceRef
    abstract val note: String

    @Serializable
    data class Gaussian(
        override val reservoirId: String,
        override val version: Int,
        override val parentVersion: Int?,
        val mean: Double,
        val sd: Double,
        override val sourceRef: SourceRef,
        override val note: String = ""
    ) : ReservoirModel()

    @Serializable
    data class Mixture(
        override val reservoirId: String,
        override val version: Int,
        override val parentVersion: Int?,
        val components: List<Component>,
        override val sourceRef: SourceRef,
        override val note: String = ""
    ) : ReservoirModel() {
        @Serializable
        data class Component(val weight: Double, val mean: Double, val sd: Double)
    }

    @Serializable
    data class Discrete(
        override val reservoirId: String,
        override val version: Int,
        override val parentVersion: Int?,
        val support: List<P>,
        override val sourceRef: SourceRef,
        override val note: String = ""
    ) : ReservoirModel() {
        @Serializable
        data class P(val offset: Double, val weight: Double)
    }

    fun variance(): Double = when (this) {
        is Gaussian -> sd * sd
        is Mixture -> {
            val wSum = components.sumOf { it.weight }
            val m = components.sumOf { it.weight * it.mean } / wSum
            val ev2 = components.sumOf { it.weight * (it.sd * it.sd + it.mean * it.mean) } / wSum
            ev2 - m * m
        }
        is Discrete -> {
            val wSum = support.sumOf { it.weight }
            val m = support.sumOf { it.weight * it.offset } / wSum
            support.sumOf { it.weight * (it.offset - m) * (it.offset - m) } / wSum
        }
    }
}

/** 储库偏移在偏移网格上的离散概率（用于卷积证据的精确展开）。 */
data class OffsetDensity(val offset: Double, val density: Double)

object ReservoirKernel {
    private fun gauss(x: Double, m: Double, s: Double): Double {
        val z = (x - m) / s
        return exp(-0.5 * z * z) / (s * 2.5066282746310002)
    }

    /**
     * 在 [-cutSD, +cutSD]·总SD 的窗口内按 step 取点，输出未归一化密度；
     * 归一化在使用处按梯形积分完成（与校准后验同一套规则）。
     */
    fun discretize(model: ReservoirModel, step: Double = 5.0, cutSD: Double = 5.0): List<OffsetDensity> {
        val totalSd = kotlin.math.sqrt(model.variance())
        val mean = when (model) {
            is ReservoirModel.Gaussian -> model.mean
            is ReservoirModel.Mixture -> {
                val w = model.components.sumOf { it.weight }
                model.components.sumOf { it.weight * it.mean } / w
            }
            is ReservoirModel.Discrete -> {
                val w = model.support.sumOf { it.weight }
                model.support.sumOf { it.weight * it.offset } / w
            }
        }
        val half = cutSD * totalSd
        val n = ((2 * half) / step).toInt().coerceAtLeast(1)
        return (0..n).map { i ->
            val x = mean - half + i * step
            val d = when (model) {
                is ReservoirModel.Gaussian -> gauss(x, model.mean, model.sd)
                is ReservoirModel.Mixture -> model.components.sumOf { c ->
                    val w = model.components.sumOf { it.weight }
                    c.weight / w * gauss(x, c.mean, c.sd)
                }
                is ReservoirModel.Discrete -> {
                    // 离散表：每个表点一个窄高斯（宽度=step/2），仅用于稳定数值卷积
                    val bw = step / 2.0
                    model.support.sumOf { p ->
                        val w = model.support.sumOf { it.weight }
                        p.weight / w * gauss(x, p.offset, bw)
                    }
                }
            }
            OffsetDensity(x, d)
        }
    }
}
