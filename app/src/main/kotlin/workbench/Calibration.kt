package workbench

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.*

const val ALGO_CALIB = "calib-1.2.0"
const val ALGO_INTERVAL = "hpd-1.1.0"
const val ALGO_SEQ = "seqcheck-1.0.0"

@Serializable
data class CurvePoint(val calBp: Int, val c14Age: Double, val sigma: Double)

@Serializable
data class Curve(val id: String, val name: String, val points: List<CurvePoint>) {
    val minBp get() = points.first().calBp
    val maxBp get() = points.last().calBp
    init { require(points.zipWithNext().all { (a, b) -> a.calBp < b.calBp }) { "curve points must be ascending in calBp" } }

    /** 固定版本下的线性插值；越界时钳制到端点并返回诊断（边界插值情形）。 */
    fun interp(calBp: Double): Pair<Pair<Double, Double>, String?> {
        if (calBp <= minBp) return (points.first().c14Age to points.first().sigma) to
            "boundary-clamp: calBp=$calBp below curve range [${minBp}..${maxBp}], clamped to ${minBp}"
        if (calBp >= maxBp) return (points.last().c14Age to points.last().sigma) to
            "boundary-clamp: calBp=$calBp above curve range [${minBp}..${maxBp}], clamped to ${maxBp}"
        val i = points.indexOfFirst { it.calBp > calBp }
        val a = points[i - 1]; val b = points[i]
        val f = (calBp - a.calBp) / (b.calBp - a.calBp)
        return (a.c14Age + f * (b.c14Age - a.c14Age) to a.sigma + f * (b.sigma - a.sigma)) to null
    }
}

/** 年代系统：calBP ↔ 历史纪年（无 0 年）。 */
object Era {
    /** calCE = 1950 - calBP；calCE >= 1 为 CE，否则 BCE 年 = 1 - calCE。 */
    fun calBpToEra(calBp: Double): Pair<Int, String> {
        val calCE = 1950.0 - calBp
        return if (calCE >= 1.0) ceil(calCE).toInt() to "CE" else (1 - floor(calCE).toInt()) to "BCE"
    }
    fun eraToCalBp(year: Int, era: String): Double =
        if (era == "CE") 1950.0 - (year - 1) else 1950.0 + year
    fun fmt(calBp: Double): String {
        val (y, e) = calBpToEra(calBp)
        return "$y $e"
    }
}

@Serializable
data class DensityPoint(val calBp: Double, val p: Double)

@Serializable
data class CalibrationResult(
    val grid: List<DensityPoint>,      // 归一化后的概率密度（固定曲线版本下可重现）
    val step: Double,
    val norm: Double,                  // 归一化常数
    val offsetApplied: Double,         // 实际应用的储库偏移 ΔR
    val boundaryDiagnostics: List<String>,
    val mass: Double                   // 网格内总概率质量（<1 说明截断）
)

@Serializable
data class HpdInterval(
    val fromBp: Double, val toBp: Double, val mass: Double,
    val fromEra: String, val toEra: String
)

object Calibrator {
    private fun normPdf(x: Double): Double = exp(-0.5 * x * x) / sqrt(2 * PI)

    /**
     * 校准：p(t) = φ((m - ΔR - μ(t)) / sqrt(s² + σ(t)²))，网格归一化。
     * 多峰不被压成平均年；全部峰保留在 grid 中。
     */
    fun calibrate(
        c14Age: Double, c14Sigma: Double, offset: Double,
        curve: Curve, step: Double = 1.0
    ): CalibrationResult {
        val diags = mutableListOf<String>()
        val raw = ArrayList<DensityPoint>()
        var t = curve.minBp.toDouble()
        val adjusted = c14Age - offset
        while (t <= curve.maxBp) {
            val (muSig, diag) = curve.interp(t)
            if (diag != null && diags.none { it.startsWith("boundary-clamp") }) diags.add(diag)
            val (mu, sig) = muSig
            val sd = sqrt(c14Sigma * c14Sigma + sig * sig)
            raw.add(DensityPoint(t, normPdf((adjusted - mu) / sd) / sd))
            t += step
        }
        val norm = raw.sumOf { it.p } * step
        val grid = raw.map { DensityPoint(it.calBp, it.p / norm) }
        return CalibrationResult(grid, step, norm, offset, diags, norm * 1.0)
    }

    /** HPD 区间：按密度降序累积至 level，收集连续段；并列区间全部保留。 */
    fun hpd(result: CalibrationResult, level: Double = 0.954): List<HpdInterval> {
        val pts = result.grid
        val order = pts.indices.sortedByDescending { pts[it].p }
        val covered = BooleanArray(pts.size)
        var acc = 0.0
        val target = level * result.mass
        for (i in order) {
            if (acc >= target) break
            covered[i] = true
            acc += pts[i].p * result.step
        }
        val intervals = mutableListOf<HpdInterval>()
        var i = 0
        while (i < pts.size) {
            if (!covered[i]) { i++; continue }
            var j = i
            var mass = 0.0
            while (j < pts.size && covered[j]) { mass += pts[j].p * result.step; j++ }
            val from = pts[i].calBp; val to = pts[j - 1].calBp
            intervals.add(HpdInterval(from, to, mass, Era.fmt(from), Era.fmt(to)))
            i = j
        }
        return intervals.sortedBy { it.fromBp }
    }

    /** 区间积分证据：展开每个网格点的贡献，供页面展开核对。 */
    fun integrationEvidence(result: CalibrationResult, fromBp: Double, toBp: Double): JsonArray {
        var cum = 0.0
        val arr = buildJsonArray {
            for (pt in result.grid) {
                if (pt.calBp < fromBp || pt.calBp > toBp) continue
                val contrib = pt.p * result.step
                cum += contrib
                add(buildJsonObject {
                    put("calBp", pt.calBp)
                    put("era", Era.fmt(pt.calBp))
                    put("density", pt.p)
                    put("contribution", contrib)
                    put("cumulative", cum)
                })
            }
        }
        return arr
    }

    /** 储库偏移分布：离散假设集 {ΔR 值 → 权重}，每个假设产生独立候选分布。 */
    @Serializable
    data class OffsetAssumption(val offset: Double, val weight: Double, val label: String)

    fun parseOffsetDistribution(data: JsonObject): List<OffsetAssumption> {
        val arr = data["assumptions"] as JsonArray
        return arr.map { el ->
            val o = el as JsonObject
            OffsetAssumption(
                (o["offset"] as JsonPrimitive).content.toDouble(),
                (o["weight"] as JsonPrimitive).content.toDouble(),
                (o["label"] as JsonPrimitive).content
            )
        }
    }
}
