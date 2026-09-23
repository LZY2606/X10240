package gsb

import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.max
import kotlin.math.roundToInt

private class P2(val w: Int, val h: Int, val ml: Int, val mr: Int, val mt: Int, val mb: Int) {
    val iw get() = w - ml - mr
    val ih get() = h - mt - mb
}

fun densitySvg(d: Derived): String {
    val g = d.payload["grid"]!!.jsonObject
    val loBp = g["loBp"]!!.jsonPrimitive.int
    val hiBp = g["hiBp"]!!.jsonPrimitive.int
    val step = g["step"]!!.jsonPrimitive.int
    val density = d.payload["density"]!!.let { arr ->
        arr.jsonArray.map { it.jsonPrimitive.double }
    }
    val intervals = d.payload["intervals"]!!.jsonArray
    val p = P2(860, 300, 46, 16, 18, 40)
    val span = (hiBp - loBp).toDouble()
    fun xBp(bp: Double) = p.ml + (hiBp - bp) / span * p.iw // older on the left
    val dMax = density.max()
    fun yD(v: Double) = p.mt + p.ih - (v / dMax) * p.ih
    val sb = StringBuilder()
    sb.append("<svg width='${p.w}' height='${p.h}' viewBox='0 0 ${p.w} ${p.h}' role='img' aria-label='calibrated probability density'>")
    // shading for 95.4 and 68.2 runs
    fun shade(level: String, fill: String, opacity: Double) {
        intervals.forEachIndexed { idx, el ->
            val iv = el.jsonObject
            if (iv["level"]!!.jsonPrimitive.double.toString() == level ||
                (level == "0.682" && iv["level"]!!.jsonPrimitive.double == 0.682) ||
                (level == "0.954" && iv["level"]!!.jsonPrimitive.double == 0.954)) {
                val a = iv["loBp"]!!.jsonPrimitive.int
                val b = iv["hiBp"]!!.jsonPrimitive.int
                sb.append("<rect x='${"%.1f".format(xBp(b.toDouble()))}' y='${p.mt}' width='${"%.1f".format(xBp(a.toDouble()) - xBp(b.toDouble()))}' height='${p.ih}' fill='$fill' opacity='$opacity' data-vid='${d.derivedId}/interval$idx/shade'/>")
            }
        }
    }
    shade("0.954", "#a9c4e6", 0.35)
    shade("0.682", "#4a6fa5", 0.30)
    val pts = StringBuilder()
    density.forEachIndexed { i, v ->
        val bp = loBp + i * step
        pts.append("${"%.1f".format(xBp(bp.toDouble()))},${"%.1f".format(yD(v))} ")
    }
    sb.append("<polyline points='$pts' fill='none' stroke='#243447' stroke-width='1.4'/>")
    // peaks
    d.payload["peaks"]!!.jsonArray.forEach { el ->
        val pk = el.jsonObject
        val apex = pk["apexBp"]!!.jsonPrimitive.int
        val i = (apex - loBp) / step
        if (i in density.indices) {
            sb.append("<circle cx='${"%.1f".format(xBp(apex.toDouble()))}' cy='${"%.1f".format(yD(density[i]))}' r='3.5' fill='#a23122'/>")
        }
    }
    // axes
    sb.append("<line x1='${p.ml}' y1='${p.mt + p.ih}' x2='${p.w - p.mr}' y2='${p.mt + p.ih}' stroke='#555'/>")
    for (bp in hiBp downTo loBp step 200) {
        val x = xBp(bp.toDouble())
        sb.append("<line x1='${"%.0f".format(x)}' y1='${p.mt + p.ih}' x2='${"%.0f".format(x)}' y2='${p.mt + p.ih + 4}' stroke='#555'/>")
        sb.append("<text x='${"%.0f".format(x)}' y='${p.mt + p.ih + 16}' font-size='10' text-anchor='middle'>$bp BP</text>")
        sb.append("<text x='${"%.0f".format(x)}' y='${p.mt + p.ih + 30}' font-size='9' text-anchor='middle' fill='#666'>${Era.calBpToLabel(bp.toDouble())}</text>")
    }
    sb.append("<text x='14' y='${p.mt + p.ih / 2}' font-size='11' transform='rotate(-90 14,${p.mt + p.ih / 2})' text-anchor='middle'>归一化概率密度</text>")
    sb.append("<text x='${p.w / 2}' y='12' font-size='11' text-anchor='middle' fill='#333'>蓝块=68.2% HPD，浅蓝=95.4% HPD；红点=峰（多峰并列保留）</text>")
    sb.append("</svg>")
    return sb.toString()
}

