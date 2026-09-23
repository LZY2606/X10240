package gsb.calib

import gsb.domain.*
import kotlin.math.*

/**
 * 校准引擎（确定性、固定 seed 无关：全部使用固定求积节点与固定网格）。
 *
 * 似然（含储库偏移分布的边缘化）：
 *   p(t) ∝ Σ_k w_k · exp( -(mu(t) + ΔR_k - y)^2 / (2 (σ²(t)+s²+σr_k²)) )
 * 其中 t 为 cal BP 网格，mu/σ 为曲线插值，ΔR_k 为对偏移正态分布的固定高斯-勒让德/等概节点。
 *
 * 设计原则：
 *  - 多峰密度保留为完整数组，绝不压缩成单一平均年；
 *  - 边界（曲线范围外）产生诊断，而不是悄悄裁剪；
 *  - 同一曲线版本、同一参数指纹下逐位可重现。
 */
object CalibrationEngine {
    const val ALGORITHM_VERSION = "cal-1.0.0"
    const val GRID_STEP = 1
    private const val RESERVOIR_NODES = 5

    /** 标准正态分布的 5 个等概率分位中点（固定、无需随机 seed）。 */
    private val QUANTILE_MIDS = doubleArrayOf(
        -0.841621233572915,  // 0%-20% 区近似中点（用逆CDF在 0.1）
        -0.2533471031358,
        0.0,
        0.2533471031358,
        0.841621233572915
    )
    private const val NODE_WEIGHT = 0.2

    private fun inverseStandardNormalAt(p: Double): Double = QUANTILE_MIDS.let {
        // 上面的常量即预计算的 Φ⁻¹(0.1,0.3,0.5,0.7,0.9)
        when (p) {
            0.1 -> -1.2815515655446004
            0.3 -> -0.5244008443621045
            0.5 -> 0.0
            0.7 -> 0.5244008443621045
            0.9 -> 1.2815515655446004
            else -> 0.0
        }
    }

    /** 偏移分布求积节点：等概率 5 分箱，节点位于 Φ⁻¹(箱中值)。 */
    fun reservoirNodes(deltaR: Double, sigmaR: Double): List<ResNode> {
        val mids = doubleArrayOf(0.1, 0.3, 0.5, 0.7, 0.9)
        return mids.map { p ->
            val z = inverseStandardNormalAt(p)
            ResNode(deltaR + z * sigmaR, NODE_WEIGHT, z)
        }
    }

    data class ResNode(val value: Double, val weight: Double, val z: Double)

    data class Grid(
        val calBp: IntRange,
        val values: DoubleArray,
        val rawMass: DoubleArray,
        val boundaryHits: List<String>,
        val outOfRangeYears: List<Int>
    )

