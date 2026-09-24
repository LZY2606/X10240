package gsb.rcarb.domain

import kotlinx.serialization.Serializable

/**
 * 校准曲线（固定版本、不可变）。
 *
 * knots 按 calBP 升序（即从年轻到年老）排列；曲线覆盖范围之外的似然按 0 处理
 * （不是外推），边界情形由校准引擎在证据中显式标注。
 *
 * 14C 轴：curveAge[yr BP]（放射性碳年 BP，1950 锚点，与实验室年龄同一坐标）。
 */
@Serializable
data class CalibrationCurve(
    val curveId: String,
    val version: String,
    val name: String,
    val knots: List<CurveKnot>,
    val sourceRef: SourceRef,
    val supersedes: String? = null
) {
    val calBpMin: Int get() = knots.first().calBp
    val calBpMax: Int get() = knots.last().calBp

    fun sorted(): CalibrationCurve =
        if (knots.zipWithNext().all { it.first.calBp < it.second.calBp }) this
        else copy(knots = knots.sortedBy { it.calBp })

    /**
     * 线性插值曲线 14C 年龄。返回 (mu14C, kind)；
     * kind=Exact 表示落在结点上，Linear 表示段内线性插值，Outside 表示超出覆盖范围。
     */
    fun interp(calBp: Int): Interp {
        val s = sorted()
        val arr = s.knots
        if (calBp < arr.first().calBp || calBp > arr.last().calBp) return Interp(0.0, InterpKind.Outside, null)
        var lo = 0
        var hi = arr.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (arr[mid].calBp < calBp) lo = mid + 1 else hi = mid - 1
        }
        val i = lo.coerceIn(0, arr.size - 1)
        if (arr[i].calBp == calBp) return Interp(arr[i].curveAge14c, InterpKind.Exact, i)
        val a = arr[i - 1]
        val b = arr[i]
        val f = (calBp - a.calBp).toDouble() / (b.calBp - a.calBp)
        val mu = a.curveAge14c + f * (b.curveAge14c - a.curveAge14c)
        return Interp(mu, InterpKind.Linear, i - 1)
    }

    data class Interp(val mu14c: Double, val kind: InterpKind, val knotIndex: Int?)
    enum class InterpKind { Exact, Linear, Outside }
}

@Serializable
data class CurveKnot(
    val calBp: Int,            // 日历年坐标（yr calBP，1950 锚点）
    val curveAge14c: Double,   // 曲线 14C 年龄（14C yr BP）
    val sigma14c: Double       // 曲线 1σ（14C yr）
)

@Serializable
data class SourceRef(
    val origin: String,        // 例如 "case-pack:carbon-candidates/measurement"、"lab:LAB-7"
    val detail: String = "",
    val retrievedAt: String? = null
)
