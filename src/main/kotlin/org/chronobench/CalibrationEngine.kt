package org.chronobench

import kotlinx.serialization.json.Json
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * 放射性碳校准（固定版本、确定性、可重放）：
 *   L(t) = exp(-0.5 * ((M - muR - mu_curve(t)) / sqrt(sigLab^2 + sigR^2))^2)
 *   mu_curve 在 knot 之间做分段线性插值；曲线端外按端点夹取并标记 boundary。
 *   密度经梯形积分归一化；候选区间按相对谷底阈值切分，保留全部峰（不压成平均年）。
 * 算法版本写死在 CalibrationResult.ALGORITHM_VERSION；任何公式/网格变化都必须升版本。
 */
object CalibrationEngine {
    const val GRID_STEP = 10
    private const val FLOOR = 0.05           /* 相对峰值 5% 以下视为谷 */
    private const val TIE_REL_EPS = 1.0e-6

    private val json = Json { ignoreUnknownKeys = true }

    data class Inputs(
        val curve: CurvePayload,
        val curveId: String,
        val curveVersion: Int,
        val measurement: MeasurementPayload,
        val measurementVersion: Int,
        val offset: OffsetHypothesisPayload?,
        val offsetId: String?,
        val offsetVersion: Int?,
        val eraSystemId: String,
        val eraSystemVersion: Int
    )

    fun curveMuAt(curve: CurvePayload, calBP: Double): Pair<Double, Boolean> {
        val k = curve.knots.sortedBy { it.calBP }
        if (calBP <= k.first().calBP.toDouble()) return k.first().radioCarbonBP to (calBP <= k.first().calBP.toDouble())
        if (calBP >= k.last().calBP.toDouble()) return k.last().radioCarbonBP to (calBP >= k.last().calBP.toDouble())
        var i = 0
        while (i < k.size - 1 && k[i + 1].calBP < calBP) i++
        val a = k[i]; val b = k[i + 1]
        val f = (calBP - a.calBP) / (b.calBP - a.calBP)
        return a.radioCarbonBP + f * (b.radioCarbonBP - a.radioCarbonBP) to false
    }