    fun calibrate(
        measurement: Measurement,
        curve: CalibrationCurve,
        reservoir: ReservoirAssumption?,
        createdAt: String,
        runId: String,
        evidenceIdFactory: (String) -> String
    ): CalibrationRunResult {
        val diagnostics = mutableListOf<String>()
        require(measurement.c14Age.isUsable) { "measurement c14Age unusable: ${measurement.c14Age.diagnostic}" }
        require(measurement.c14Sigma.isUsable) { "measurement c14Sigma unusable" }
        val y = measurement.c14Age.normalized!!
        val s = measurement.c14Sigma.normalized!!

        val deltaR = reservoir?.deltaR?.normalized ?: 0.0
        val sigmaR = reservoir?.deltaRSigma?.normalized ?: 0.0
        if (reservoir != null && (!reservoir.deltaR.isUsable || !reservoir.deltaRSigma.isUsable)) {
            diagnostics += "Reservoir ${reservoir.id} 含无法转换的值，偏移按 0 处理并保留原始表示"
        }
        val nodes = if (reservoir != null) reservoirNodes(deltaR, sigmaR) else listOf(ResNode(0.0, 1.0, 0.0))

        // 网格范围：由测量 ±6σ（经曲线反查窗口会在引擎外层扩展），这里用曲线全范围与年龄窗口的交集。
        val window = ceil(6.0 * sqrt(s * s + sigmaR * sigmaR)).toInt()
        val ageLo = y - window - (deltaR - 3 * sigmaR)
        val ageHi = y + window - (deltaR + 3 * sigmaR)

        val boundaryHits = mutableListOf<String>()
        val outYears = mutableListOf<Int>()
        // 在曲线上找到 c14 落在 [ageLo, ageHi] 的 calBp 区间，并向两侧各扩 60 年。
        val candidate = curve.points.filter { it.c14Bp in ageLo..ageHi }.map { it.calBp }
        val rawFrom = (candidate.minOrNull()?.let { it - 60 } ?: curve.minCalBp)
            .coerceAtLeast(curve.minCalBp)
        val rawTo = (candidate.maxOrNull()?.let { it + 60 } ?: curve.maxCalBp)
            .coerceAtMost(curve.maxCalBp)
        val from = minOf(rawFrom, rawTo)
        val to = maxOf(rawFrom, rawTo)

        val years = (from..to).toList()
        val mass = DoubleArray(years.size)
        for ((i, calBpYear) in years.withIndex()) {
            val interp = curve.interpolate(calBpYear.toDouble())
            if (interp == null) {
                outYears += calBpYear
                continue
            }
            if (interp.boundary != BoundaryKind.INTERIOR) {
                val kind = if (interp.boundary == BoundaryKind.AT_MIN) "curve-min-boundary@calBP$calBpYear"
                else "curve-max-boundary@calBP$calBpYear"
                if (boundaryHits.none { it == kind }) boundaryHits += kind
            }
            var acc = 0.0
            // 节点方差仅合并测量与曲线误差；偏移的不确定性通过在节点网格上积分表达，
            // 避免对同一 σΔR 重复计两次。
            val sigTot = sqrt(s * s + interp.sigma * interp.sigma)
            for (node in nodes) {
                val diff = interp.c14 + node.value - y
                val z = diff / sigTot
                acc += node.weight * exp(-0.5 * z * z) / (sigTot * sqrt(2.0 * PI))
            }
            mass[i] = acc
        }
        if (outYears.isNotEmpty()) diagnostics += "有 ${outYears.size} 个网格年落在曲线范围外，已跳过（未静默外推）"

        val total = mass.sum() * GRID_STEP
        if (total <= 0.0) {
            diagnostics += "校准似然总质量为 0（测量可能超出曲线覆盖范围）"
        }
        val density = if (total > 0) DoubleArray(mass.size) { mass[it] / total } else DoubleArray(mass.size)

        val fingerprint = paramFingerprint(
            curve.id, curve.version, measurement.id, measurement.c14Age.raw,
            measurement.c14Sigma.raw, reservoir?.id ?: "none", deltaR, sigmaR, GRID_STEP, from, to
        )

        val hpd68 = hpdIntervals(density, years, 0.6827).mapIndexed { idx, iv ->
            iv.toInterval(runId, evidenceIdFactory, "HPD68#${idx + 1}", density, years)
        }
        val hpd95 = hpdIntervals(density, years, 0.9545).mapIndexed { idx, iv ->
            iv.toInterval(runId, evidenceIdFactory, "HPD95#${idx + 1}", density, years)
        }

        val componentCount = countComponents(density, years)
        val multiModal = componentCount >= 2

        return CalibrationRunResult(
            runId = runId,
            measurementId = measurement.id,
            sampleId = measurement.sampleId,
            curveId = curve.id,
            curveVersion = curve.version,
            reservoirId = reservoir?.id,
            algorithmVersion = ALGORITHM_VERSION,
            paramFingerprint = fingerprint,
            directSources = listOfNotNull(
                measurement.source.origin, curve.source.origin, reservoir?.source?.origin
            ),
            createdAt = createdAt,
            gridStepYears = GRID_STEP,
            gridFromCalBp = from,
            gridToCalBp = to,
            density = density.toList(),
            hpd68 = hpd68,
            hpd95 = hpd95,
            integralEvidence = (hpd68 + hpd95).map { iv ->
                buildEvidence(iv, runId, density, years, from, to)
            },
            boundaryHits = boundaryHits,
            multiModal = multiModal,
            componentCount = componentCount,
            diagnostics = diagnostics
        )
    }

