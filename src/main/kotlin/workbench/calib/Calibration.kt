package workbench.calib

import workbench.model.*
import java.security.MessageDigest
import kotlin.math.exp
import kotlin.math.sqrt

const val ALGORITHM_VERSION = "calib-1.0"

/** BCE/CE 换算（无 0 年）。calBp 0 == 1950 CE。 */
object CalendarConv {
    fun calBpToDisplay(calBp: Int, bpReferenceYear: Int = 1950): String {
        val year = bpReferenceYear - calBp       // 天文年
        return if (year > 0) "$year CE" else "${1 - year} BCE"
    }
    fun calBpRangeDisplay(startCalBp: Int, endCalBp: Int, bpReferenceYear: Int = 1950): String =
        "${calBpToDisplay(startCalBp, bpReferenceYear)} – ${calBpToDisplay(endCalBp, bpReferenceYear)}"
}

/** 固定版本曲线上的线性插值；越界时钳制到边界并记录诊断（可重现）。 */
class CurveInterpolator(val curve: CurveVersion) {
    val diagnostics = mutableListOf<String>()
    private val pts = curve.points.sortedBy { it.calBp }

    fun c14At(calBp: Int): Pair<Double, Double> {
        if (calBp <= pts.first().calBp) {
            if (calBp < pts.first().calBp) diagnostics += "boundary-extrapolation-clamped@$calBp"
            return pts.first().c14AgeBp to pts.first().sigma
        }
        if (calBp >= pts.last().calBp) {
            if (calBp > pts.last().calBp) diagnostics += "boundary-extrapolation-clamped@$calBp"
            return pts.last().c14AgeBp to pts.last().sigma
        }
        for (i in 0 until pts.size - 1) {
            val a = pts[i]; val b = pts[i + 1]
            if (calBp in a.calBp..b.calBp) {
                if (a.calBp == b.calBp) return a.c14AgeBp to a.sigma
                val t = (calBp - a.calBp).toDouble() / (b.calBp - a.calBp)
                return (a.c14AgeBp + t * (b.c14AgeBp - a.c14AgeBp)) to (a.sigma + t * (b.sigma - a.sigma))
            }
        }
        return pts.last().c14AgeBp to pts.last().sigma
    }
}

object CalibrationEngine {

    data class Params(
        val measurement: Measurement,
        val curve: CurveVersion,
        val offset: ReservoirOffset?,
        val calendarSystem: CalendarSystemDef,
        val gridStartCalBp: Int,
        val gridEndCalBp: Int,
        val gridStepYears: Int = 1,
        val intervalLevels: List<Double> = listOf(0.6827, 0.9545)
    )

    fun fingerprint(p: Params): String {
        val canonical = buildString {
            append(ALGORITHM_VERSION).append('|')
            append(p.measurement.entityId).append('@').append(p.measurement.version).append('|')
            append(p.measurement.c14AgeBp).append('|').append(p.measurement.sigma).append('|')
            append(p.curve.entityId).append('@').append(p.curve.version).append('|')
            append(p.curve.points.joinToString(",") { "${it.calBp}:${it.c14AgeBp}:${it.sigma}" }).append('|')
            append(p.offset?.let { "${it.entityId}@${it.version}:${it.distribution}" } ?: "none").append('|')
            append(p.calendarSystem.entityId).append('@').append(p.calendarSystem.version).append('|')
            append(p.gridStartCalBp).append('|').append(p.gridEndCalBp).append('|').append(p.gridStepYears)
        }
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(canonical.toByteArray()).joinToString("") { "%02x".format(it) }.take(24)
    }

    /** 应用储库偏移分布：均值平移 + 方差合成（uniform 用矩：均值/方差）。 */
    fun applyOffset(age: Double, sigma: Double, dist: OffsetDistribution?): Pair<Double, Double> {
        return when (dist) {
            null -> age to sigma
            is OffsetDistribution.Gaussian -> (age - dist.mean) to sqrt(sigma * sigma + dist.sigma * dist.sigma)
            is OffsetDistribution.Uniform -> {
                val mean = (dist.min + dist.max) / 2.0
                val variance = (dist.max - dist.min) * (dist.max - dist.min) / 12.0
                (age - mean) to sqrt(sigma * sigma + variance)
            }
        }
    }