    fun calibrate(
        projectId: String,
        inp: Inputs,
        derivationId: String,
        at: Long
    ): CalibrationResult {
        val diags = mutableListOf<String>()
        val mAge = inp.measurement.age
        val mSig = inp.measurement.sigma
        if (!mAge.convertible || !mSig.convertible) {
            diags += "测量值或标准差无法规范化，校准中止于原始位置"
        }
        val muM = mAge.value
        val sigLab = mSig.value
        val muR = inp.offset?.mean?.value ?: 0.0
        val sigR = inp.offset?.sigma?.value ?: 0.0
        if (inp.offset != null && (!inp.offset.mean.convertible || !inp.offset.sigma.convertible)) {
            diags += "储库偏移分布含无法规范化的量，偏移未参与计算"
        }
        val knots = inp.curve.knots.sortedBy { it.calBP }
        val tMin = knots.first().calBP
        val tMax = knots.last().calBP

        val points = ArrayList<DensityPoint>()
        if (muM == null || sigLab == null) {
            return aborted(projectId, inp, derivationId, at, diags, tMin, tMax, points)
        }
        val sigmaEff = sqrt(sigLab * sigLab + sigR * sigR)

        val grid = ArrayList<Int>()
        var t = tMin
        grid += t
        while (t + GRID_STEP <= tMax) { t += GRID_STEP; grid += t }
        if (grid.last() != tMax) grid += tMax

        val raw = DoubleArray(grid.size)
        for (i in grid.indices) {
            val tt = grid[i].toDouble()
            val (mu, edge) = curveMuAt(inp.curve, tt)
            val x = (muM - muR - mu) / sigmaEff
            raw[i] = exp(-0.5 * x * x)
            if (edge && raw[i] > FLOOR) diags += "likelihood 延伸到曲线端点 calBP=${grid[i]}，采用边界插值（夹取端点 knot）"
        }
        // 梯形归一化（积分常数 Z），固定网格与版本下逐位可重现。
        var z = 0.0
        for (i in 0 until grid.size - 1) {
            z += 0.5 * (raw[i] + raw[i + 1]) * (grid[i + 1] - grid[i])
        }
        if (z <= 0.0) diags += "归一化常数为 0，测量年龄超出曲线覆盖范围"

        for (i in grid.indices) {
            val tt = grid[i]
            val (mu, edge) = curveMuAt(inp.curve, tt.toDouble())
            points += DensityPoint(
                calBP = tt,
                astronomicalYear = Eras.astronomicalYear(tt),
                eraLabel = Eras.eraLabel(tt),
                interpolatedCurveBP = mu,
                likelihoodRaw = raw[i],
                density = if (z > 0) raw[i] / z else 0.0,
                boundary = edge
            )
        }

        val peak = points.maxOf { it.density }
        // 从老（calBP 大）到年轻切分区间。
        val desc = points.sortedByDescending { it.calBP }
        val intervals = ArrayList<CalibratedInterval>()
        var run = ArrayList<DensityPoint>()
        fun flush() {
            if (run.isEmpty()) return
            val start = run.maxOf { it.calBP }   // 较老端
            val end = run.minOf { it.calBP }     // 较年轻端
            val peakPt = run.maxBy { it.density }
            val mass = trapezoidMass(run)
            val id = intervalId(inp, start, end)
            intervals += CalibratedInterval(
                intervalId = id,
                rank = 0,
                startCalBP = start,
                endCalBP = end,
                startEraLabel = Eras.eraLabel(start),
                endEraLabel = Eras.eraLabel(end),
                mass = mass,
                peakCalBP = peakPt.calBP,
                peakDensity = peakPt.density
            )
            run = ArrayList()
        }
        for (p in desc) {
            if (z > 0 && p.density >= FLOOR * peak && peak > 0) run += p else flush()
        }
        flush()

        val ranked = intervals.sortedWith(compareByDescending<CalibratedInterval> { it.mass }
            .thenBy { it.startCalBP }).mapIndexed { idx, iv -> iv.copy(rank = idx + 1) }.toMutableList()

        // 并列日历区间：质量在容差内相等的等价类 -> tie group；>=2 成员才算并列。
        val tieClasses = ArrayList<MutableList<Int>>()
        for (i in ranked.indices) {
            val cls = tieClasses.firstOrNull { c -> equalMass(ranked[c[0]].mass, ranked[i].mass) }
            if (cls != null) cls += i else tieClasses += mutableListOf(i)
        }
        for (cls in tieClasses) {
            if (cls.size < 2) continue
            val anchor = cls.minOf { ranked[it].startCalBP }
            val g = "tie:" + Fingerprints.short(
                Fingerprints.of(inp.measurement.labCode, inp.curveId, inp.curveVersion.toString(), anchor.toString()))
            for (i in cls) {
                val cur = ranked[i]
                ranked[i] = cur.copy(tieGroup = g, tiedWith = cls.filter { it != i }.map { ranked[it].intervalId })
            }
        }

        val byId = ranked.associateBy { it.intervalId }
        for (idx in points.indices) {
            val p = points[idx]
            val owner = ranked.firstOrNull { p.calBP in it.endCalBP..it.startCalBP && p.density >= FLOOR * peak }
            points[idx] = p.copy(segment = owner?.intervalId)
        }

        val fp = fingerprint(inp, z)
        val sources = buildList {
            add("${inp.measurement.labCode}@v${inp.measurementVersion}")
            add("${inp.curveId}@v${inp.curveVersion}")
            add("${inp.eraSystemId}@v${inp.eraSystemVersion}")
            if (inp.offsetId != null) add("${inp.offsetId}@v${inp.offsetVersion}")
        }
        return CalibrationResult(
            derivationId = derivationId,
            projectId = projectId,
            measurementId = inp.measurement.labCode,
            measurementVersion = inp.measurementVersion,
            offsetId = inp.offsetId,
            offsetVersion = inp.offsetVersion,
            curveId = inp.curveId,
            curveVersion = inp.curveVersion,
            eraSystemId = inp.eraSystemId,
            eraSystemVersion = inp.eraSystemVersion,
            algorithmVersion = CalibrationResult.ALGORITHM_VERSION,
            paramFingerprint = fp,
            directSources = sources,
            computedAt = at,
            gridStepYears = GRID_STEP,
            curveMinCalBP = tMin,
            curveMaxCalBP = tMax,
            normalizationZ = z,
            multimodal = ranked.size > 1,
            modeCount = ranked.size,
            points = points,
            intervals = ranked,
            diagnostics = diags.distinct()
        )
    }