    private data class HpdSpan(val from: Int, val to: Int)

    private fun hpdIntervals(density: DoubleArray, years: List<Int>, coverage: Double): List<HpdSpan> {
        if (density.isEmpty()) return emptyList()
        val target = coverage
        val totalMass = density.sum()
        if (totalMass <= 0) return emptyList()
        // HPD：从最高密度年起逐年并入，直到累计质量达标。
        val order = density.indices.sortedWith(compareByDescending<Int> { density[it] })
        val chosen = BooleanArray(density.size)
        var mass = 0.0
        for (idx in order) {
            chosen[idx] = true
            mass += density[idx] / totalMass
            if (mass >= target) break
        }
        val spans = mutableListOf<HpdSpan>()
        var i = 0
        while (i < chosen.size) {
            if (chosen[i]) {
                var j = i
                while (j + 1 < chosen.size && chosen[j + 1]) j++
                // 合并相隔 1 年的“桥接”在物理上仍视为独立峰，不做形态学膨胀。
                spans += HpdSpan(years[i], years[j])
                i = j + 1
            } else i++
        }
        return spans
    }

    private fun HpdSpan.toInterval(
        runId: String,
        evidenceFactory: (String) -> String,
        key: String,
        density: DoubleArray,
        years: List<Int>
    ): CalendarInterval {
        return CalendarInterval(
            key = key,
            fromAstronomical = 1950 - maxOf(from, to),
            toAstronomical = 1950 - minOf(from, to),
            probability = intervalProbability(density, years, minOf(from, to), maxOf(from, to)),
            integralEvidenceId = evidenceFactory(key)
        )
    }

    fun intervalProbability(density: DoubleArray, years: List<Int>, fromCalBp: Int, toCalBp: Int): Double {
        var m = 0.0
        for ((i, yr) in years.withIndex()) {
            if (yr in fromCalBp..toCalBp) m += density[i]
        }
        return m * GRID_STEP
    }

    private fun buildEvidence(
        iv: CalendarInterval,
        runId: String,
        density: DoubleArray,
        years: List<Int>,
        from: Int,
        to: Int
    ): IntegralEvidence {
        val loCalBp = 1950 - iv.toAstronomical
        val hiCalBp = 1950 - iv.fromAstronomical
        var mass = 0.0
        for ((i, yr) in years.withIndex()) if (yr in loCalBp..hiCalBp) mass += density[i]
        return IntegralEvidence(
            id = iv.integralEvidenceId,
            runId = runId,
            intervalKey = iv.key,
            gridStepYears = GRID_STEP,
            gridFromCalBp = loCalBp,
            gridToCalBp = hiCalBp,
            unnormalizedMass = mass, // 归一化后的逐点概率和（×步长=概率）
            totalMass = 1.0,
            probability = mass * GRID_STEP,
            quadratureNodes = RESERVOIR_NODES,
            formula = "P([t1,t2]) = Σ_{t=t1}^{t2} d(t)·Δt, d(t)=似然(t)/Σ似然·Δt; 偏移边缘化使用 5 等概分位节点"
        )
    }

    private fun countComponents(density: DoubleArray, years: List<Int>): Int {
        if (density.isEmpty()) return 0
        val peak = density.maxOrNull() ?: return 0
        val threshold = peak * 0.02
        var count = 0
        var inside = false
        for (v in density) {
            if (v > threshold && !inside) { count++; inside = true }
            if (v <= threshold) inside = false
        }
        return count
    }

    fun paramFingerprint(vararg parts: Any): String {
        val text = parts.joinToString("|") { it.toString() }
        var h1 = 0x811c9dc5.toLong()
        for (b in text.toByteArray()) {
            h1 = h1 xor (b.toLong() and 0xff)
            h1 = (h1 * 0x01000193L) and 0xffffffffL
        }
        return "fp-" + h1.toString(16).padStart(8, '0')
    }
}
