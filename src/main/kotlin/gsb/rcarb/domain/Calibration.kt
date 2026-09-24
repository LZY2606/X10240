package gsb.rcarb.domain

import kotlinx.serialization.Serializable
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

const val ALGORITHM_CALIBRATION = "calibration-grid-v1"

/**
 * 校准引擎。
 *
 * 后验（Ward & Bronk Ramsey 风格的“似然即后验”，无先验曲线）：
 *
 *   L(t) ∝ ∫ N(Dobs | mu(t) + r, sigmaD^2 + sigmaCurve(t)^2) * pR(r) dr
 *
 * 其中 t 为整数 calBP 网格；mu、sigmaCurve 由固定曲线版本线性插值；
 * pR 为储库偏移密度（无偏移即 r=0 的点质量）。积分在固定网格上离散完成，
 * 密度在覆盖区间内按梯形规则归一化——固定输入 => 逐位可重现。
 * 曲线范围之外 L=0（不外推），并在证据中标注 boundary。
 */
object CalibrationEngine {
    const val GRID_STEP = 1
    private val SQRT_2PI = sqrt(2.0 * Math.PI)

    data class Point(val calBp: Int, val density: Double, val mu14c: Double, val sigmaTotal: Double)

    fun run(
        measurement: Measurement,
        curve: CalibrationCurve,
        reservoir: ReservoirModel?,
        offsetDensityGrid: List<OffsetDensity> = emptyList(),
        peakWindow: Int = 20
    ): CalibrationResult {
        val evidence = mutableListOf<EvidenceStep>()
        if (!measurement.valid) {
            return CalibrationResult(
                status = ResultStatus.Unknown,
                points = emptyList(),
                intervals = emptyList(),
                peaks = emptyList(),
                evidence = listOf(
                    EvidenceStep(
                        step = "validate-measurement",
                        status = "failed",
                        detail = "measurement invalid: ${measurement.diagnostics.joinToString("; ")}"
                    )
                ),
                gridStep = GRID_STEP,
                normalizationMass = 0.0
            )
        }
        val dobs = measurement.age14c!!
        val sd = measurement.sigma14c!!
        evidence += EvidenceStep(
            step = "validate-measurement",
            status = "ok",
            detail = "Dobs=${dobs}14CyrBP sigma=${sd}14Cyr (source ${measurement.labCode} v${measurement.version})"
        )
        evidence += EvidenceStep(
            step = "curve-version",
            status = "ok",
            detail = "${curve.curveId}@${curve.version} knots=${curve.knots.size} coverage=[${curve.calBpMin},${curve.calBpMax}] calBP"
        )

        val offsets: List<Pair<Double, Double>> = reservoirOffsets(reservoir, offsetDensityGrid)
        if (reservoir != null) {
            evidence += EvidenceStep(
                step = "reservoir-convolution",
                status = "ok",
                detail = "${reservoir.reservoirId}@v${reservoir.version} components=${offsets.size} kernel=trapezoid step=${offsets.stepText()}"
            )
        }

        val raw = mutableListOf<Point>()
        var outsideCount = 0
        var interpLinear = 0
        var interpExact = 0
        for (t in curve.calBpMin..curve.calBpMax step GRID_STEP) {
            val iv = curve.interp(t)
            when (iv.kind) {
                CalibrationCurve.InterpKind.Linear -> interpLinear++
                CalibrationCurve.InterpKind.Exact -> interpExact++
                CalibrationCurve.InterpKind.Outside -> outsideCount++
            }
            if (iv.kind == CalibrationCurve.InterpKind.Outside) continue
            val sigCurve = curve.sigmaInterp(t)
            val sigTot = sqrt(sd * sd + sigCurve * sigCurve)
            var lik = 0.0
            for ((r, w) in offsets) {
                val z = (dobs - (iv.mu14c + r)) / sigTot
                lik += w * exp(-0.5 * z * z) / (sigTot * SQRT_2PI)
            }
            raw += Point(t, lik, iv.mu14c, sigTot)
        }
        evidence += EvidenceStep(
            step = "interpolate",
            status = "ok",
            detail = "linear-interp-points=$interpLinear exact-knot-points=$interpExact outside=$outsideCount (no extrapolation)"
        )

        // 梯形归一化
        var mass = 0.0
        for (i in 1 until raw.size) {
            mass += 0.5 * (raw[i].density + raw[i - 1].density) * (raw[i].calBp - raw[i - 1].calBp)
        }
        val normalized = if (mass > 0) raw.map { it.copy(density = it.density / mass) } else raw
        evidence += EvidenceStep(
            step = "normalize",
            status = if (mass > 0) "ok" else "failed",
            detail = "trapezoid-mass=${"%.12g".format(mass)} gridStep=${GRID_STEP}yr"
        )

        val peaks = findPeaks(normalized, peakWindow)
        evidence += EvidenceStep(
            step = "peaks",
            status = "ok",
            detail = "modes=${peaks.size} ${peaks.joinToString { Calendar.formatEra(it.calBp) }}"
        )

        val i68 = hpdIntervals(normalized, 0.6827, "68")
        val i95 = hpdIntervals(normalized, 0.9545, "95")
        evidence += EvidenceStep(
            step = "hpd",
            status = "ok",
            detail = "68.27% intervals=${i68.size}, 95.45% intervals=${i95.size}; multimodal kept separate (no mean-year collapse)"
        )

        return CalibrationResult(
            status = if (mass > 0) ResultStatus.Ok else ResultStatus.Unknown,
            points = normalized,
            intervals = i95 + i68,
            peaks = peaks,
            evidence = evidence,
            gridStep = GRID_STEP,
            normalizationMass = mass
        )
    }