    fun calibrate(resultId: String, p: Params): CalibrationResult {
        val interp = CurveInterpolator(p.curve)
        val (age, sigma) = applyOffset(p.measurement.c14AgeBp, p.measurement.sigma, p.offset?.distribution)

        val raw = ArrayList<Pair<Int, Double>>()
        var cal = p.gridStartCalBp
        while (cal <= p.gridEndCalBp) {
            val (curveAge, curveSigma) = interp.c14At(cal)
            val combined = sigma * sigma + curveSigma * curveSigma
            val like = exp(-(curveAge - age) * (curveAge - age) / (2.0 * combined))
            raw += cal to like
            cal += p.gridStepYears
        }
        val sum = raw.sumOf { it.second }
        require(sum > 0.0) { "likelihood sum is zero; grid does not overlap measurement" }
        val density = raw.map { (c, l) -> DensityPoint(c, l / sum) }

        val modes = detectModes(density)
        val intervals = p.intervalLevels.flatMap { level -> hpdIntervals(density, level, p.calendarSystem.bpReferenceYear) }
        return CalibrationResult(
            resultId = resultId,
            measurementRef = "${p.measurement.entityId}@v${p.measurement.version}",
            curveRef = "${p.curve.entityId}@v${p.curve.version}",
            offsetRef = p.offset?.let { "${it.entityId}@v${it.version}" },
            calendarSystemRef = "${p.calendarSystem.entityId}@v${p.calendarSystem.version}",
            algorithmVersion = ALGORITHM_VERSION,
            paramFingerprint = fingerprint(p),
            gridStartCalBp = p.gridStartCalBp,
            gridEndCalBp = p.gridEndCalBp,
            gridStepYears = p.gridStepYears,
            density = density,
            modes = modes,
            intervals = intervals,
            diagnostics = interp.diagnostics.distinct()
        )
    }

    /** 多峰检测：局部极大值，峰间以低于峰高 5% 的谷分隔；质量按区间求和。绝不压成平均年。 */
    fun detectModes(density: List<DensityPoint>): List<Mode> {
        if (density.isEmpty()) return emptyList()
        val peaks = ArrayList<Int>()
        for (i in density.indices) {
            val p = density[i].probability
            val left = if (i > 0) density[i - 1].probability else -1.0
            val right = if (i < density.size - 1) density[i + 1].probability else -1.0
            if (p >= left && p > right && p > 0) peaks += i
        }
        if (peaks.isEmpty()) peaks += density.indices.maxBy { density[it].probability }
        val modes = ArrayList<Mode>()
        var start = 0
        for (k in peaks.indices) {
            val peakIdx = peaks[k]
            val end = if (k < peaks.size - 1) {
                var valley = peakIdx
                for (j in peakIdx until peaks[k + 1]) if (density[j].probability < density[valley].probability) valley = j
                valley
            } else density.size - 1
            val mass = (start..end).sumOf { density[it].probability }
            modes += Mode(
                index = modes.size,
                peakCalBp = density[peakIdx].calBp,
                rangeStartCalBp = density[start].calBp,
                rangeEndCalBp = density[end].calBp,
                mass = mass
            )
            start = end
        }
        return modes.filter { it.mass > 1e-6 }
    }

    /** HPD 区间：按密度从高到低累积到 level；每段给出可展开的逐点积分证据。 */
    fun hpdIntervals(density: List<DensityPoint>, level: Double, bpReferenceYear: Int): List<CalendarInterval> {
        val order = density.indices.sortedByDescending { density[it].probability }
        val included = BooleanArray(density.size)
        var acc = 0.0
        for (i in order) {
            if (acc >= level) break
            included[i] = true
            acc += density[i].probability
        }
        val intervals = ArrayList<CalendarInterval>()
        var i = 0
        while (i < density.size) {
            if (!included[i]) { i++; continue }
            var j = i
            while (j + 1 < density.size && included[j + 1]) j++
            val seg = density.subList(i, j + 1)
            var cum = 0.0
            var runBase = 0.0
            // 累计从段首开始；段前累计不影响段内证据可读性
            val evidence = seg.map { pt ->
                cum += pt.probability
                IntervalEvidencePoint(pt.calBp, pt.probability, runBase + cum)
            }
            val startCal = seg.first().calBp
            val endCal = seg.last().calBp
            intervals += CalendarInterval(
                startCalBp = startCal,
                endCalBp = endCal,
                level = level,
                probability = seg.sumOf { it.probability },
                startDisplay = CalendarConv.calBpToDisplay(startCal, bpReferenceYear),
                endDisplay = CalendarConv.calBpToDisplay(endCal, bpReferenceYear),
                evidence = evidence
            )
            i = j + 1
        }
        return intervals.sortedByDescending { it.probability }
    }
}