    private fun aborted(
        projectId: String, inp: Inputs, derivationId: String, at: Long,
        diags: MutableList<String>, tMin: Int, tMax: Int, points: ArrayList<DensityPoint>
    ): CalibrationResult = CalibrationResult(
        derivationId = derivationId, projectId = projectId,
        measurementId = inp.measurement.labCode, measurementVersion = inp.measurementVersion,
        offsetId = inp.offsetId, offsetVersion = inp.offsetVersion,
        curveId = inp.curveId, curveVersion = inp.curveVersion,
        eraSystemId = inp.eraSystemId, eraSystemVersion = inp.eraSystemVersion,
        algorithmVersion = CalibrationResult.ALGORITHM_VERSION,
        paramFingerprint = fingerprint(inp, 0.0),
        directSources = listOf("${inp.measurement.labCode}@v${inp.measurementVersion}", "${inp.curveId}@v${inp.curveVersion}"),
        computedAt = at, gridStepYears = GRID_STEP, curveMinCalBP = tMin, curveMaxCalBP = tMax,
        normalizationZ = 0.0, multimodal = false, modeCount = 0, points = points, intervals = emptyList(),
        diagnostics = diags
    )

    private fun trapezoidMass(runPts: List<DensityPoint>): Double {
        val asc = runPts.sortedBy { it.calBP }
        var m = 0.0
        for (i in 0 until asc.size - 1) {
            m += 0.5 * (asc[i].density + asc[i + 1].density) * (asc[i + 1].calBP - asc[i].calBP)
        }
        if (asc.size == 1) m = asc[0].density.toDouble()
        return m
    }

    private fun equalMass(a: Double, b: Double): Boolean {
        val scale = maxOf(1e-12, 0.5 * (kotlin.math.abs(a) + kotlin.math.abs(b)))
        return kotlin.math.abs(a - b) <= TIE_REL_EPS * scale
    }

    private fun intervalId(inp: Inputs, start: Int, end: Int): String {
        val core = Fingerprints.of(
            CalibrationResult.ALGORITHM_VERSION,
            inp.measurement.labCode, inp.measurementVersion.toString(),
            inp.curveId, inp.curveVersion.toString(),
            (inp.offsetId ?: "none"), (inp.offsetVersion ?: 0).toString(),
            GRID_STEP.toString(), FLOOR.toString(), start.toString(), end.toString()
        )
        return "I:" + Fingerprints.short(core)
    }

    private fun fingerprint(inp: Inputs, z: Double): String {
        val knotsHash = Fingerprints.of(inp.curve.knots.joinToString(",") { "${it.calBP}:${it.radioCarbonBP}:${it.sigma}" })
        val m = inp.measurement
        return Fingerprints.of(
            CalibrationResult.ALGORITHM_VERSION,
            knotsHash,
            m.labCode, inp.measurementVersion.toString(), m.age.rawValue, m.age.rawUnit, m.sigma.rawValue, m.sigma.rawUnit,
            (inp.offsetId ?: "none"), (inp.offsetVersion ?: 0).toString(),
            inp.offset?.mean?.rawValue.orEmpty(), inp.offset?.sigma?.rawValue.orEmpty(),
            GRID_STEP.toString(), FLOOR.toString(), "z=%.12g".format(z)
        )
    }
}
