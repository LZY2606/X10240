package workbench

@DslMarker annotation class SvgDsl

object Svg {
    private const val W = 760
    private const val H = 260
    private const val ML = 52; private const val MR = 16; private const val MT = 18; private const val MB = 34

    private fun range(lo: Double, hi: Double) = if (hi > lo) hi - lo else 1.0

    /** 校准曲线 + 测量带。x=calBP（左老右年轻），y=14C age。 */
    fun curveView(curve: CurveData, measurement: Measurement?, stableId: String,
                  offset: ReservoirOffset? = null): String {
        val xLo = curve.minCalBp; val xHi = curve.maxCalBp
        val ys = curve.points.map { it.c14Age + (offset?.mean ?: 0.0) }
        val yLo = ys.min() - 120; val yHi = ys.max() + 120
        fun px(t: Double) = ML + (xHi - t) / range(xLo, xHi) * (W - ML - MR)
        fun py(v: Double) = MT + (yHi - v) / range(yLo, yHi) * (H - MT - MB)
        val pts = curve.points.joinToString(" ") {
            "%.1f,%.1f".format(px(it.calBp), py(it.c14Age + (offset?.mean ?: 0.0)))
        }
        val band = if (measurement != null) {
            val y1 = py(measurement.ageBp + measurement.sigma)
            val y0 = py(measurement.ageBp - measurement.sigma)
            """<rect data-stable-id="$stableId-meas-band" x="$ML" y="${"%.1f".format(y0)}" width="${W - ML - MR}"
               height="${"%.1f".format(y1 - y0)}" fill="#2563eb" opacity="0.12"/>
               <line data-stable-id="$stableId-meas-line" x1="$ML" x2="${W - MR}"
               y1="${"%.1f".format(py(measurement.ageBp))}" y2="${"%.1f".format(py(measurement.ageBp))}"
               stroke="#2563eb" stroke-width="1.4" stroke-dasharray="4 3"/>"""
        } else ""
        return """<svg data-stable-id="$stableId" viewBox="0 0 $W $H" class="svg-chart" role="img"
            aria-label="calibration curve ${curve.name}">
            <rect width="$W" height="$H" fill="#fff"/>
            $band
            <polyline data-stable-id="$stableId-curve" points="$pts" fill="none" stroke="#0f766e" stroke-width="2"/>
            ${axes(xLo, xHi, yLo, yHi, ::px, ::py, "calBP", "14C BP")}
            <text x="$ML" y="12" class="svg-title">${esc(curve.name)}${if (offset != null) " + ΔR" else ""}</text>
            </svg>"""
    }

    /** 校准概率密度 + 68%/95% HDR 并列区间 + 峰标记。 */
    fun densityView(res: CalibrationResult, stableId: String): String {
        if (res.points.isEmpty()) return "<svg data-stable-id=\"$stableId\"><text x='10' y='20'>unknown: ${esc(res.unknownReason ?: "")}</text></svg>"
        val xLo = res.points.first().calBp; val xHi = res.points.last().calBp
        val yMax = res.points.maxOf { it.norm } * 1.12
        fun px(t: Double) = ML + (xHi - t) / range(xLo, xHi) * (W - ML - MR)
        fun py(v: Double) = MT + (yMax - v) / range(0.0, yMax) * (H - MT - MB)
        val densityPts = res.points.joinToString(" ") { "%.1f,%.1f".format(px(it.calBp), py(it.norm)) }
        val close = listOf("%.1f,%.1f".format(px(res.points.last().calBp), py(0.0)),
            "%.1f,%.1f".format(px(res.points.first().calBp), py(0.0))).joinToString(" ")
        val rects95 = res.intervals95.joinToString("\n") { iv ->
            val x = px(iv.hiCalBp); val w = px(iv.loCalBp) - px(iv.hiCalBp)
            """<rect data-stable-id="$stableId-i95-${iv.loCalBp.toInt()}" x="${"%.1f".format(x)}" y="$MT"
            width="${"%.1f".format(w)}" height="${H - MT - MB}" fill="#f59e0b" opacity="0.15"/>"""
        }
        val rects68 = res.intervals68.joinToString("\n") { iv ->
            val x = px(iv.hiCalBp); val w = px(iv.loCalBp) - px(iv.hiCalBp)
            """<rect data-stable-id="$stableId-i68-${iv.loCalBp.toInt()}" x="${"%.1f".format(x)}" y="$MT"
            width="${"%.1f".format(w)}" height="${H - MT - MB}" fill="#f59e0b" opacity="0.30"/>"""
        }
        val peaks = res.peaks.joinToString("\n") { pk ->
            """<circle data-stable-id="$stableId-peak-${pk.calBp.toInt()}" cx="${"%.1f".format(px(pk.calBp))}"
            cy="${"%.1f".format(py(pk.density))}" r="3.5" fill="#b91c1c"/>"""
        }
        val poly = """<polygon data-stable-id="$stableId-density" points="$densityPts $close" fill="#0f766e" opacity="0.22"/>
            <polyline points="$densityPts" fill="none" stroke="#0f766e" stroke-width="1.6"/>"""
        return """<svg data-stable-id="$stableId" viewBox="0 0 $W $H" class="svg-chart" role="img"
            aria-label="calibrated probability density">
            <rect width="$W" height="$H" fill="#fff"/>
            $rects95$rects68$poly$peaks
            ${axes(xLo, xHi, 0.0, yMax, ::px, ::py, "calBP", "p (normalized)")}
            <text x="$ML" y="12" class="svg-title">校准密度（深橙=68.2% HDR，浅橙=95.4% HDR，红点=峰；不做平均年压缩）</text>
            </svg>"""
    }

