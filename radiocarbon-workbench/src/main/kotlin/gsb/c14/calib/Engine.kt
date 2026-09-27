package gsb.c14.calib

import gsb.c14.domain.*
import kotlinx.serialization.Serializable
import kotlin.math.*

const val ALGORITHM_VERSION = "c14-grid-v1.0.0"

/** 密度网格上的一个年份单元；prob 是该年（1 年宽）的概率质量。 */
@Serializable
data class DensityBin(val year: Int, val prob: Double, val components: List<AtomContribution> = emptyList())

@Serializable
data class AtomContribution(val offsetYears: Double, val atomSigma: Double, val atomWeight: Double, val likelihood: Double)

/** 一个候选日历区间（HPD）；并列区间都保留，不压成单一平均年。 */
@Serializable
data class CalendarInterval(
    val intervalId: String,
    val fromYear: Int,
    val toYear: Int,
    val probability: Double,
    val credibleLevel: Double,
    val rank: Int,
    val modeYear: Int,
    val modeProb: Double,
    val tiedWith: List<String> = emptyList(),
    /** 区间概率的积分证据：逐年梯形求和的记账，可展开核对。 */
    val integralEvidence: List<IntegralTerm>
) {
    val label: String get() = "${AstronomicalYear(fromYear).eraLabel()} – ${AstronomicalYear(toYear).eraLabel()}"
}

@Serializable
data class IntegralTerm(val year: Int, val density: Double, val widthYears: Double, val contributed: Double)

@Serializable
data class CalibrationRun(
    val runId: String,
    val sampleId: String,
    val sampleVersion: Int,
    val curveId: String,
    val curveVersion: String,
    val offsetId: String?,
    val offsetVersion: Int?,
    val algorithmVersion: String,
    val parameterFingerprint: String,
    val directSourceRefs: List<String>,
    val gridMinYear: Int,
    val gridMaxYear: Int,
    val bins: List<DensityBin>,
    val intervals68: List<CalendarInterval>,
    val intervals95: List<CalendarInterval>,
    val modes: List<Int>,
    val diagnostics: List<String>,
    val createdAtVirtual: Long
)

/** 固定参数指纹：同一固定曲线版本、同一偏移、同一算法 => 逐字节可重现。 */
data class FingerprintInputs(
    val sampleId: String, val sampleVersion: Int, val age14c: Double, val sigma14c: Double,
    val curveId: String, val curveVersion: String, val offsetId: String?, val offsetVersion: Int?,
    val gridMin: Int, val gridMax: Int
)

object Fingerprints {
    fun sha256Hex(text: String): String {
        val md = java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return md.joinToString("") { "%02x".format(it) }
    }

    fun of(inp: FingerprintInputs, algorithmVersion: String): String {
        val base = listOf(
            algorithmVersion, inp.sampleId, "v${inp.sampleVersion}",
            "age=%.6f".format(inp.age14c), "sig=%.6f".format(inp.sigma14c),
            inp.curveId, inp.curveVersion,
            inp.offsetId ?: "none", inp.offsetVersion?.let { "v$it" } ?: "-",
            "grid=${inp.gridMin}..${inp.gridMax}"
        ).joinToString("|")
        return sha256Hex(base).take(16)
    }

    fun runId(inp: FingerprintInputs, algorithmVersion: String): String {
        val fp = of(inp, algorithmVersion)
        return "run_${inp.sampleId}_v${inp.sampleVersion}_${fp}"
    }
}

/**
 * 校准引擎：年份网格（1 年/格），正态似然，储库偏移离散混合；
 * 曲线年份之间线性插值；超出曲线范围的格点标记诊断并排除（不静默截断成错误结果）。
 */
object CalibrationEngine {

    private fun normalLogLike(observed: Double, sigmaO: Double, mu: Double, sigmaCurve: Double, delta: Double, sigmaDelta: Double): Double {
        val mean = mu + delta
        val s2 = sigmaO * sigmaO + sigmaCurve * sigmaCurve + sigmaDelta * sigmaDelta
        val s = sqrt(s2)
        val z = (observed - mean) / s
        return -0.5 * z * z - ln(s) - 0.9189385332046727 // -0.5*ln(2*pi)
    }

