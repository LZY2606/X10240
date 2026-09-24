package workbench.model

import kotlinx.serialization.Serializable

/** 每个输入都携带自己的来源。 */
@Serializable
data class SourceRef(
    val document: String,      // 来源文献/实验室报告
    val locator: String,       // 页码/表号/行号
    val note: String = ""
)

/** 年代系统定义：calBP 与 BCE/CE 的换算基准（无 0 年）。 */
@Serializable
data class CalendarSystemDef(
    val entityId: String,
    val version: Int,
    val parentVersion: Int? = null,
    val name: String,              // e.g. "BCE/CE (no year zero)"
    val bpReferenceYear: Int = 1950,
    val source: SourceRef
)

/** 校准曲线的一个版本：点列 (calBp, c14AgeBp, sigma)。 */
@Serializable
data class CurvePoint(val calBp: Int, val c14AgeBp: Double, val sigma: Double)

@Serializable
data class CurveVersion(
    val entityId: String,
    val version: Int,
    val parentVersion: Int? = null,
    val curveName: String,         // e.g. "MiniCal"
    val points: List<CurvePoint>,
    val source: SourceRef
)

/** 储库偏移分布。 */
@Serializable
sealed class OffsetDistribution {
    @Serializable
    data class Gaussian(val mean: Double, val sigma: Double) : OffsetDistribution()
    @Serializable
    data class Uniform(val min: Double, val max: Double) : OffsetDistribution()
}

@Serializable
data class ReservoirOffset(
    val entityId: String,
    val version: Int,
    val parentVersion: Int? = null,
    val label: String,
    val distribution: OffsetDistribution,
    val source: SourceRef
)

/** 实验室测得的 radiocarbon age（原始表示，BP 约定）。 */
@Serializable
data class Measurement(
    val entityId: String,
    val version: Int,
    val parentVersion: Int? = null,
    val labCode: String,
    val c14AgeBp: Double,
    val sigma: Double,
    val material: String,
    val reservoirOffsetId: String? = null,   // versioned ref: "off-0001@v1"
    val calendarSystemId: String,            // versioned ref: "csys-0001@v1"
    val source: SourceRef
)

/** 层序约束：lower 在层位上低于（老于）upper。 */
@Serializable
data class StratConstraint(
    val entityId: String,
    val version: Int,
    val parentVersion: Int? = null,
    val lowerMeasurementId: String,   // versioned ref
    val upperMeasurementId: String,   // versioned ref
    val note: String,
    val source: SourceRef
)

/** 人工排除决定（输入层的一等材料）。 */
@Serializable
data class ExclusionDecision(
    val entityId: String,
    val version: Int,
    val parentVersion: Int? = null,
    val targetMeasurementId: String,  // versioned ref
    val excludedIntervalCalBp: IntRange?,
    val reason: String,
    val decidedBy: String,
    val source: SourceRef
)

@Serializable
data class InputEnvelope(
    val kind: String,          // measurement | curve | offset | calendarSystem | stratConstraint | exclusion
    val payload: String        // 序列化后的实体 JSON（保留原始表示）
)

// ---------- 派生层 ----------

@Serializable
data class DensityPoint(val calBp: Int, val probability: Double)

@Serializable
data class Mode(
    val index: Int,
    val peakCalBp: Int,
    val rangeStartCalBp: Int,
    val rangeEndCalBp: Int,
    val mass: Double
)

@Serializable
data class IntervalEvidencePoint(val calBp: Int, val probability: Double, val cumulative: Double)

@Serializable
data class CalendarInterval(
    val startCalBp: Int,
    val endCalBp: Int,          // start > end（calBP 向过去增大）
    val level: Double,          // HPD 层级，如 0.6827 / 0.9545
    val probability: Double,
    val startDisplay: String,   // 带年代系统的显示，如 "501 BCE"
    val endDisplay: String,
    val evidence: List<IntervalEvidencePoint>  // 可展开的积分证据
)

@Serializable
data class CalibrationResult(
    val resultId: String,
    val measurementRef: String,      // "msr-0001@v1"
    val curveRef: String,            // "crv-0001@v1"
    val offsetRef: String?,          // "off-0001@v1" 或 null
    val calendarSystemRef: String,
    val algorithmVersion: String,    // e.g. "calib-1.0"
    val paramFingerprint: String,    // sha256
    val gridStartCalBp: Int,
    val gridEndCalBp: Int,
    val gridStepYears: Int,
    val density: List<DensityPoint>, // 归一化概率密度（多峰，不压成平均年）
    val modes: List<Mode>,
    val intervals: List<CalendarInterval>,
    val diagnostics: List<String> = emptyList()  // 例如 boundary-extrapolation-clamped
)

@Serializable
data class CandidateAdjustment(
    val candidateId: String,
    val description: String,
    val impliedChange: String,       // 对哪个对象施加什么约束（不改写测量）
    val resultingIntervalCalBp: IntRange? = null
)

@Serializable
data class StratConflict(
    val conflictId: String,
    val constraintRef: String,
    val lowerResultRef: String,
    val upperResultRef: String,
    val detail: String,
    val candidates: List<CandidateAdjustment>,
    val status: String = "open"      // open | resolved
)

@Serializable
data class HumanDecision(
    val decisionId: String,
    val targetRef: String,           // 结果/区间/冲突 的稳定身份
    val verdict: String,             // accept | reject | adjust | resolve-conflict
    val rationale: String,
    val decidedBy: String,
    val parameters: Map<String, String> = emptyMap()
)

@Serializable
data class DerivedRecord(
    val id: String,
    val kind: String,                // calibration | stratConflict
    val algoVersion: String,
    val fingerprint: String,
    val sources: List<String>,       // 直接来源的 versioned refs
    val status: String,              // current | stale
    val payload: String
)

@Serializable
data class Event(
    val seq: Long,
    val id: String,
    val type: String,
    val at: String,
    val actor: String,
    val payload: String
)

object EventTypes {
    const val INPUT_SUBMITTED = "input.submitted"
    const val INPUT_REVISED = "input.revised"
    const val CALIBRATION_COMPUTED = "calibration.computed"
    const val RESULT_STALED = "result.staled"
    const val STRAT_EVALUATED = "strat.evaluated"
    const val STRAT_CONFLICT = "strat.conflict"
    const val DECISION_RECORDED = "decision.recorded"
    const val DECISION_UNDONE = "decision.undone"
    const val CONFLICT_RESOLVED = "conflict.resolved"
    const val CASE_IMPORTED = "case.imported"
}
