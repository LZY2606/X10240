package bench

import kotlin.math.*

/** 校准引擎：固定曲线版本 + 固定网格下完全可重现（无随机性）。 */
object Calibrate {
    const val ALGO_VERSION = "calib-core/1.2.0"
    val DEFAULT_GRID = GridSpec(0.0, 6000.0, 5.0)
    val HPD_LEVELS = listOf(0.6827, 0.9545)

    fun gaussPdf(x: Double): Double = exp(-0.5 * x * x) / sqrt(2.0 * PI)

    /**
     * 核心校准：测量 M±s（14C BP），曲线 curve，可选储库偏移分布。
     * 对每个日历年 t：L(t) = Σ_k w_k · N(M − μ_k; c14(t), √(s² + σ_k² + σc(t)²))
     * 网格梯形归一化；越界触发边界插值诊断但保留数值。
     */
    fun calibrate(measured: C14Age, curve: CurveTable, offset: OffsetDistribution?, grid: GridSpec): DensityGrid {
        val n = grid.size
        val raw = DoubleArray(n)
        var boundary = false
        val diagnostics = mutableListOf<String>()
        val wNorm = offset?.totalWeight ?: 1.0
        for (i in 0 until n) {
            val t = grid.at(i)
            val (cp, hitBoundary) = curve.interpolate(t)
            if (hitBoundary) boundary = true
            var l = 0.0
            if (offset == null) {
                val sd = sqrt(measured.sigma * measured.sigma + cp.c14Sigma * cp.c14Sigma)
                l = gaussPdf((measured.bp - cp.c14Age) / sd) / sd
            } else {
                for (c in offset.components) {
                    val sd = sqrt(measured.sigma * measured.sigma + c.sigma * c.sigma + cp.c14Sigma * cp.c14Sigma)
                    l += (c.weight / wNorm) * gaussPdf((measured.bp - c.mean - cp.c14Age) / sd) / sd
                }
            }
            raw[i] = l
        }
        if (boundary) diagnostics.add(
            "boundary-interpolation: 分布越出曲线表范围 [${curve.minCalBp}, ${curve.maxCalBp}] cal BP，端点段线性外推，数值保留在原位置")
        // 梯形归一化
        var area = 0.0
        for (i in 0 until n - 1) area += (raw[i] + raw[i + 1]) / 2.0 * grid.step
        if (area <= 0.0) {
            diagnostics.add("normalization-failed: 网格内似然为零，分布无法归一化（测量与曲线可能不兼容）")
            return DensityGrid(grid, raw, boundary, diagnostics)
        }
        for (i in 0 until n) raw[i] /= area
        return DensityGrid(grid, raw, boundary, diagnostics)
    }

    /** HPD 区间：保留多峰，不合并、不取平均年。 */
    fun hpdIntervals(grid: DensityGrid, levels: List<Double> = HPD_LEVELS): List<CalInterval> {
        val d = grid.density
        val step = grid.spec.step
        val total = (0 until d.size - 1).sumOf { (d[it] + d[it + 1]) / 2.0 } * step
        val result = mutableListOf<CalInterval>()
        for (level in levels) {
            // 阈值：按密度降序累积质量到 level*total
            val sorted = d.sortedDescending()
            var acc = 0.0; var threshold = 0.0
            val target = level * total
            for (v in sorted) { acc += v * step; threshold = v; if (acc >= target) break }
            // 连续高于阈值的段为一个峰
            var i = 0
            while (i < d.size) {
                if (d[i] >= threshold) {
                    var j = i
                    while (j + 1 < d.size && d[j + 1] >= threshold) j++
                    // 边界线性回插到阈值交点
                    val from = if (i > 0 && d[i] > d[i - 1]) {
                        val f = (threshold - d[i - 1]) / (d[i] - d[i - 1])
                        grid.at(i - 1) + f * step
                    } else grid.at(i)
                    val to = if (j + 1 < d.size && d[j + 1] < d[j]) {
                        val f = (threshold - d[j + 1]) / (d[j] - d[j + 1])
                        grid.at(j) + f * step
                    } else grid.at(j)
                    val prob = grid.probabilityWithEvidence(from, to).probability
                    result.add(CalInterval(from, to, prob, level))
                    i = j + 1
                } else i++
            }
        }
        return result.sortedWith(compareBy({ it.level }, { -it.fromCalBp }))
    }

    /** P(A 比 B 老)：独立后验下对网格求和。 */
    fun probOlder(a: DensityGrid, b: DensityGrid): Double {
        require(a.spec == b.spec) { "grids must match" }
        val step = a.spec.step
        // B 的“更年轻”累积：cdfYounger[i] = P(B > t_i)（calBP 更大 = 更老）
        // P(A 老于 B) = Σ_i a_i · P(B 的 calBP < t_i)
        var cumB = 0.0
        var p = 0.0
        for (i in a.density.indices) {
            val massA = a.density[i] * step
            p += massA * cumB
            cumB += b.density[i] * step
        }
        return p
    }

    /** 层序冲突候选调整：截断后验到满足约束的区域并重新归一化；不改写测量。 */
    fun truncationCandidate(grid: DensityGrid, olderThan: Double?, youngerThan: Double?): DensityGrid {
        val d = grid.density.copyOf()
        for (i in d.indices) {
            val t = grid.at(i)
            if (olderThan != null && t < olderThan) d[i] = 0.0
            if (youngerThan != null && t > youngerThan) d[i] = 0.0
        }
        var area = 0.0
        for (i in 0 until d.size - 1) area += (d[i] + d[i + 1]) / 2.0 * grid.spec.step
        val diags = grid.diagnostics.toMutableList()
        if (area <= 0.0) {
            diags.add("truncation-empty: 约束区域与后验无交集，候选无法归一化")
            return DensityGrid(grid.spec, d, grid.boundaryInterpolated, diags)
        }
        for (i in d.indices) d[i] /= area
        diags.add("truncated: olderThan=$olderThan youngerThan=$youngerThan retainedMass=${"%.4f".format(area)}")
        return DensityGrid(grid.spec, d, grid.boundaryInterpolated, diags)
    }

    /** 分位数（cal BP）。 */
    fun quantile(grid: DensityGrid, q: Double): Double {
        val step = grid.spec.step
        var acc = 0.0
        for (i in grid.density.indices) {
            acc += grid.density[i] * step
            if (acc >= q) return grid.at(i)
        }
        return grid.spec.endCalBp
    }

    /** 参数指纹：固定版本下可重现的校验依据。 */
    fun fingerprint(measured: C14Age, curveName: String, offset: OffsetDistribution?, grid: GridSpec): String =
        Ids.of("fp", "${measured.bp}|${measured.sigma}|$curveName|${Json.to(offset)}|${grid.fingerprint()}|$ALGO_VERSION")
}
