package workbench

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 曲线资源（离线内嵌，不访问公网）。 */
object Curves {
    fun load(version: String): Pair<Curve, String> {
        val path = "/curves/labcal-$version.json"
        val text = Curves::class.java.getResourceAsStream(path)?.bufferedReader()?.readText()
            ?: error("curve resource missing: $path")
        val obj = JsonFmt.parseToJsonElement(text) as JsonObject
        val pts = (obj["points"] as kotlinx.serialization.json.JsonArray).map {
            val o = it as JsonObject
            CurvePoint(
                (o["calBp"] as JsonPrimitive).content.toInt(),
                (o["c14Age"] as JsonPrimitive).content.toDouble(),
                (o["sigma"] as JsonPrimitive).content.toDouble()
            )
        }
        return Curve("labcal-$version", "LabCal-Sample $version", pts) to text
    }
}

/** 对一次测量 + 一个偏移假设执行校准并写入派生事件。 */
fun runCalibration(
    store: Store, m: SourceRecord, curveSrc: SourceRecord, curve: Curve,
    offset: Double, offsetLabel: String, offsetSrc: SourceRecord, eraSrc: SourceRecord
): Pair<DerivedRecord, CalibrationResult> {
    val md = m.data
    val age = (md["c14Age"] as JsonPrimitive).content.toDouble()
    val sigma = (md["c14Sigma"] as JsonPrimitive).content.toDouble()
    val res = Calibrator.calibrate(age, sigma, offset, curve)
    val intervals = Calibrator.hpd(res)
    val payload = buildJsonObject {
        put("measurement", m.id); put("curve", curveSrc.id); put("curveName", curve.name)
        put("offsetLabel", offsetLabel); put("offsetApplied", offset)
        put("step", res.step); put("norm", res.norm); put("mass", res.mass)
        put("boundaryDiagnostics", buildJsonArray { res.boundaryDiagnostics.forEach { add(JsonPrimitive(it)) } })
        put("intervals", buildJsonArray {
            intervals.forEach { iv ->
                add(buildJsonObject {
                    put("fromBp", iv.fromBp); put("toBp", iv.toBp); put("mass", iv.mass)
                    put("fromEra", iv.fromEra); put("toEra", iv.toEra)
                })
            }
        })
        put("grid", buildJsonArray {
            res.grid.forEach { p -> add(buildJsonObject { put("calBp", p.calBp); put("p", p.p) }) }
        })
    }
    val d = store.computeDerived("calibration", ALGO_CALIB,
        listOf(m, curveSrc, offsetSrc, eraSrc), payload)
    return d to res
}

fun derivedGrid(d: DerivedRecord): CalibrationResult {
    val p = d.payload
    val grid = (p["grid"] as kotlinx.serialization.json.JsonArray).map {
        val o = it as JsonObject
        DensityPoint((o["calBp"] as JsonPrimitive).content.toDouble(), (o["p"] as JsonPrimitive).content.toDouble())
    }
    val diags = (p["boundaryDiagnostics"] as kotlinx.serialization.json.JsonArray).map { (it as JsonPrimitive).content }
    return CalibrationResult(
        grid, (p["step"] as JsonPrimitive).content.toDouble(),
        (p["norm"] as JsonPrimitive).content.toDouble(),
        (p["offsetApplied"] as JsonPrimitive).content.toDouble(), diags,
        (p["mass"] as JsonPrimitive).content.toDouble()
    )
}

fun derivedIntervals(d: DerivedRecord): List<HpdInterval> =
    (d.payload["intervals"] as kotlinx.serialization.json.JsonArray).map {
        val o = it as JsonObject
        HpdInterval(
            (o["fromBp"] as JsonPrimitive).content.toDouble(),
            (o["toBp"] as JsonPrimitive).content.toDouble(),
            (o["mass"] as JsonPrimitive).content.toDouble(),
            (o["fromEra"] as JsonPrimitive).content,
            (o["toEra"] as JsonPrimitive).content
        )
    }

