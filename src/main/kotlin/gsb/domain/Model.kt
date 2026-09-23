package gsb.domain

import kotlinx.serialization.Serializable

/**
 * 领域核心模型。
 *
 * 约定：内部所有“时间”统一使用天文年（astronomical / ISO 年）表示：
 *   - 公元 200 CE = 200
 *   - 公元 1 CE = 1
 *   - 公元前 1 BCE = 0
 *   - 公元前 2000 BCE = -1999
 * 曲线点与放射性碳年龄单位为 14C BP（距今 1950）。
 */

/** 量纲标签，确保领域量携带单位或坐标基准。 */
@Serializable
enum class UnitTag(val symbol: String, val kind: String) {
    C14_BP("14C BP", "radiocarbon_age"),
    CAL_BP("cal BP", "calendar_before_1950"),
    YEAR_ASTRONOMICAL("astronomical year (1 BCE=0)", "calendar_year"),
    YEARS("years", "duration"),
    DELTA_R("14C years (reservoir Delta-R)", "reservoir_offset")
}

/**
 * 带原始表示的值。raw 是导入时逐字保留的文本；normalized 是派生层规范化结果。
 * 无法转换时 normalized=null 并带诊断，原值仍留在原处。
 */
@Serializable
data class RawValue(
    val raw: String,
    val normalized: Double? = null,
    val unit: String? = null,
    val diagnostic: String? = null
) {
    val isUsable: Boolean get() = normalized != null && diagnostic == null
}

@Serializable
data class SourceRef(
    val origin: String,            // 例如 "lab-sheet:A12 / case-v1"
    val retrievedAt: String,       // ISO-8601 虚拟时间
    val note: String = ""
)

/** 校准曲线的一个点。 */
@Serializable
data class CurvePoint(
    val calBp: Int,                // 日历年 BP（距 1950）
    val c14Bp: Double,             // 曲线放射性碳年龄
    val sigma: Double,
    val source: SourceRef
) {
    fun astronomicalYear(): Int = 1950 - calBp
}

@Serializable
data class CalibrationCurve(
    val id: String,
    val label: String,            // IntCal20-like 显示名
    val version: String,
    val points: List<CurvePoint>,
    val source: SourceRef,
    val supersedes: String? = null
) {
    val minCalBp: Int get() = points.minOf { it.calBp }
    val maxCalBp: Int get() = points.maxOf { it.calBp }

    /** 线性插值（固定曲线版本下可重现）。calBp 落在数据范围外返回 null 并由调用方记录边界诊断。 */
    fun interpolate(calBp: Double): InterpResult? {
        if (points.isEmpty()) return null
        if (calBp < minCalBp || calBp > maxCalBp) return null
        var lo = 0
        var hi = points.size - 1
        if (calBp <= points.first().calBp.toDouble()) {
            val p = points.first()
            return InterpResult(p.c14Bp, p.sigma, BoundaryKind.AT_MIN, calBp)
        }
        if (calBp >= points.last().calBp.toDouble()) {
            val p = points.last()
            return InterpResult(p.c14Bp, p.sigma, BoundaryKind.AT_MAX, calBp)
        }
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (points[mid].calBp <= calBp) lo = mid else hi = mid
        }
        val a = points[lo]
        val b = points[hi]
        val span = (b.calBp - a.calBp).toDouble()
        val t = if (span == 0.0) 0.0 else (calBp - a.calBp) / span
        return InterpResult(
            c14 = a.c14Bp + t * (b.c14Bp - a.c14Bp),
            sigma = a.sigma + t * (b.sigma - a.sigma),
            boundary = BoundaryKind.INTERIOR,
            calBp = calBp,
            segmentLo = a.calBp,
            segmentHi = b.calBp
        )
    }
}

enum class BoundaryKind { INTERIOR, AT_MIN, AT_MAX }

data class InterpResult(
    val c14: Double,
    val sigma: Double,
    val boundary: BoundaryKind,
    val calBp: Double,
    val segmentLo: Int? = null,
    val segmentHi: Int? = null
)

