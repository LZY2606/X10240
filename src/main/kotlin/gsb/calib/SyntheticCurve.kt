package gsb.calib

import gsb.model.CurvePoint
import gsb.model.CurvePayload
import kotlin.math.*

/**
 * 离线合成校准曲线“GSB-FAKE”。非通用表格，而是专门构造用于
 * 多峰、BCE/CE 跨界、并列日历区间与边界插值情形的领域材料。
 * 坐标：cal BP（BP1950），逐年采样。
 *
 * 通过给 c14Bp 加入“台地 + 折返（wiggle/plateau）”，使一个 14C 年龄
 * 在多个日历年代上同时命中 -> 校准后多峰。
 */
object SyntheticCurve {

    private fun wiggle(t: Double): Double {
        // t = cal BP。台地 + 周期折返，制造近水平段（多峰）与跨越 cal BP 1950 的结构。
        val plateau = 180.0 * exp(-((t - 1980.0) * (t - 1980.0)) / (2.0 * 120.0 * 120.0))
        val osc = 55.0 * sin((t - 1900.0) / 26.0)
        val slow = 0.9 * (t - 2200.0)
        return slow + plateau + osc
    }

    private fun build(version: String, shift: Double, sigmaBase: Double, supersedes: String?): CurvePayload {
        // 覆盖 1600 BCE..400 CE：cal BP 3550 .. 1550
        val startBp = 3550
        val endBp = 1550
        val pts = ArrayList<CurvePoint>(startBp - endBp + 1)
        var b = startBp
        while (b >= endBp) {
            val c14 = b + wiggle(b.toDouble()) + shift
            val sig = sigmaBase + 4.0 * (1.0 + sin(b / 53.0))
            pts += CurvePoint(b.toDouble(), c14, sig)
            b -= 1
        }
        return CurvePayload(
            curveId = "GSB-FAKE",
            version = version,
            basis = "cal BP / BP1950 (synthetic offline fixture)",
            points = pts,
            supersedes = supersedes
        )
    }

    fun v1(): CurvePayload = build("v1", shift = 0.0, sigmaBase = 14.0, supersedes = null)
    /** v2：曲线升版——wiggle 相位/幅度微调 + 偏移，使同一测量校准结果发生可见但局部的变化。 */
    fun v2(): CurvePayload = build("v2", shift = 18.0, sigmaBase = 13.0, supersedes = "v1")
}
