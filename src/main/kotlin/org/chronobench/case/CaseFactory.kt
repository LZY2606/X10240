package org.chronobench.case

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import org.chronobench.domain.*
import kotlin.math.exp
import kotlin.math.round

/**
 * Offline domain material for "碳年候选席：校准曲线、储库偏移与多峰年代证据".
 * The curve is produced by a fixed formula with a fixed seed and is frozen into the
 * shipped case bundle — no network access, no generic spreadsheet placeholder.
 */
object CaseFactory {
    const val CASE_ID = "carbon-candidates-2026"
    const val CASE_VERSION = "1.0.0"
    const val SEED = 20260924L

    @Serializable
    data class CaseBundle(
        val caseId: String,
        val caseVersion: String,
        val title: String,
        val seed: Long,
        val description: String,
        val curves: List<CurveDto>,
        val measurements: List<MeasurementDto>,
        val reservoirHypotheses: List<ReservoirDto>,
        val yearSystems: List<YearSystemDto>,
        val sequenceEdges: List<EdgeDto>,
        val exclusions: List<ExclusionDto>,
        val guide: List<GuideStepDto>
    )

    @Serializable data class CurveDto(
        val id: String, val version: String, val parentVersion: String? = null,
        val name: String, val origin: OriginRef, val points: List<List<Double>>
    )
    @Serializable data class MeasurementDto(
        val id: String, val labCode: String, val material: String,
        val ageRaw: String, val ageValue: Double?, val ageUnit: String,
        val sigmaRaw: String, val sigmaValue: Double?, val sigmaUnit: String,
        val origin: OriginRef, val diagnostics: List<String> = emptyList()
    )
    @Serializable data class OffsetDto(val meanBP: Double, val sigmaBP: Double, val weight: Double)
    @Serializable data class ReservoirDto(
        val id: String, val label: String, val origin: OriginRef, val components: List<OffsetDto>
    )
    @Serializable data class YearSystemDto(
        val id: String, val name: String, val scheme: String, val anchorCalBP: Int, val origin: OriginRef
    )
    @Serializable data class EdgeDto(
        val id: String, val olderMeasurementId: String, val youngerMeasurementId: String,
        val rationale: String, val origin: OriginRef
    )
    @Serializable data class ExclusionDto(
        val id: String, val targetMeasurementId: String, val reason: String, val origin: OriginRef
    )
    @Serializable data class GuideStepDto(val key: String, val title: String, val detail: String)

    private fun curveC14BP(t: Int): Double {
        val x = t.toDouble()
        val base = x + 200.0
        val wiggle =
            150.0 * exp(-((x - 1955.0) / 18.0).let { it * it }) -
            120.0 * exp(-((x - 1860.0) / 16.0).let { it * it }) +
             70.0 * exp(-((x - 1785.0) / 14.0).let { it * it })
        return round(base + wiggle)
    }

    /** v2 is a curve *revision*: parentVersion links to v1; only part of the wiggle moves. */
    private fun curveC14BPv2(t: Int): Double {
        val v1 = curveC14BP(t)
        val delta = 10.0 * exp(-((t - 1870.0) / 22.0).let { it * it })
        return round(v1 + delta)
    }

