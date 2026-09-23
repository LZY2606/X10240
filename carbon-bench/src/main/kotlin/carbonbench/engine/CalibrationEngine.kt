package carbonbench.engine

import carbonbench.domain.BoundaryDiagnostic
import carbonbench.domain.CalibrationCurve
import carbonbench.domain.CalibrationResult
import carbonbench.domain.GridPoint
import carbonbench.domain.HpdInterval
import carbonbench.domain.IntegralEvidence
import carbonbench.domain.IntegralSubStep
import carbonbench.domain.MeasurementVersion
import carbonbench.domain.ReservoirOffset
import carbonbench.util.CanonicalJson
import carbonbench.util.Hashing
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.exp
import kotlin.math.sqrt

data class CalibrationRequest(
    val idSeed: String,
    val projectId: String,
    val measurement: MeasurementVersion,
    val offset: ReservoirOffset?,
    val curve: CalibrationCurve,
    val gridStep: Double = 2.0,
    val seed: Long = 20260924L
)

class CalibrationEngine(private val version: String = "cal-engine-1.0.0") {

    fun algorithmVersion(): String = version

    fun interpolate(curve: CalibrationCurve, calBP: Double): Pair<Double, Double> {
        val pts = curve.points
        if (calBP <= pts.first().calBP) return pts.first().c14Age to pts.first().c14Sigma
        if (calBP >= pts.last().calBP) return pts.last().c14Age to pts.last().c14Sigma
        var lo = 0
        var hi = pts.lastIndex
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (pts[mid].calBP <= calBP) lo = mid else hi = mid
        }
        val a = pts[lo]
        val b = pts[hi]
        val f = (calBP - a.calBP) / (b.calBP - a.calBP)
        return (a.c14Age + f * (b.c14Age - a.c14Age)) to
                (a.c14Sigma + f * (b.c14Sigma - a.c14Sigma))
    }

    fun calibrate(req: CalibrationRequest, nowIso: String): CalibrationResult {
        val m = req.measurement
        val curve = req.curve
        val diagnostics = mutableListOf<BoundaryDiagnostic>()

        if (m.age.value == null || m.sigma.value == null) {
            diagnostics.add(BoundaryDiagnostic(
                "unsupported-window",
                "测量值缺少可规范化的数值，保留原始表示，未产出密度",
                null
            ))
            return emptyResult(req, diagnostics, nowIso, rawMass = 0.0)
        }
        val obs = m.age.value
        val labSigma = m.sigma.value
        if (labSigma <= 0.0) {
            diagnostics.add(BoundaryDiagnostic("unsupported-window", "实验室标准差必须为正，无法校准", null))
            return emptyResult(req, diagnostics, nowIso, rawMass = 0.0)
        }

        val components = offsetComponents(req.offset, diagnostics)
        val min = curve.minCalBP
        val max = curve.maxCalBP
        val step = req.gridStep

        val n = ((max - min) / step).toInt() + 1
        val grid = ArrayList<GridPoint>(n)
        val raw = DoubleArray(n)
        var rawSum = 0.0
        var edgeMaxRatio = 0.0
        var maxRaw = 0.0
        for (i in 0 until n) {
            val t = min + i * step
            val (mu, curveSigma) = interpolate(curve, t)
            var lik = 0.0
            for (comp in components) {
                val totalVar = labSigma * labSigma + curveSigma * curveSigma + comp.sigma * comp.sigma
                val s = sqrt(totalVar)
                val d = obs - (mu + comp.mean)
                lik += comp.weight * normalPdf(d, s)
            }
            raw[i] = lik
            if (lik > maxRaw) maxRaw = lik
            val gp = GridPoint("${req.idSeed}#g$i", t, 0.0)
            grid.add(gp)
        }

        // 梯形积分归一化
        var mass = 0.0
        for (i in 0 until n) {
            val w = trapWeight(i, n, step)
            mass += raw[i] * w
        }
        if (mass <= 0.0 || maxRaw <= 0.0) {
            diagnostics.add(BoundaryDiagnostic("unsupported-window", "全窗口似然为零，曲线版本下无支持区间", null))
            return emptyResult(req, diagnostics, nowIso, rawMass = 0.0)
        }
        for (i in 0 until n) {
            grid[i] = grid[i].copy(density = raw[i] / mass)
            rawSum += raw[i] * trapWeight(i, n, step)
        }

        // 边界裁剪诊断：曲线端点处密度仍显著 => 名义窗口超出曲线覆盖范围
        val normEdge = max(raw.first(), raw.last()) / maxRaw
        if (normEdge > 1e-3) {
            val edgeT = if (raw.first() >= raw.last()) min else max
            diagnostics.add(
                BoundaryDiagnostic(
                    "clipped-window",
                    "校准窗口在曲线边界 ${edgeT.toInt()} cal BP 处仍有显著密度（%.2e 峰值比），窗口被曲线覆盖范围裁剪".format(normEdge),
                    edgeT
                )
            )
        }
        edgeMaxRatio = normEdge

        val intervals68 = hpdIntervals(req.idSeed, grid, raw, step, mass, 0.68)
        val intervals95 = hpdIntervals(req.idSeed, grid, raw, step, mass, 0.95)
        val multimodal = intervals68.size >= 2

        val mean = run {
            var num = 0.0
            var den = 0.0
            for (i in 0 until n) {
                val w = trapWeight(i, n, step)
                num += grid[i].calBP * grid[i].density * w
                den += grid[i].density * w
            }
            num / den
        }

        val paramFp = parameterFingerprint(req, components)
        val directSources = buildList {
            add(m.id); add("curve:${curve.curveId}@${curve.version}:${curve.fingerprint}")
            if (req.offset != null) add(req.offset.id)
        }

        @Suppress("UNUSED_VARIABLE") val unused = edgeMaxRatio
        return CalibrationResult(
            id = req.idSeed,
            projectId = req.projectId,
            labCode = m.labCode,
            measurementId = m.id,
            offsetId = req.offset?.id,
            curveId = curve.curveId,
            curveVersion = curve.version,
            curveFingerprint = curve.fingerprint,
            algorithmVersion = version,
            parameterFingerprint = paramFp,
            grid = grid,
            intervals68 = intervals68,
            intervals95 = intervals95,
            diagnostics = diagnostics,
            seed = req.seed,
            rawMass = rawSum,
            normalized = true,
            diagnosticMeanCalBP = mean,
            multimodal = multimodal,
            status = "current",
            computedAt = nowIso,
            directSources = directSources
        )
    }

    private fun offsetComponents(offset: ReservoirOffset?, diagnostics: MutableList<BoundaryDiagnostic>): List<OffsetTerm> {
        if (offset == null) return listOf(OffsetTerm(1.0, 0.0, 0.0, "no-offset"))
        val positive = offset.components.filter { it.sigma >= 0.0 && it.weight > 0.0 }
        val total = positive.sumOf { it.weight }
        if (total <= 0.0) {
            diagnostics.add(BoundaryDiagnostic("unsupported-window", "偏移分布 ${offset.id} 权重全为零，退回零偏移但保留记录", null))
            return listOf(OffsetTerm(1.0, 0.0, 0.0, "no-offset"))
        }
        return positive.map { OffsetTerm(it.weight / total, it.mean, it.sigma, it.basis) }
    }

    private fun hpdIntervals(
        idSeed: String,
        grid: List<GridPoint>,
        raw: DoubleArray,
        step: Double,
        mass: Double,
        level: Double
    ): List<HpdInterval> {
        val n = grid.size
        val w = DoubleArray(n) { trapWeight(it, n, step) }
        val order = IntArray(n) { it }.sortedWith(compareByDescending<Int> { raw[it] }.thenBy { it })
        val included = BooleanArray(n)
        var acc = 0.0
        var threshold = 0.0
        for (i in order) {
            if (acc >= level) break
            included[i] = true
            threshold = raw[i]
            acc += (raw[i] / mass) * w[i]
        }
        val intervals = mutableListOf<HpdInterval>()
        var component = 0
        var i = 0
        while (i < n) {
            if (!included[i]) { i++; continue }
            var j = i
            var contrib = 0.0
            val ids = ArrayList<String>()
            val subSteps = ArrayList<IntegralSubStep>()
            while (j < n && included[j]) {
                val weight = w[j]
                val contribution = (raw[j] / mass) * weight
                contrib += contribution
                ids.add(grid[j].id)
                subSteps.add(IntegralSubStep(grid[j].id, grid[j].calBP, raw[j] / mass, weight, contribution))
                j++
            }
            val start = grid[i].calBP - step / 2.0
            val end = grid[j - 1].calBP + step / 2.0
            intervals.add(
                HpdInterval(
                    id = "$idSeed#h${(level * 100).toInt()}-$component",
                    startCalBP = start,
                    endCalBP = end,
                    level = level,
                    probability = contrib,
                    componentIndex = component,
                    integralEvidence = IntegralEvidence(
                        integration = "trapezoid",
                        gridStep = step,
                        includedGridIds = ids,
                        normalizedAgainst = "density-normalized mass=1.0 over curve domain (threshold=${"%.6g".format(threshold)})",
                        subSteps = subSteps
                    )
                )
            )
            component++
            i = j
        }
        return intervals.sortedByDescending { it.probability }.mapIndexed { idx, iv ->
            iv.copy(id = "$idSeed#h${(level * 100).toInt()}-$idx", componentIndex = idx)
        }
    }

    private fun trapWeight(i: Int, n: Int, step: Double): Double = when {
        n == 1 -> step
        i == 0 || i == n - 1 -> step / 2.0
        else -> step
    }

    private fun normalPdf(x: Double, s: Double): Double {
        val z = x / s
        return exp(-0.5 * z * z) / (s * sqrt(2.0 * Math.PI))
    }

    private fun parameterFingerprint(req: CalibrationRequest, components: List<OffsetTerm>): String {
        val m = req.measurement
        val obj = JsonObject(
            mapOf(
                "algorithm" to JsonPrimitive(version),
                "seed" to JsonPrimitive(req.seed),
                "gridStep" to JsonPrimitive(req.gridStep),
                "measurementId" to JsonPrimitive(m.id),
                "age14C" to JsonPrimitive(m.age.value),
                "sigma14C" to JsonPrimitive(m.sigma.value),
                "ageUnit" to JsonPrimitive(m.age.unit),
                "curveId" to JsonPrimitive(req.curve.curveId),
                "curveVersion" to JsonPrimitive(req.curve.version),
                "curveFingerprint" to JsonPrimitive(req.curve.fingerprint),
                "offsetId" to JsonPrimitive(req.offset?.id),
                "offsetTerms" to JsonArray(
                    components.map {
                        JsonObject(
                            mapOf(
                                "w" to JsonPrimitive(it.weight),
                                "mean" to JsonPrimitive(it.mean),
                                "sigma" to JsonPrimitive(it.sigma),
                                "basis" to JsonPrimitive(it.basis)
                            )
                        )
                    }
                )
            )
        )
        return Hashing.shortFingerprint(CanonicalJson.canonical(obj))
    }

    private fun emptyResult(
        req: CalibrationRequest,
        diagnostics: MutableList<BoundaryDiagnostic>,
        nowIso: String,
        rawMass: Double
    ) = CalibrationResult(
        id = req.idSeed,
        projectId = req.projectId,
        labCode = req.measurement.labCode,
        measurementId = req.measurement.id,
        offsetId = req.offset?.id,
        curveId = req.curve.curveId,
        curveVersion = req.curve.version,
        curveFingerprint = req.curve.fingerprint,
        algorithmVersion = version,
        parameterFingerprint = parameterFingerprint(req, listOf(OffsetTerm(1.0, 0.0, 0.0, "no-offset"))),
        grid = emptyList(),
        intervals68 = emptyList(),
        intervals95 = emptyList(),
        diagnostics = diagnostics,
        seed = req.seed,
        rawMass = rawMass,
        normalized = false,
        diagnosticMeanCalBP = null,
        multimodal = false,
        status = "current",
        computedAt = nowIso,
        directSources = listOf(req.measurement.id, "curve:${req.curve.curveId}@${req.curve.version}:${req.curve.fingerprint}")
    )

    private data class OffsetTerm(val weight: Double, val mean: Double, val sigma: Double, val basis: String)
}