fun curveSvg(wb: Workbench, d: Derived): String {
    val curveSrc = wb.state.sources[d.inputs["curve"]]!!
    val measSrc = wb.state.sources[d.inputs["measurement"]]!!
    val curve = wb.curves.load(curveSrc.raw["file"]!!.jsonPrimitive.content)
    val g = d.payload["grid"]!!.jsonObject
    val loBp = g["loBp"]!!.jsonPrimitive.int
    val hiBp = g["hiBp"]!!.jsonPrimitive.int
    val p = P2(860, 300, 52, 16, 22, 34)
    val span = (hiBp - loBp).toDouble()
    fun xBp(bp: Double) = p.ml + (hiBp - bp) / span * p.iw
    val seg = curve.points.filter { it.calBp in loBp..hiBp }
    val meas = measSrc.raw["c14AgeBP"]!!.jsonPrimitive.double
    val sigma = measSrc.raw["sigma"]!!.jsonPrimitive.double
    val yMin = (seg.minOf { it.c14 } - 120).coerceAtMost(meas - 2.5 * sigma)
    val yMax = (seg.maxOf { it.c14 } + 120).coerceAtLeast(meas + 2.5 * sigma)
    val ySpan = yMax - yMin
    fun y(v: Double) = p.mt + (yMax - v) / ySpan * p.ih
    val sb = StringBuilder()
    sb.append("<svg width='${p.w}' height='${p.h}' viewBox='0 0 ${p.w} ${p.h}' role='img' aria-label='calibration curve'>")
    sb.append("<rect x='${p.ml}' y='${"%.1f".format(y(meas + sigma))}' width='${p.iw}' height='${"%.1f".format(max(1.0, y(meas - sigma) - y(meas + sigma)))}' fill='#e6a05a' opacity='0.28'/>")
    sb.append("<line x1='${p.ml}' y1='${"%.1f".format(y(meas))}' x2='${p.w - p.mr}' y2='${"%.1f".format(y(meas))}' stroke='#a23122' stroke-dasharray='5 4'/>")
    val pts = seg.joinToString(" ") { "${"%.1f".format(xBp(it.calBp.toDouble()))},${"%.1f".format(y(it.c14))}" }
    sb.append("<polyline points='$pts' fill='none' stroke='#1f4e79' stroke-width='2'/>")
    sb.append("<line x1='${p.ml}' y1='${p.mt + p.ih}' x2='${p.w - p.mr}' y2='${p.mt + p.ih}' stroke='#555'/>")
    sb.append("<line x1='${p.ml}' y1='${p.mt}' x2='${p.ml}' y2='${p.mt + p.ih}' stroke='#555'/>")
    for (bp in hiBp downTo loBp step 200) {
        val x = xBp(bp.toDouble())
        sb.append("<text x='${"%.0f".format(x)}' y='${p.mt + p.ih + 16}' font-size='10' text-anchor='middle'>$bp BP</text>")
    }
    val step = (ySpan / 6).roundToInt()
    var vv = yMin.roundToInt()
    while (vv <= yMax) {
        sb.append("<text x='${p.ml - 6}' y='${"%.0f".format(y(vv.toDouble()) + 3)}' font-size='10' text-anchor='end'>$vv</text>")
        vv += step
    }
    sb.append("<text x='14' y='${p.mt + p.ih / 2}' font-size='11' transform='rotate(-90 14,${p.mt + p.ih / 2})' text-anchor='middle'>¹⁴C age BP</text>")
    sb.append("<text x='${p.w / 2}' y='12' font-size='11' text-anchor='middle'>固定曲线版本 ${esc(curve.curveId)} ${esc(curve.version)}；橙带=测量 ${meas.roundToInt()}±${sigma.roundToInt()}（红线）</text>")
    sb.append("</svg>")
    return sb.toString()
}