    private fun List<OffsetDensity>.stepText(): String =
        if (size >= 2) "%.2fyr".format(kotlin.math.abs(this[1].offset - this[0].offset)) else "n/a"

    private fun reservoirOffsets(model: ReservoirModel?, provided: List<OffsetDensity>): List<Pair<Double, Double>> {
        if (model == null) return listOf(0.0 to 1.0)
        val grid = provided.ifEmpty { ReservoirKernel.discretize(model) }
        var area = 0.0
        for (i in 1 until grid.size) {
            area += 0.5 * (grid[i].density + grid[i - 1].density) * (grid[i].offset - grid[i - 1].offset)
        }
        val step = if (grid.size >= 2) kotlin.math.abs(grid[1].offset - grid[0].offset) else 1.0
        // 梯形积分：先归一化 pR，再乘固定步长得到求和权重；外层密度随后整体归一化。
        return grid.map { it.offset to (if (area > 0) it.density / area else it.density) * step }
    }

    /** 局部极大值：窗口内密度最大且 > 0；并列时取较老者（calBP 更大），保证稳定。 */
    fun findPeaks(points: List<Point>, window: Int): List<Peak> {
        val out = mutableListOf<Peak>()
        for (i in points.indices) {
            if (points[i].density <= 0) continue
            val l = (i - window).coerceAtLeast(0)
            val r = (i + window).coerceAtMost(points.size - 1)
            var isPeak = true
            for (j in l..r) {
                if (j == i) continue
                if (points[j].density > points[i].density) { isPeak = false; break }
                if (points[j].density == points[i].density && points[j].calBp > points[i].calBp) {
                    isPeak = false; break
                }
            }
            if (isPeak) out += Peak(points[i].calBp, points[i].density)
        }
        return out.sortedByDescending { it.density }.mapIndexed { idx, p -> p.copy(rank = idx + 1) }
            .sortedByDescending { it.calBp }
    }

