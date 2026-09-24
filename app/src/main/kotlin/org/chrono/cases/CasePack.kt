package org.chrono.cases

import org.chrono.domain.*
import kotlin.math.sin
import kotlin.math.round

/**
 * 离线专用案例包「碳年候选席」。
 * 合成曲线 LabCal2024(a/b) 的构造是确定性的（无随机数、固定虚拟时间），
 * 既可以直接注入事件流，也导出为 resources/cases/*.json 供人工复制与审阅。
 *
 * 场景覆盖：
 *  曲线多峰、边界插值、储库偏移分布(高斯+经验混合)、BCE/CE 跨界、
 *  层序矛盾、曲线升版(局部变化)、并列日历区间。
 */
object CasePack {
    const val PROJECT_ID = "proj-carbon-dais"
    const val CURVE_ID = "LabCal2024"
    const val BASE_TIME = "2024-05-01T09:00:00Z"

    fun curveA(): CalibrationCurve {
        val pts = ArrayList<CurvePoint>()
        var y = -99
        while (y <= 300 step 5) {
            val base = 1950 - y
            val wiggle = 35 * sin((y + 99) / 18.0)
            val plateau = if (y in -10..50) (y - 20) * 0.6 else 0.0
            val age = round((base + wiggle + plateau) * 10) / 10.0
            val sig = round((25 + 8 * sin((y + 50) / 33.0)) * 10) / 10.0
            pts += CurvePoint(y, age, sig)
            y += 5
        }
        // 边界插值检查点：在最右端加入一个加密 1 年点，验证不等距网格
        pts += CurvePoint(301, round((1950 - 301) * 10) / 10.0, 24.0)
        return CalibrationCurve(CURVE_ID, "2024a", null,
            "Laboratory synthetic calibration 2024 (teaching pack)",
            pts.sortedBy { it.calYearAstro }, "cases/case-v1-import.json#curve-LabCal2024@2024a", gridStep = 1)
    }

    /** 2024b：仅在 astro 120..200 区间抬高 30 14C yr、sigma-3，用于「只影响局部依赖」的升版。 */
    fun curveB(): CalibrationCurve {
        val a = curveA()
        val pts = a.points.map { p ->
            if (p.calYearAstro in 120..200)
                p.copy(c14Age = round((p.c14Age + 30.0) * 10) / 10.0, sigma = round((p.sigma - 3.0) * 10) / 10.0)
            else p
        }
        return CalibrationCurve(CURVE_ID, "2024b", "2024a",
            "Laboratory synthetic calibration 2024b (local revision 120-200 cal)",
            pts, "cases/case-v3-local-change.json#curve-LabCal2024@2024b", gridStep = 1)
    }

    fun measurementsV1(): List<Measurement> {
        val c = "cases/case-v1-import.json"
        return listOf(
            Measurement("TER-001", 1, null, "LabX-1001", "charcoal (terrestrial)",
                Quantity(1900.0, Units.BP, "1900 +/- 30 BP"),
                Quantity(30.0, Units.BP, raw = "30"),
                CURVE_ID, "2024a", null, "$c#/measurements/TER-001"),
            Measurement("MAR-002", 1, null, "LabX-1002", "marine shell (Sakura Bay)",
                Quantity(1850.0, Units.BP, "1850 +/- 35 BP (marine)"),
                Quantity(35.0, Units.BP, raw = "35"),
                CURVE_ID, "2024a",
                ReservoirModel.Empirical(
                    listOf(
                        ReservoirModel.Empirical.OffsetSample(100.0, 30.0, 0.5),
                        ReservoirModel.Empirical.OffsetSample(150.0, 25.0, 0.5)
                    ), "$c#/reservoir/MAR-002"
                ), "$c#/measurements/MAR-002"),
            Measurement("TER-003", 1, null, "LabX-1003", "seed (terrestrial)",
                Quantity(1750.0, Units.BP, "1750 +/- 30 BP"),
                Quantity(30.0, Units.BP),
                CURVE_ID, "2024a", null, "$c#/measurements/TER-003"),
            Measurement("TER-004", 1, null, "LabX-1004", "charred grain (terrestrial)",
                Quantity(1700.0, Units.BP, "1700 +/- 30 BP"),
                Quantity(30.0, Units.BP),
                CURVE_ID, "2024a", null, "$c#/measurements/TER-004"),
            // 无法转换的原始值：留在原位置并附诊断，绝不为画图丢弃
            Measurement("BAD-005", 1, null, "LabX-1005", "unlabelled material",
                Quantity(Double.NaN, "?", raw = "n/a", diagnostic = "ERROR: numeric age unreadable 'n/a'"),
                Quantity(Double.NaN, "?", raw = "?", diagnostic = "ERROR: sigma missing"),
                CURVE_ID, "2024a", null, "$c#/measurements/BAD-005",
                diagnostics = listOf("ERROR: numeric age unreadable 'n/a'", "ERROR: sigma missing"))
        )
    }