    fun buildBundle(): CaseBundle {
        val originCurve = OriginRef("台站离线曲线汇编 CB-CURVE", "cases/carbon-candidates/curve-cb-v1.csv")
        val originCurveV2 = OriginRef("台站离线曲线汇编 CB-CURVE 升版通报", "cases/carbon-candidates/curve-cb-v2.csv")
        val originLab = OriginRef("实验室测定原始登记", "cases/carbon-candidates/lab-sheet.pdf#table2")
        val originField = OriginRef("发掘记录与层位剖面", "cases/carbon-candidates/stratigraphy.md")
        val originMarine = OriginRef("现代滨海储库调查综合", "cases/carbon-candidates/reservoir-survey.json")
        val originCal = OriginRef("年代体系约定", "cases/carbon-candidates/year-systems.md")
        val originExcl = OriginRef("审阅组排除决议会签", "cases/carbon-candidates/exclusion-minutes.md")

        fun points(v2: Boolean) = (1500..2100 step 10).map { t ->
            listOf(t.toDouble(), (if (v2) curveC14BPv2(t) else curveC14BP(t)), 25.0)
        }

        return CaseBundle(
            caseId = CASE_ID,
            caseVersion = CASE_VERSION,
            title = "碳年候选席：校准曲线、储库偏移与多峰年代证据",
            seed = SEED,
            description = "离线案例包：实验室 14C 年龄 × 合成校准曲线（多峰 wiggle）× 储库偏移分布 × 层序约束，覆盖多峰、边界插值、BCE/CE 跨界、层序矛盾、曲线升版与并列日历区间。",
            curves = listOf(
                CurveDto("cb-curve", "v1", null, "台站合成校准曲线 CB v1（固定公式，seed=$SEED）", originCurve, points(false)),
                CurveDto("cb-curve", "v2", "v1", "台站合成校准曲线 CB v2（局部升版，父版本 v1）", originCurveV2, points(true))
            ),
            measurements = listOf(
                MeasurementDto("m1", "CB-1001", "炭化种子（短寿命）",
                    "2020 BP", 2020.0, "14C years BP", "25 yr", 25.0, "14C years BP", originLab),
                MeasurementDto("m2", "CB-1002", "贝壳（海生，疑似储库效应）",
                    "2260 BP", 2260.0, "14C years BP", "30 yr", 30.0, "14C years BP", originLab),
                MeasurementDto("m3", "CB-1003", "炭化木（长寿命，靠曲线端点）",
                    "2405 BP", 2405.0, "14C years BP", "35 yr", 35.0, "14C years BP", originLab),
                MeasurementDto("m4", "CB-1004", "陶片烟炱",
                    "1960 BP", 1960.0, "14C years BP", "28 yr", 28.0, "14C years BP", originLab),
                MeasurementDto("m5", "CB-1005", "骨胶原（污染存疑，原表记 >X）",
                    ">2050 BP", null, "14C years BP", "约25", null, "14C years BP",
                    originLab, listOf("UNCERTAIN_RAW: '>2050 BP' 与 '约25' 无法转换，保留原表示，不进入校准"))
            ),
            reservoirHypotheses = listOf(
                ReservoirDto("r-zero", "零偏移（陆相短寿命基准）",
                    OriginRef("方法论默认", "cases/carbon-candidates/reservoir-survey.json#zero"),
                    listOf(OffsetDto(0.0, 0.0, 1.0))),
                ReservoirDto("r-marine", "滨海储库混合分布（双峰：海湾/外海）",
                    originMarine,
                    listOf(
                        OffsetDto(250.0, 60.0, 0.6),
                        OffsetDto(420.0, 45.0, 0.4)
                    )
                ),
                ReservoirDto("r-marine-rev", "滨海储库修订版（参数修订：均值下调，父版本 r-marine）",
                    OriginRef("现代滨海储库调查综合·修订", "cases/carbon-candidates/reservoir-survey.json#rev"),
                    listOf(
                        OffsetDto(210.0, 55.0, 0.65),
                        OffsetDto(380.0, 50.0, 0.35)
                    )
                )
            ),
            yearSystems = listOf(
                YearSystemDto("ys-bp", "cal BP（BP=1950 CE）", "CAL_BP", 1950, originCal),
                YearSystemDto("ys-bcece", "proleptic Gregorian BCE/CE（无 0 年显示）", "BCE_CE", 1950, originCal)
            ),
            sequenceEdges = listOf(
                EdgeDto("e1", "m2", "m1", "L4 贝壳层下伏于 L3 炭化种子层", originField),
                EdgeDto("e2", "m3", "m2", "L5 炭化木是最下层", originField),
                EdgeDto("e3", "m1", "m4", "L3 下伏于 L2 陶片烟炱层", originField)
            ),
            exclusions = listOf(
                ExclusionDto("x1", "m5", "骨胶原污染指标超阈，原始测定仅作背景记录；来源：会签", originExcl)
            ),
            guide = listOf(
                GuideStepDto("verify_sources", "核对来源", "逐条核对原测量值与标准差、曲线版本、偏移分布、年代系统、层序约束与排除决定的来源。"),
                GuideStepDto("calibrate", "产生候选", "对每条测量在零偏移/储库假设下校准，保留全部众数与 95% 并列区间。"),
                GuideStepDto("inspect_multimodal", "多峰与 BCE/CE", "展开 CB-1001 的积分证据，确认多峰未被压成平均年，区间跨越 1 BCE/1 CE。"),
                GuideStepDto("inspect_boundary", "边界插值", "查看 CB-1003 在曲线端点的 BOUNDARY_TRUNCATED 诊断。"),
                GuideStepDto("sequence_conflict", "层序矛盾", "运行层序检查：矛盾产生候选调整，原测量不变；无法唯一决定时保留全部合法候选。"),
                GuideStepDto("decide", "人工裁定", "接受一个并列区间；对冲突选择储库假设；冲突解决与撤销都写入历史。"),
                GuideStepDto("revise_param", "参数修订", "采用储库偏移修订版（新版本+父版本），只使依赖它的结果过期。"),
                GuideStepDto("curve_upgrade", "曲线升版", "导入曲线 v2（父版本 v1），仅依赖 cb-curve 的结果局部过期；旧结果仍可查看。"),
                GuideStepDto("time_travel", "回到旧事件", "选择任意旧事件，恢复当时依赖版本与可见结论，而不覆盖历史。"),
                GuideStepDto("counter_archive", "反例档案", "依次运行七个边界探针，检查影响是否被限制在正确范围，采纳与未采纳结果均保留。"),
                GuideStepDto("export_import", "导出再导入", "导出、删除本地数据库再导入，核对身份、顺序与来源关系。")
            )
        )
    }
}