/** 层序检查：约束要求 older 的 calBp 分布应整体偏老；冲突时产生候选调整，不改写测量。 */
fun runSequenceCheck(store: Store, seqSrc: SourceRecord, calibs: Map<String, DerivedRecord>): Pair<ConflictRecord?, DerivedRecord> {
    val order = (seqSrc.data["order"] as kotlinx.serialization.json.JsonArray).map { (it as JsonPrimitive).content }
    val findings = buildJsonArray {
        for (i in 0 until order.size - 1) {
            val a = calibs[order[i]]; val b = calibs[order[i + 1]] ?: continue
            if (a == null) continue
            val ia = derivedIntervals(a); val ib = derivedIntervals(b)
            if (ia.isEmpty() || ib.isEmpty()) continue
            // 层序要求 a 老于 b：a 的 calBp 应 >= b 的 calBp
            val violation = ia.maxOf { it.toBp } < ib.minOf { it.fromBp }
            add(buildJsonObject {
                put("older", order[i]); put("younger", order[i + 1])
                put("olderIntervals", ia.size); put("youngerIntervals", ib.size)
                put("violation", violation)
            })
        }
    }
    val refs = (calibs.values.flatMap { it.sources } + "${seqSrc.id}@${seqSrc.version}").distinct()
    val d = store.computeDerivedByRefs("sequence-check", ALGO_SEQ, refs,
        buildJsonObject { put("order", buildJsonArray { order.forEach { add(JsonPrimitive(it)) } }); put("findings", findings) })

    val violations = findings.filter { (it as JsonObject)["violation"].let { v -> (v as JsonPrimitive).content == "true" } }
    var conflict: ConflictRecord? = null
    if (violations.isNotEmpty()) {
        // 候选调整：对每对冲突，给出全部合法候选（截断到重叠区 / 保留并列区间），不改写测量
        val candidates = mutableListOf<JsonObject>()
        for (i in 0 until order.size - 1) {
            val a = calibs[order[i]] ?: continue; val b = calibs[order[i + 1]] ?: continue
            val ia = derivedIntervals(a); val ib = derivedIntervals(b)
            if (ia.isEmpty() || ib.isEmpty()) continue
            if (ia.maxOf { it.toBp } < ib.minOf { it.fromBp }) {
                candidates.add(buildJsonObject {
                    put("kind", "truncate-older-to-overlap")
                    put("measurement", order[i])
                    put("note", "候选A：仅保留 ${order[i]} 分布中老于 ${order[i + 1]} 最老端的概率质量；测量值不变")
                })
                candidates.add(buildJsonObject {
                    put("kind", "keep-parallel-intervals")
                    put("measurement", order[i])
                    put("note", "候选B：保留全部并列日历区间，标记层序待人工裁定；测量值不变")
                })
            }
        }
        conflict = store.raiseConflict("sequence-order",
            order.filter { calibs.containsKey(it) }.map { calibs[it]!!.id }, candidates)
    }
    return conflict to d
}

/** 离线案例包：碳年候选席——校准曲线、储库偏移与多峰年代证据。 */
object CasePack {
    const val TITLE = "碳年候选席：校准曲线、储库偏移与多峰年代证据"

