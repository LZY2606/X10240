package rcwb

import kotlin.math.*

/** 校准曲线上的一个点：日历坐标 calBP、对应碳十四年龄与 1σ。所有领域量都带单位基准（BP = Before Present, 1950）。 */
data class CurvePoint(val calBP: Double, val c14BP: Double, val sigmaBP: Double)

data class Grid(val from: Double, val to: Double, val step: Double) {
    val size: Int get() = (floor((to - from) / step).toInt() + 1).coerceAtLeast(1)
    fun at(i: Int): Double = from + i * step
}

/** 区间积分证据：任一区间概率可展开读回。 */
data class IntervalEvidence(
    val level: Double,
    val threshold: Double,
    val fromCalBP: Double,
    val toCalBP: Double,
    val points: Int,
    val rawMass: Double,
    val normConst: Double,
    val step: Double,
    val probability: Double,
    val crossesBceCe: Boolean,
    val touchesBoundary: Boolean,
) {
    fun toJson() = obj(
        "level" to level, "threshold" to threshold,
        "fromCalBP" to fromCalBP, "toCalBP" to toCalBP,
        "fromLabel" to Calibration.bceCeLabel(fromCalBP), "toLabel" to Calibration.bceCeLabel(toCalBP),
        "points" to points, "rawMass" to rawMass, "normConst" to normConst,
        "step" to step, "probability" to probability,
        "crossesBceCe" to crossesBceCe, "touchesBoundary" to touchesBoundary,
        "unit" to "calBP", "coordinateBasis" to "BCE/CE(astronomical)"
    )
}

data class CalibrationResult(
    val grid: Grid,
    val densities: List<Double>,   // 归一化后的概率密度（每 calBP 年）
    val normConst: Double,
    val modes: List<Pair<Double, Double>>,
    val medianCalBP: Double,
    val intervals: List<IntervalEvidence>,
    val boundaryHit: Boolean,
    val effectiveAgeBP: Double,
    val effectiveSigmaBP: Double,
)

object Calibration {
    const val ALGO_VERSION = "rc-calib/1.2.0"

    /** 固定曲线版本下的线性插值；越界时钳制到端点并标记 boundary（边界插值情形）。 */
    fun interpolate(curve: List<CurvePoint>, t: Double): Triple<Double, Double, Boolean> {
        require(curve.isNotEmpty()) { "curve must not be empty" }
        if (t <= curve.first().calBP) return Triple(curve.first().c14BP, curve.first().sigmaBP, t < curve.first().calBP)
        if (t >= curve.last().calBP) return Triple(curve.last().c14BP, curve.last().sigmaBP, t > curve.last().calBP)
        var lo = 0; var hi = curve.size - 1
        while (hi - lo > 1) { val mid = (lo + hi) / 2; if (curve[mid].calBP <= t) lo = mid else hi = mid }
        val a = curve[lo]; val b = curve[hi]
        val f = (t - a.calBP) / (b.calBP - a.calBP)
        return Triple(a.c14BP + f * (b.c14BP - a.c14BP), a.sigmaBP + f * (b.sigmaBP - a.sigmaBP), false)
    }

    /**
     * 校准：多峰后验分布，绝不压成平均年。
     * 储库偏移作为分布进入：r' = r - ΔR.mean，σ' = sqrt(σ² + ΔR.σ²)。
     * 纯函数：固定曲线版本 + 固定参数 => 完全可重现。
     */
    fun calibrate(
        c14AgeBP: Double, c14SigmaBP: Double,
        offsetMeanBP: Double, offsetSigmaBP: Double,
        curve: List<CurvePoint>, grid: Grid,
    ): CalibrationResult {
        val rEff = c14AgeBP - offsetMeanBP
        val sEff = sqrt(c14SigmaBP * c14SigmaBP + offsetSigmaBP * offsetSigmaBP)
        val raw = DoubleArray(grid.size)
        var boundary = false
        for (i in 0 until grid.size) {
            val (m, sig, hit) = interpolate(curve, grid.at(i))
            if (hit) boundary = true
            val v2 = sEff * sEff + sig * sig
            val z = rEff - m
            raw[i] = exp(-0.5 * z * z / v2) / sqrt(v2)
        }
        val norm = raw.sum() * grid.step
        require(norm > 0.0) { "normalization constant is zero: measurement too far from curve" }
        val densities = raw.map { it / norm }
        val maxD = densities.max()
        val modes = mutableListOf<Pair<Double, Double>>()
        for (i in densities.indices) {
            val d = densities[i]
            val left = if (i == 0) -1.0 else densities[i - 1]
            val right = if (i == densities.size - 1) -1.0 else densities[i + 1]
            if (d > left && d >= right && d >= 0.05 * maxD) modes.add(grid.at(i) to d)
        }
        // 中位数（仅用于层序检查参考，不作为结论压缩）
        var cum = 0.0; var median = grid.to
        val total = densities.sum() * grid.step
        for (i in densities.indices) {
            cum += densities[i] * grid.step
            if (cum >= total / 2) { median = grid.at(i); break }
        }
        val intervals = listOf(0.682, 0.954).flatMap { level -> hpdIntervals(raw, grid, level, curve) }
        return CalibrationResult(grid, densities, norm, modes, median, intervals, boundary, rEff, sEff)
    }