    /** 层序关系：两个样品盒 + 老→年轻箭头；冲突时红色。 */
    fun sequenceView(before: String, after: String, conflict: Boolean, prob: Double, stableId: String): String {
        val color = if (conflict) "#b91c1c" else "#0f766e"
        val label = if (prob.isNaN()) "P=unknown" else "P(before older)=${"%.3f".format(prob)}"
        return """<svg data-stable-id="$stableId" viewBox="0 0 760 130" class="svg-chart" role="img"
            aria-label="stratigraphic sequence">
            <rect x="40" y="30" width="240" height="64" rx="8" fill="#fff" stroke="$color" stroke-width="2"/>
            <text x="160" y="58" text-anchor="middle" class="svg-box">${esc(before)}（应较早/older）</text>
            <text x="160" y="80" text-anchor="middle" class="svg-sub">$label</text>
            <line x1="284" y1="62" x2="468" y2="62" stroke="$color" stroke-width="2.5"
            marker-end="url(#arrow-$stableId)"/>
            <text x="376" y="50" text-anchor="middle" class="svg-sub">older → younger</text>
            <rect x="472" y="30" width="240" height="64" rx="8" fill="#fff" stroke="$color" stroke-width="2"/>
            <text x="592" y="58" text-anchor="middle" class="svg-box">${esc(after)}（应较年轻/younger）</text>
            <text x="592" y="80" text-anchor="middle" class="svg-sub">${if (conflict) "层序冲突 → 候选调整" else "一致"}</text>
            <defs><marker id="arrow-$stableId" markerWidth="10" markerHeight="10" refX="8" refY="3"
            orient="auto"><path d="M0,0 L8,3 L0,6 Z" fill="$color"/></marker></defs>
            </svg>"""
    }

    private fun axes(
        xLo: Double, xHi: Double, yLo: Double, yHi: Double,
        px: (Double) -> Double, py: (Double) -> Double, xLabel: String, yLabel: String
    ): String {
        fun ticks(lo: Double, hi: Double, n: Int): List<Double> {
            val step = ((hi - lo) / n)
            return (0..n).map { lo + it * step }
        }
        val xt = ticks(xLo, xHi, 5).joinToString("\n") { v ->
            val x = px(v)
            """<line x1="${"%.1f".format(x)}" y2="${H - MB}" x2="${"%.1f".format(x)}" y1="${H - MB + 4}" stroke="#666"/>
            <text x="${"%.1f".format(x)}" y="${H - MB + 16}" text-anchor="middle" class="svg-axis">${v.toInt()}</text>"""
        }
        val yt = ticks(yLo, yHi, 4).joinToString("\n") { v ->
            val y = py(v)
            """<line x1="${ML - 4}" y2="${"%.1f".format(y)}" x2="$ML" y1="${"%.1f".format(y)}" stroke="#666"/>
            <text x="${ML - 7}" y="${"%.1f".format(y + 3.5)}" text-anchor="end" class="svg-axis">${v.toInt()}</text>"""
        }
        return """$xt$yt
            <line x1="$ML" y1="$MT" x2="$ML" y2="${H - MB}" stroke="#444"/>
            <line x1="$ML" y1="${H - MB}" x2="${W - MR}" y2="${H - MB}" stroke="#444"/>
            <text x="${W - MR}" y="${H - MB - 6}" text-anchor="end" class="svg-axis">$xLabel</text>
            <text x="14" y="$MT" class="svg-axis">$yLabel</text>"""
    }
}

fun esc(s: String): String =
    s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