    fun load(store: Store): Map<String, String> {
        val ids = mutableMapOf<String, String>()

        // —— 基线：六类来源各自有出处 ——
        val (curveV1, curveRaw) = Curves.load("v1")
        val curveSrc = store.registerSource("curve", "LabCal-Sample v1（本仓库内嵌离线曲线）",
            curveRaw, buildJsonObject {
                put("name", curveV1.name); put("minBp", curveV1.minBp); put("maxBp", curveV1.maxBp)
                put("points", curveV1.points.size)
            })
        ids["curveV1"] = curveSrc.id

        val eraSrc = store.registerSource("era-system", "历史纪年约定（无 0 年：1 BCE 之后为 1 CE）",
            "calCE = 1950 - calBP; no year zero", buildJsonObject {
                put("datum", "historical-era(no-year-zero)"); put("epoch", "1950")
            })
        ids["era"] = eraSrc.id

        val m1 = store.registerSource("measurement", "实验室编号 Lab-A-101（加速器质谱）",
            "2280 ± 30 BP", buildJsonObject {
                put("c14Age", 2280.0); put("c14Sigma", 30.0); put("unit", "BP"); put("datum", "calBP(1950)")
                put("raw", "2280 ± 30 BP")
            })
        ids["m1"] = m1.id

        val mMarine = store.registerSource("measurement", "实验室编号 Lab-M-207（海洋贝壳）",
            "3000 ± 35 BP (marine shell)", buildJsonObject {
                put("c14Age", 3000.0); put("c14Sigma", 35.0); put("unit", "BP"); put("datum", "calBP(1950)")
                put("material", "marine-shell"); put("raw", "3000 ± 35 BP (marine shell)")
            })
        ids["mMarine"] = mMarine.id

        val mBoundary = store.registerSource("measurement", "实验室编号 Lab-B-330（深部炭屑）",
            "6150 ± 40 BP", buildJsonObject {
                put("c14Age", 6150.0); put("c14Sigma", 40.0); put("unit", "BP"); put("datum", "calBP(1950)")
                put("raw", "6150 ± 40 BP")
            })
        ids["mBoundary"] = mBoundary.id

        val mCross = store.registerSource("measurement", "实验室编号 Lab-C-412（居址木炭）",
            "1990 ± 40 BP", buildJsonObject {
                put("c14Age", 1990.0); put("c14Sigma", 40.0); put("unit", "BP"); put("datum", "calBP(1950)")
                put("raw", "1990 ± 40 BP")
            })
        ids["mCross"] = mCross.id

        val offsetSrc = store.registerSource("offset-dist", "区域海洋储库数据集 R-2024（ΔR 离散分布）",
            "ΔR ∈ {0 (w0.2), 150 (w0.5), 300 (w0.3)}", buildJsonObject {
                put("assumptions", buildJsonArray {
                    add(buildJsonObject { put("offset", 0.0); put("weight", 0.2); put("label", "无储库校正") })
                    add(buildJsonObject { put("offset", 150.0); put("weight", 0.5); put("label", "区域均值 ΔR=150") })
                    add(buildJsonObject { put("offset", 300.0); put("weight", 0.3); put("label", "保守上限 ΔR=300") })
                })
            })
        ids["offset"] = offsetSrc.id

        val seqSrc = store.registerSource("sequence", "发掘记录 Trench-4 层位关系（下老上新）",
            "Layer3(Lab-A-101) 早于 Layer1(Lab-C-412)", buildJsonObject {
                put("order", buildJsonArray { add(JsonPrimitive(m1.id)); add(JsonPrimitive(mCross.id)) })
                put("basis", "stratigraphic-trench-4")
            })
        ids["seq"] = seqSrc.id

        val exclSrc = store.registerSource("exclusion", "审阅员 K. 初筛记录",
            "排除 Lab-M-207 的硬水效应怀疑之外的污染假设", buildJsonObject {
                put("excluded", "contamination-hypothesis-2"); put("appliesTo", mMarine.id)
            })
        ids["excl"] = exclSrc.id

        // —— 初始校准（曲线多峰 / 边界插值 / 储库偏移分布 / BCE-CE 跨界）——
        val zeroOffset = offsetSrc // 陆地样默认 ΔR=0，仍引用偏移分布来源
        val (cal1, _) = runCalibration(store, m1, curveSrc, curveV1, 0.0, "陆地样 ΔR=0", zeroOffset, eraSrc)
        ids["cal1"] = cal1.id
        val (calB, _) = runCalibration(store, mBoundary, curveSrc, curveV1, 0.0, "陆地样 ΔR=0", zeroOffset, eraSrc)
        ids["calBoundary"] = calB.id
        val (calX, _) = runCalibration(store, mCross, curveSrc, curveV1, 0.0, "陆地样 ΔR=0", zeroOffset, eraSrc)
        ids["calCross"] = calX.id

        // 储库偏移分布：每个假设独立候选，并列保留
        val assumptions = Calibrator.parseOffsetDistribution(offsetSrc.data)
        val marineCalibs = assumptions.map { a ->
            runCalibration(store, mMarine, curveSrc, curveV1, a.offset, a.label, offsetSrc, eraSrc).first
        }
        ids["calMarine"] = marineCalibs.joinToString(",") { it.id }

        // 层序检查（层序矛盾 → 候选调整，不改写测量）
        val (conflict, seqCheck) = runSequenceCheck(store, seqSrc,
            mapOf(m1.id to cal1, mCross.id to calX))
        ids["seqCheck"] = seqCheck.id
        if (conflict != null) ids["conflict"] = conflict.id

        // —— 一次参数修订：偏移分布修订（只影响海洋样派生）——
        val offsetV2 = store.reviseSource(offsetSrc.id,
            "ΔR ∈ {0 (w0.15), 180 (w0.55), 320 (w0.3)}（2025 年区域数据更新）",
            buildJsonObject {
                put("assumptions", buildJsonArray {
                    add(buildJsonObject { put("offset", 0.0); put("weight", 0.15); put("label", "无储库校正") })
                    add(buildJsonObject { put("offset", 180.0); put("weight", 0.55); put("label", "修订均值 ΔR=180") })
                    add(buildJsonObject { put("offset", 320.0); put("weight", 0.3); put("label", "修订上限 ΔR=320") })
                })
            })
        ids["offsetV2"] = offsetV2.id

        // —— 一次只影响局部依赖的源数据改动：m1 测量值修订 ——
        val m1v2 = store.reviseSource(m1.id, "2295 ± 28 BP（实验室复检）",
            buildJsonObject {
                put("c14Age", 2295.0); put("c14Sigma", 28.0); put("unit", "BP"); put("datum", "calBP(1950)")
                put("raw", "2295 ± 28 BP（实验室复检）")
            })
        ids["m1v2"] = m1v2.id

        // —— 曲线升版：v2 作为 v1 的修订 ——
        val (curveV2, curveV2Raw) = Curves.load("v2")
        val curveSrcV2 = store.reviseSource(curveSrc.id, curveV2Raw, buildJsonObject {
            put("name", curveV2.name); put("minBp", curveV2.minBp); put("maxBp", curveV2.maxBp)
            put("points", curveV2.points.size)
        })
        ids["curveV2"] = curveSrcV2.id
        // 升版后用 v2 重算 m1（旧结果保留但过期）
        val (cal1v2, _) = runCalibration(store, m1v2, curveSrcV2, curveV2, 0.0, "陆地样 ΔR=0", offsetV2, eraSrc)
        ids["cal1v2"] = cal1v2.id

        // —— 互相冲突的人工决定 ——
        val dec1 = store.recordDecision("accept", cal1v2.id, "审阅员甲：接受 95.4% 并列区间作为报告候选")
        ids["decAccept"] = dec1.id
        val dec2 = store.recordDecision("reject", cal1v2.id, "审阅员乙：曲线升版影响未评估，暂不采纳")
        ids["decReject"] = dec2.id
        val decConflict = store.raiseConflict("decision-disagreement",
            listOf(dec1.id, dec2.id), listOf(
                buildJsonObject { put("kind", "accept-then-reject"); put("note", "候选C：以接受为准，记录反对意见") },
                buildJsonObject { put("kind", "reject-then-accept"); put("note", "候选D：以否定为准，待升版评估后复审") }
            ))
        ids["decConflict"] = decConflict.id

        return ids
    }
}