/** 储库偏移假设：Delta-R 均值与标准差（单位 14C years）。 */
@Serializable
data class ReservoirAssumption(
    val id: String,
    val label: String,
    val deltaR: RawValue,
    val deltaRSigma: RawValue,
    val source: SourceRef,
    val parentId: String? = null,
    val retired: Boolean = false
)

/** 原测量值一经提交不可覆盖；修订以新版本 + 父版本关系表达。 */
@Serializable
data class Measurement(
    val id: String,
    val sampleId: String,
    val labCode: String,
    val c14Age: RawValue,            // 14C BP
    val c14Sigma: RawValue,          // 14C years
    val material: String,
    val reservoirId: String?,
    val eraSystem: String,           // BCE/CE + astronomical conversion policy id
    val source: SourceRef,
    val parentId: String? = null,
    val retired: Boolean = false
)

@Serializable
data class SequenceMember(
    val sampleId: String,
    val order: Int                   // 0 = 最老（下层）
)

/** 层序约束：members 按 order 递增排列，要求天文年严格递减（下老上新）。 */
@Serializable
data class SequenceConstraint(
    val id: String,
    val label: String,
    val members: List<SequenceMember>,
    val source: SourceRef,
    val parentId: String? = null,
    val retired: Boolean = false
)

/** 排除决定：某区间或样品被人工排除，原因是一等数据。 */
@Serializable
data class ExclusionDecision(
    val id: String,
    val sampleId: String,
    val targetKind: String,         // SAMPLE | INTERVAL
    val intervalKey: String?,       // 例如 HPD68#2
    val reason: String,
    val source: SourceRef,
    val parentId: String? = null,
    val retired: Boolean = false
)

/** 日历年区间（天文年坐标，含显示标签）。 */
@Serializable
data class CalendarInterval(
    val key: String,
    val fromAstronomical: Int,
    val toAstronomical: Int,
    val probability: Double,
    val integralEvidenceId: String,
    val rejected: Boolean = false,
    val rejectReason: String? = null
)

/** 积分证据：某区间概率的逐步积分记录。 */
@Serializable
data class IntegralEvidence(
    val id: String,
    val runId: String,
    val intervalKey: String,
    val gridStepYears: Int,
    val gridFromCalBp: Int,
    val gridToCalBp: Int,
    val unnormalizedMass: Double,
    val totalMass: Double,
    val probability: Double,
    val quadratureNodes: Int,
    val formula: String
)

@Serializable
data class CalibrationRunResult(
    val runId: String,
    val measurementId: String,
    val sampleId: String,
    val curveId: String,
    val curveVersion: String,
    val reservoirId: String?,
    val algorithmVersion: String,
    val paramFingerprint: String,
    val directSources: List<String>,
    val createdAt: String,
    val gridStepYears: Int,
    val gridFromCalBp: Int,
    val gridToCalBp: Int,
    /** 归一化后的密度数组（按 calBp 递增，即天文年递减）。 */
    val density: List<Double>,
    val hpd68: List<CalendarInterval>,
    val hpd95: List<CalendarInterval>,
    val integralEvidence: List<IntegralEvidence>,
    val boundaryHits: List<String>,
    val multiModal: Boolean,
    val componentCount: Int,
    val diagnostics: List<String>
)

@Serializable
data class SequenceEvalResult(
    val evalId: String,
    val constraintId: String,
    val runIds: List<String>,
    val algorithmVersion: String,
    val paramFingerprint: String,
    val createdAt: String,
    val agreementIndex: Double,
    val satisfiedProbability: Double,
    val contradictory: Boolean,
    val candidateAdjustments: List<String>,
    val perPair: List<OrderingPairResult>,
    val diagnostics: List<String>
)

@Serializable
data class OrderingPairResult(
    val olderSample: String,
    val youngerSample: String,
    val probabilityOlderBeforeYounger: Double,
    val contradictory: Boolean
)

/** 派生记录的新鲜度：局部输入变化只使依赖它的结果过期。 */
@Serializable
data class DerivedStaleness(
    val derivedId: String,
    val stale: Boolean,
    val currentFingerprint: String,
    val recordedFingerprint: String,
    val changedSources: List<String>
)