    /** HPD 可信区间：同一水平可产生多个并列日历区间。 */
    fun hpdIntervals(raw: DoubleArray, grid: Grid, level: Double, curve: List<CurvePoint>): List<IntervalEvidence> {
        val sorted = raw.sortedDescending()
        val total = sorted.sum()
        val target = level * total
        var cum = 0.0; var h = sorted.last()
        for (v in sorted) { cum += v; h = v; if (cum >= target) break }
        val out = mutableListOf<IntervalEvidence>()
        var i = 0
        while (i < raw.size) {
            if (raw[i] >= h && raw[i] > 0.0) {
                var j = i
                var mass = 0.0
                while (j < raw.size && raw[j] >= h) { mass += raw[j]; j++ }
                val from = grid.at(i); val to = grid.at(j - 1)
                out.add(
                    IntervalEvidence(
                        level = level, threshold = h, fromCalBP = from, toCalBP = to,
                        points = j - i, rawMass = mass, normConst = total, step = grid.step,
                        probability = mass / total,
                        crossesBceCe = from < 1950.0 && to > 1949.0,
                        touchesBoundary = from <= curve.first().calBP || to >= curve.last().calBP,
                    )
                )
                i = j
            } else i++
        }
        return out
    }

    /** 任意区间的积分证据（页面上“展开积分证据”用）。 */
    fun evidenceFor(result: CalibrationResult, fromCalBP: Double, toCalBP: Double, level: Double): IntervalEvidence {
        var mass = 0.0; var pts = 0
        for (i in result.densities.indices) {
            val t = result.grid.at(i)
            if (t >= fromCalBP && t <= toCalBP) { mass += result.densities[i]; pts++ }
        }
        val rawTotal = result.densities.sum() // = 1/(step) after normalization; keep rawMass in normalized units
        return IntervalEvidence(
            level = level, threshold = 0.0, fromCalBP = fromCalBP, toCalBP = toCalBP,
            points = pts, rawMass = mass, normConst = rawTotal, step = result.grid.step,
            probability = mass * result.grid.step,
            crossesBceCe = fromCalBP < 1950.0 && toCalBP > 1949.0,
            touchesBoundary = false,
        )
    }

    /** calBP -> BCE/CE 标签（天文纪年：calBP 1949 = 1 CE；calBP 1950 = 1 BCE）。 */
    fun bceCeLabel(calBP: Double): String {
        val ce = 1950.0 - calBP
        return if (ce >= 1.0) "${ce.toInt()} CE" else "${(1.0 - ce).toInt()} BCE"
    }

    fun fingerprint(algoVersion: String, paramsCanonical: String, sources: List<String>): String =
        sha256(algoVersion + "|" + paramsCanonical + "|" + sources.sorted().joinToString(",")).take(16)
}

/** 层序检查：冲突只产生候选调整，绝不改写测量；无法唯一决定时保留全部合法候选。 */
object SequenceCheck {
    const val ALGO_VERSION = "seq-check/1.0.0"

    fun check(
        orderedLabCodes: List<String>,
        medians: Map<String, Double>,          // labCode -> median calBP
        modes: Map<String, List<Double>>,      // labCode -> 全部合法峰位 calBP
    ): JsonObject {
        val violations = mutableListOf<JsonObject>()
        val candidates = mutableListOf<JsonObject>()
        for (k in 0 until orderedLabCodes.size - 1) {
            val older = orderedLabCodes[k]
            val younger = orderedLabCodes[k + 1]
            val mOld = medians[older] ?: continue
            val mYoung = medians[younger] ?: continue
            if (mOld < mYoung) {
                violations.add(obj(
                    "older" to older, "younger" to younger,
                    "olderMedianCalBP" to mOld, "youngerMedianCalBP" to mYoung,
                    "unit" to "calBP",
                    "status" to "VIOLATED",
                ))
                val oldFix = (modes[older] ?: emptyList()).filter { it >= mYoung }
                val youngFix = (modes[younger] ?: emptyList()).filter { it <= mOld }
                if (oldFix.isEmpty()) candidates.add(obj(
                    "sample" to older, "kind" to "NO_LEGAL_MODE",
                    "note" to "分布中不存在满足层序的峰位，保持未知状态，不改写测量",
                ))
                oldFix.forEach { cand ->
                    candidates.add(obj(
                        "sample" to older, "kind" to "PREFER_MODE",
                        "proposedModeCalBP" to cand, "unit" to "calBP",
                        "satisfiesWith" to younger,
                        "note" to "候选调整：仅标记该样本应采用的峰位，测量值保持不变",
                    ))
                }
                youngFix.forEach { cand ->
                    candidates.add(obj(
                        "sample" to younger, "kind" to "PREFER_MODE",
                        "proposedModeCalBP" to cand, "unit" to "calBP",
                        "satisfiesWith" to older,
                        "note" to "候选调整：仅标记该样本应采用的峰位，测量值保持不变",
                    ))
                }
            }
        }
        return obj(
            "algorithmVersion" to ALGO_VERSION,
            "violations" to violations,
            "candidates" to candidates,
            "status" to if (violations.isEmpty()) "CONSISTENT" else "CONFLICT",
        )
    }
}