    /**
     * HPD：按密度阈值二分，密度>=阈值的网格点形成若干游程；游程端点做边界插值修正，
     * 概率按梯形积分。并列区间（多峰）各自独立给出，稳定排序：较老优先，再按 id。
     */
    fun hpdIntervals(points: List<Point>, target: Double, tag: String): List<CalendarInterval> {
        if (points.isEmpty()) return emptyList()
        var lo = 0.0
        var hi = points.maxOf { it.density }
        var runs: List<IntRange> = emptyList()
        var prob = 0.0
        repeat(60) {
            val mid = 0.5 * (lo + hi)
            val candidate = runsAbove(points, mid)
            val p = totalProbability(points, candidate)
            if (p >= target) { lo = mid; runs = candidate; prob = p } else hi = mid
        }
        if (runs.isEmpty()) {
            runs = runsAbove(points, lo)
            prob = totalProbability(points, runs)
        }
        return runs.sortedByDescending { points[it.first].calBp }.mapIndexed { idx, range ->
            val older = points[range.first].calBp
            val younger = points[range.last].calBp
            val threshold = points[range.first].density
            val edges = edgeInterpolation(points, range, lo)
            CalendarInterval(
                intervalId = "HPD$tag-${"%02d".format(idx + 1)}",
                level = tag,
                olderCalBp = older,
                youngerCalBp = younger,
                probability = totalProbability(points, listOf(range)).coerceAtMost(1.0),
                densityThreshold = threshold,
                edgeInterpolation = edges,
                crossesBceCe = Calendar.spansBceCeBoundary(older, younger),
                tieGroup = null
            )
        }.let { intervals ->
            // 并列日历区间：概率差在 1e-9 内的同层区间标 tieGroup
            val byLevel = intervals.groupBy { it.level }
            byLevel.flatMap { (_, list) ->
                val sorted = list.sortedByDescending { it.probability }
                var g = 0
                val marks = HashMap<String, Int>()
                for (i in sorted.indices) {
                    if (i == 0 || kotlin.math.abs(sorted[i].probability - sorted[i - 1].probability) > 1e-9) g++
                    marks[sorted[i].intervalId] = g
                }
                list.map { it.copy(tieGroup = "tie-$tag-${marks[it.intervalId]}") }
            }
        }.also { prob }
    }

    private fun runsAbove(points: List<Point>, threshold: Double): List<IntRange> {
        val out = mutableListOf<IntRange>()
        var start = -1
        for (i in points.indices) {
            if (points[i].density >= threshold) {
                if (start < 0) start = i
            } else {
                if (start >= 0) { out += start..(i - 1); start = -1 }
            }
        }
        if (start >= 0) out += start..(points.size - 1)
        return out
    }

    private fun totalProbability(points: List<Point>, runs: List<IntRange>): Double {
        var p = 0.0
        for (range in runs) {
            for (i in (range.first + 1)..range.last) {
                p += 0.5 * (points[i].density + points[i - 1].density) *
                    (points[i].calBp - points[i - 1].calBp)
            }
        }
        return p
    }

    private fun edgeInterpolation(points: List<Point>, range: IntRange, threshold: Double): List<EdgePoint> {
        val res = mutableListOf<EdgePoint>()
        val left = range.first
        val right = range.last
        if (left > 0) res += EdgePoint(points[left - 1].calBp, points[left].calBp, points[left - 1].density, points[left].density, threshold)
        if (right < points.size - 1) res += EdgePoint(points[right].calBp, points[right + 1].calBp, points[right].density, points[right + 1].density, threshold)
        return res
    }

    private fun CalibrationCurve.sigmaInterp(calBp: Int): Double {
        val s = sorted()
        val arr = s.knots
        if (calBp <= arr.first().calBp) return arr.first().sigma14c
        if (calBp >= arr.last().calBp) return arr.last().sigma14c
        var lo = 0
        var hi = arr.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (arr[mid].calBp < calBp) lo = mid + 1 else hi = mid - 1
        }
        val a = arr[lo - 1]
        val b = arr[lo]
        val f = (calBp - a.calBp).toDouble() / (b.calBp - a.calBp)
        return a.sigma14c + f * (b.sigma14c - a.sigma14c)
    }
}

@Serializable
data class CalibrationResult(
    val status: ResultStatus,
    val points: List<CalibrationEngine.Point>,
    val intervals: List<CalendarInterval>,
    val peaks: List<Peak>,
    val evidence: List<EvidenceStep>,
    val gridStep: Int,
    val normalizationMass: Double
)

@Serializable
data class CalendarInterval(
    val intervalId: String,
    val level: String,
    val olderCalBp: Int,
    val youngerCalBp: Int,
    val probability: Double,
    val densityThreshold: Double,
    val edgeInterpolation: List<EdgePoint>,
    val crossesBceCe: Boolean,
    val tieGroup: String?
)

@Serializable
data class EdgePoint(
    val outerCalBp: Int,
    val innerCalBp: Int,
    val outerDensity: Double,
    val innerDensity: Double,
    val threshold: Double
)

@Serializable
data class Peak(val calBp: Int, val density: Double, val rank: Int = 0)

@Serializable
data class EvidenceStep(
    val step: String,
    val status: String,
    val detail: String
)

enum class ResultStatus { Ok, Unknown, Stale }