    /** 参数修订：MAR-002 储库偏移改为高斯 mean=200/sd=40（新版本+父版本）。 */
    fun mar002Revised(): Measurement = Measurement(
        "MAR-002", 2, 1, "LabX-1002", "marine shell (Sakura Bay)",
        Quantity(1850.0, Units.BP, "1850 +/- 35 BP (marine)"),
        Quantity(35.0, Units.BP),
        CURVE_ID, "2024a",
        ReservoirModel.Gaussian(200.0, 40.0, "cases/case-v2-revision.json#/reservoir/MAR-002"),
        "cases/case-v2-revision.json#/measurements/MAR-002"
    )

    fun edges(): List<SequenceEdge> = listOf(
        SequenceEdge("SEQ-A", "TER-003", "TER-004", "stratigraphy: pit fill layer 3 below layer 2 (trench T2 log, p.44)"),
        // 故意反向：海洋样本被声明早于明显跨越 BCE/CE 的陆地炭，触发矛盾候选
        SequenceEdge("SEQ-B", "MAR-002", "TER-001", "field notebook 2024-04-18: shell lens claimed below hearth charcoal")
    )

    fun initialExclusion(): ExclusionDecision = ExclusionDecision(
        "DEC-0001", "BAD-005", true,
        "measurement unreadable in lab sheet; excluded pending re-measurement",
        BASE_TIME, "cases/case-v1-import.json#/decisions/DEC-0001"
    )

    // --- 事件包（每个阶段单独成束，导入器逐束提交，形成连续叙事）---

    fun stage1Baseline(): List<DomainEvent> {
        val t = BASE_TIME
        fun eid(k: String) = "ev-${stable(k).take(10)}"
        return listOf(
            DomainEvent.ProjectCreated(eid("create"), PROJECT_ID,
                "Sakura Bay trench T2 — carbon date dais", "astronomical years / BCE-CE (no year zero)",
                "archaeologist", t, null, "cases/case-v1-import.json#/project"),
            DomainEvent.CurveImported(eid("curveA"), PROJECT_ID, curveA(),
                "archivist", t, null, curveA().sourceRef)
        ) + measurementsV1().mapIndexed { i, m ->
            DomainEvent.MeasurementSubmitted(eid("m${m.measurementId}"), PROJECT_ID, m,
                "lab-upload", t, null, m.sourceRef)
        } + edges().map { e ->
            DomainEvent.SequenceDeclared(eid("edge${e.edgeId}"), PROJECT_ID, e,
                "field-director", t, null, e.sourceRef)
        } + DomainEvent.ExclusionRecorded(eid("dec1"), PROJECT_ID, initialExclusion(),
            "reviewer", t, null, initialExclusion().sourceRef)
    }

    fun stage2Revision(): List<DomainEvent> {
        val t = "2024-05-02T10:00:00Z"
        val m = mar002Revised()
        return listOf(DomainEvent.MeasurementRevised("ev-${stable("revMAR").take(10)}",
            PROJECT_ID, m, "chronologist", t,
            "regional reservoir parameter revision after laboratory reassessment", m.sourceRef))
    }

    fun stage3CurveUpgrade(): List<DomainEvent> {
        val t = "2024-05-03T11:00:00Z"
        val b = curveB()
        return listOf(DomainEvent.CurveUpgraded("ev-${stable("curveB").take(10)}",
            PROJECT_ID, b, "archivist", t, "curves published in local revision", b.sourceRef))
    }

    /** 互相冲突的人工决定：SEQ-B 矛盾下，否定「排除样本」，接受「仅保留较老峰(TER-001 BCE)」。 */
    fun stage4ConflictDecision(olderIntervalFrom: Double, olderIntervalTo: Double): List<DomainEvent> {
        val t = "2024-05-04T14:00:00Z"
        return listOf(
            DomainEvent.ConflictAdjudicated("ev-${stable("rejectExcl").take(10)}",
                PROJECT_ID, "SEQ-B", false, "ExcludeSample", "MAR-002",
                "{}", "shell is stratigraphically primary; contamination not demonstrated",
                "reviewer", t, "keep measurement, refuse exclusion", "ui:review#SEQ-B"),
            DomainEvent.ConflictAdjudicated("ev-${stable("acceptPeak").take(10)}",
                PROJECT_ID, "SEQ-B", true, "RestrictToOlderPeak", "TER-001",
                "[$olderIntervalFrom,$olderIntervalTo]", null,
                "reviewer", t, "retain the BCE competing peak of the hearth charcoal for this edge",
                "ui:review#SEQ-B"),
            DomainEvent.IntervalRestrictionApplied("ev-${stable("applyRestr").take(10)}",
                PROJECT_ID, "TER-001", olderIntervalFrom, olderIntervalTo, "SEQ-B",
                "reviewer", t, "accepted candidate; multimodal distribution is not averaged",
                "ui:review#SEQ-B")
        )
    }

    private fun stable(k: String): String {
        val h = org.chrono.domain.FNV1a(); h.update(k); return h.hex()
    }
}