    /**
     * 在 [gridMin, gridMax]（天文学年，闭区间）上计算后验密度网格。
     * 每个偏移分量单独记录贡献，保证“储库偏移分布”的每个原子都可复核。
     */
    fun density(
        sample: Sample, curve: CalibrationCurve, offset: ReservoirOffset?,
        gridMin: Int, gridMax: Int
    ): Pair<List<DensityBin>, List<String>> {
        require(sample.ready) { "sample ${sample.id} not ready for calibration" }
        require(curve.id == sample.curveId && curve.version == sample.curveVersion) {
            "curve version mismatch for sample ${sample.id}: wants ${sample.curveId}@${sample.curveVersion}"
        }
        val observed = sample.age14c.value!!
        val sigmaO = sample.sigma14c.value!!
        val diagnostics = mutableListOf<String>()

        val atoms: List<ReservoirOffset.OffsetAtom> = offset?.grid?.takeIf { it.isNotEmpty() }
            ?: listOf(ReservoirOffset.OffsetAtom(0.0, 0.0, 1.0))
        val totalAtomWeight = atoms.sumOf { it.weight }.also { require(it > 0) { "offset weights must be positive" } }

        val raw = ArrayList<Pair<Int, Double, List<AtomContribution>>>(gridMax - gridMin + 1)
        var rawSum = 0.0
        for (year in gridMin..gridMax) {
            val cp = try {
                curve.at(year)
            } catch (e: OutOfCurveRange) {
                diagnostics.add("year_out_of_curve_range:${year}")
                continue
            }
            val contribs = atoms.map { a ->
                val ll = normalLogLike(observed, sigmaO, cp.curveAge, cp.curveSigma, a.offsetYears, a.sigmaYears)
                AtomContribution(a.offsetYears, a.sigmaYears, a.weight / totalAtomWeight, exp(ll))
            }
            val w = contribs.sumOf { it.atomWeight * it.likelihood }
            raw += Triple(year, w, contribs)
            rawSum += w
        }
        if (rawSum <= 0.0) {
            diagnostics.add("zero_total_likelihood")
            return emptyList<DensityBin>() to diagnostics
        }
        val bins = raw.map { (y, w, c) -> DensityBin(y, w / rawSum, c) }
        return bins to diagnostics
    }

    /** 应用排除区间：被排除年份质量置零并重新归一化（排除本身作为来源保留）。 */
    fun applyExclusions(bins: List<DensityBin>, exclusions: List<Exclusion>): List<DensityBin> {
        if (exclusions.isEmpty()) return bins
        val killed = bins.map { bin ->
            val hit = exclusions.any { ex ->
                val lo = ex.fromYear ?: Int.MIN_VALUE; val hi = ex.toYear ?: Int.MAX_VALUE
                bin.year in lo..hi
            }
            if (hit) bin.copy(prob = 0.0) else bin
        }
        val s = killed.sumOf { it.prob }
        return if (s <= 0) killed else killed.map { it.copy(prob = it.prob / s) }
    }

    /**
     * HPD（最高密度区间）：按密度降序累加，覆盖 level 质量，再合并相邻年。
     * 多峰 => 多个区间，每个区间独立保留；并列（概率差 < tieTol）时互相登记 tiedWith。
     */
    fun hpdIntervals(bins: List<DensityBin>, level: Double, idPrefix: String, tieTol: Double = 1e-12): List<CalendarInterval> {
        val target = level
        val sorted = bins.filter { it.prob > 0 }.sortedWith(compareByDescending<DensityBin> { it.prob }.thenBy { it.year })
        val chosen = HashSet<Int>()
        var cum = 0.0
        for (b in sorted) {
            if (cum >= target) break
            chosen += b.year; cum += b.prob
        }
        val years = chosen.sorted()
        val groups = mutableListOf<MutableList<Int>>()
        for (y in years) {
            if (groups.isEmpty() || y > groups.last().last() + 1) groups += mutableListOf(y)
            else groups.last() += y
        }
        val byYear = bins.associateBy { it.year }
        val intervals = groups.mapIndexed { idx, grp ->
            val prob = grp.sumOf { byYear.getValue(it).prob }
            val modeBin = grp.map { byYear.getValue(it) }.maxWithOrNull(compareBy<DensityBin> { it.prob }.thenByDescending { it.year })!!
            val evidence = grp.map { y ->
                val d = byYear.getValue(y).prob
                IntegralTerm(y, d, 1.0, d)
            }
            CalendarInterval(
                intervalId = "${idPrefix}__hpd${(level * 100).roundToInt()}_${idx + 1}",
                fromYear = grp.first(), toYear = grp.last(),
                probability = prob, credibleLevel = level, rank = idx + 1,
                modeYear = modeBin.year, modeProb = modeBin.prob, integralEvidence = evidence
            )
        }.sortedWith(compareByDescending<CalendarInterval> { it.prob }.thenBy { it.fromYear })
            .mapIndexed { idx, ci -> ci.copy(rank = idx + 1) }
        // 并列登记
        val withTies = intervals.map { ci ->
            val ties = intervals.filter { other ->
                other.intervalId != ci.intervalId && abs(other.probability - ci.probability) <= tieTol
            }.map { it.intervalId }
            ci.copy(tiedWith = ties)
        }
        return withTies
    }

    fun modes(bins: List<DensityBin>): List<Int> {
        val out = mutableListOf<Int>()
        for (i in bins.indices) {
            val cur = bins[i].prob
            val left = if (i == 0) -1.0 else bins[i - 1].prob
            val right = if (i == bins.lastIndex) -1.0 else bins[i + 1].prob
            if (cur > left && cur >= right && cur > 0.0) out += bins[i].year
        }
        return out
    }
}
