package gsb.calc

import gsb.domain.*
import java.security.MessageDigest

const val CALC_ALGORITHM_VERSION = "cal-engine-1.0.0"
const val SEQ_ALGORITHM_VERSION = "seq-engine-1.0.0"
const val DEFAULT_GRID_STEP = 1.0

fun sha256Hex(s: String): String {
    val md = MessageDigest.getInstance("SHA-256")
    return md.digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}

data class CalibrationInput(
    val measurement: Measurement,
    val curve: CalibrationCurve,
    val offset: OffsetAssumption,
    val era: EraSystem,
    val gridStep: Double = DEFAULT_GRID_STEP,
    val seed: Long = 0L
)

sealed class CalibrationOutcome {
    data class Ok(val run: CalibrationRun) : CalibrationOutcome()
    data class Unknown(val diagnostics: List<Diagnostic>) : CalibrationOutcome()
}

/**
 * 固定版本下完全确定：相同（曲线指纹, 测量, 偏移, 网格, 算法版本, seed）
 * 必然产出逐格相同的密度与区间。不使用采样，储库偏移混合分布做解析卷积。
 */
class CalibrationEngine {

    fun calibrate(input: CalibrationInput, id: String, eventSeq: Int, at: String, parentRunId: String? = null): CalibrationOutcome {
        val m = input.measurement
        val c = input.curve
        val diags = mutableListOf<Diagnostic>()
        if (m.age14C == null || m.sigma14C == null) {
            return CalibrationOutcome.Unknown(
                m.diagnostics + Diagnostic(
                    "UNCONVERTIBLE_VALUE", "UNKNOWN",
                    "测量值或标准差无法规范化，保留原始表示，不生成日历密度",
                    m.labCode
                )
            )
        }
        if (c.knots.size < 2) {
            return CalibrationOutcome.Unknown(
                listOf(Diagnostic("CURVE_EMPTY", "ERROR", "校准曲线 ${c.id} 节点不足"))
            )
        }

        val gridStart = c.minCalBp
        val gridEnd = c.maxCalBp
        if (m.age14C < c.knots.first().radioAge - 6 * m.sigma14C - 300 ||
            m.age14C > c.knots.last().radioAge + 6 * m.sigma14C + 300
        ) {
            diags += Diagnostic(
                "OUT_OF_RANGE", "ERROR",
                "测量 ${m.labCode}=${m.age14C}+/-${m.sigma14C} 超出曲线 ${c.id} 支持范围",
                m.labCode
            )
            return CalibrationOutcome.Unknown(diags)
        }

        val n = ((gridEnd - gridStart) / input.gridStep).toInt() + 1
        val raw = DoubleArray(n)
        var z = 0.0
        for (i in 0 until n) {
            val calBp = gridStart + i * input.gridStep
            val mu = interpolate(c, calBp)
            if (i == 0 || i == n - 1) {
                val knotDist = knotDistance(c, calBp)
                if (knotDist > 0) {
                    diags += Diagnostic(
                        "BOUNDARY_INTERPOLATION", "WARNING",
                        "网格边界 calBP=${fmt(calBp)} 位于曲线插值区间内（最近节点距离 ${fmt(knotDist)} calBP）",
                        m.labCode
                    )
                }
            }
            val likelihood = mixtureLikelihood(m.age14C, m.sigma14C, mu, input.offset.distribution)
            raw[i] = likelihood
            z += likelihood
        }
        val normZ = z * input.gridStep
        if (normZ <= 0.0) {
            diags += Diagnostic("ZERO_DENSITY", "UNKNOWN", "密度归一化常数为 0，无法校准", m.labCode)
            return CalibrationOutcome.Unknown(diags)
        }

        val cells = ArrayList<DensityCell>(n)
        for (i in 0 until n) {
            val calBp = gridStart + i * input.gridStep
            cells += DensityCell(calBp, interpolate(c, calBp), raw[i], raw[i] / normZ)
        }
        val modes = detectModes(cells, input.era, input.gridStep)
        val intervals = hpdIntervals(cells, input, id, normZ, diags)
        val fp = paramFingerprint(input, CALC_ALGORITHM_VERSION)

        return CalibrationOutcome.Ok(
            CalibrationRun(
                id = id, eventSeq = eventSeq,
                measurementId = m.id, curveId = c.id, offsetId = input.offset.id, eraId = input.era.id,
                algorithmVersion = CALC_ALGORITHM_VERSION, gridStep = input.gridStep, seed = input.seed,
                paramFingerprint = fp, status = RunStatus.CURRENT,
                createdAt = at, cells = cells, normalizationZ = normZ,
                modes = modes, intervals = intervals, diagnostics = diags, parentRunId = parentRunId
            )
        )
    }

    private fun mixtureLikelihood(age: Double, sigma: Double, mu: Double, dist: OffsetDist): Double {
        val inv2s2 = 1.0 / (2.0 * sigma * sigma)
        var total = 0.0
        var wsum = 0.0
        for (k in dist.components) {
            // 有效测量误差 = sqrt(sigma_lab^2 + sigma_dR^2)；中心偏移 mean
            val se = kotlin.math.sqrt(sigma * sigma + k.sigma * k.sigma)
            val d = age - (mu + k.mean)
            total += k.weight * kotlin.math.exp(-0.5 * (d / se) * (d / se)) / se
            wsum += k.weight
        }
        val norm = kotlin.math.sqrt(2.0 * Math.PI)
        return if (wsum > 0) total / (norm * wsum) else 0.0
    }

    private fun interpolate(c: CalibrationCurve, calBp: Double): Double {
        val ks = c.knots
        if (calBp <= ks.first().calBp) return ks.first().radioAge
        if (calBp >= ks.last().calBp) return ks.last().radioAge
        var lo = 0; var hi = ks.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (ks[mid].calBp <= calBp) lo = mid else hi = mid
        }
        val a = ks[lo]; val b = ks[hi]
        val t = (calBp - a.calBp) / (b.calBp - a.calBp)
        return a.radioAge + t * (b.radioAge - a.radioAge)
    }

    private fun knotDistance(c: CalibrationCurve, calBp: Double): Double =
        c.knots.minOf { kotlin.math.abs(it.calBp - calBp) }

    private fun detectModes(cells: List<DensityCell>, era: EraSystem, step: Double): List<Mode> {
        val modes = mutableListOf<Mode>()
        val maxD = cells.maxOf { it.density }
        val thresh = maxD * 0.03
        var i = 1
        var idx = 0
        while (i < cells.size - 1) {
            if (cells[i].density > thresh && cells[i].density >= cells[i - 1].density &&
                cells[i].density >= cells[i + 1].density
            ) {
                var peak = i
                var j = i
                while (j + 1 < cells.size && cells[j + 1].density > thresh) j++
                var k = i
                while (k - 1 >= 0 && cells[k - 1].density > thresh) k--
                for (p in k..j) if (cells[p].density > cells[peak].density) peak = p
                val start = cells[k].calBp; val end = cells[j].calBp
                modes += Mode(
                    index = idx++, peakCalBp = cells[peak].calBp, peakDensity = cells[peak].density,
                    startCalBp = start, endCalBp = end,
                    eraPeak = EraConversions.toLabel(cells[peak].calBp, era),
                    crossesAnchor = EraConversions.crossesAnchor(start, end, era)
                )
                i = j + 1
            } else i++
        }
        return modes
    }

    private fun hpdIntervals(
        cells: List<DensityCell>, input: CalibrationInput, runId: String, z: Double,
        diags: MutableList<Diagnostic>
    ): List<ProbInterval> {
        val result = mutableListOf<ProbInterval>()
        val step = input.gridStep
        data class Idx(val i: Int, val d: Double)
        val order = cells.indices.sortedWith(compareByDescending<Int> { cells[it].density }.thenBy { it })
        val levels = listOf("P68.2" to 0.682, "P95.4" to 0.954)
        for ((levelName, target) in levels) {
            val inSet = BooleanArray(cells.size)
            var weight = 0.0
            var p = 0
            val groups = mutableListOf<String>()
            while (p < order.size && weight < target - 1e-12) {
                val i = order[p]
                val dThreshold = cells[i].density
                val tieStart = p
                while (p < order.size && cells[order[p]].density == dThreshold) {
                    val q = order[p]
                    inSet[q] = true
                    weight += cells[q].density * step
                    p++
                }
                if (p > tieStart + 1) groups += fmt(dThreshold)
            }
            val runs = mutableListOf<IntRange>()
            var s = -1
            for (i in cells.indices) {
                if (inSet[i]) { if (s < 0) s = i }
                else if (s >= 0) { runs += s until i; s = -1 }
            }
            if (s >= 0) runs += s..cells.indices.last
            val groupId = if (groups.isNotEmpty()) "coeq-${runId}-$levelName@${groups.joinToString(",")}" else null
            if (groups.isNotEmpty()) {
                diags += Diagnostic(
                    "COEQUAL_INTERVALS", "INFO",
                    "$levelName HPD 在密度阈值 ${groups.joinToString(",")} 处并列，并列格点按 calBP 升序同时纳入",
                    input.measurement.labCode
                )
            }
            // calBP 升序（即时间从新到老）稳定排序
            val sortedRuns = runs.sortedBy { cells[it.first].calBp }
            sortedRuns.forEachIndexed { rank, range ->
                val wsum = range.sumOf { cells[it].density } * step
                val startCalBp = cells[range.first].calBp
                val endCalBp = cells[range.last].calBp
                val thresholdD = cells[order[p - 1]].density
                result += ProbInterval(
                    key = "ivl-$runId-$levelName-$rank",
                    level = levelName, rank = rank,
                    startCalBp = startCalBp, endCalBp = endCalBp, probability = wsum,
                    eraStart = EraConversions.toLabel(startCalBp, input.era),
                    eraEnd = EraConversions.toLabel(endCalBp, input.era),
                    crossesAnchor = EraConversions.crossesAnchor(startCalBp, endCalBp, input.era),
                    coequalGroup = groupId,
                    evidence = IntervalEvidence(
                        cellIndexFrom = range.first, cellIndexTo = range.last,
                        weightSum = wsum, targetLevel = target, achievedLevel = weight,
                        normalizationZ = z, gridStep = step,
                        formula = "P = Σ_{i=a..b} density[i]·gridStep,  density[i]=L[i]/Z,  Z=" + fmt(z),
                        tieRule = "HPD 阈值并列时：同密度格点全部纳入，区间按 calBP 升序定序",
                        sourceVersions = mapOf(
                            "measurement" to input.measurement.id,
                            "curve" to input.curve.id,
                            "curveFingerprint" to input.curve.fingerprintSha256,
                            "offset" to input.offset.id,
                            "algorithm" to CALC_ALGORITHM_VERSION,
                            "seed" to input.seed.toString()
                        )
                    )
                )
            }
        }
        return result
    }

    private fun paramFingerprint(input: CalibrationInput, algo: String): String = sha256Hex(
        listOf(
            algo, input.gridStep.toString(), input.seed.toString(),
            input.measurement.id, input.measurement.age14C.toString(), input.measurement.sigma14C.toString(),
            input.curve.id, input.curve.fingerprintSha256,
            input.offset.id,
            input.offset.distribution.model,
            input.offset.distribution.components.joinToString("|") { "${it.mean}/${it.sigma}/${it.weight}" }
        ).joinToString("\n")
    )

    private fun fmt(d: Double) = "%.6f".format(d).trimEnd('0').trimEnd('.')
}

/** 区间概率的独立复核入口：用归一化常数与格点直接积分。 */
fun integrateInterval(cells: List<DensityCell>, z: Double, step: Double, fromCalBp: Double, toCalBp: Double): Double {
    val lo = minOf(fromCalBp, toCalBp); val hi = maxOf(fromCalBp, toCalBp)
    return cells.filter { it.calBp in lo..hi }.sumOf { it.density } * step
}
