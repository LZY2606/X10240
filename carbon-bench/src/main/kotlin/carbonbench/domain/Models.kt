package carbonbench.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ---------- 计量与坐标基准 ----------

@Serializable
data class Quantity(
    val value: Double?,
    val sigma: Double? = null,
    val unit: String,
    val raw: String,
    val diagnostics: List<String> = emptyList()
) {
    val convertible: Boolean get() = value != null
}

// ---------- 原始测量（一经提交不可覆盖，修订以新版本表达） ----------

@Serializable
data class MeasurementVersion(
    val id: String,
    val projectId: String,
    val labCode: String,
    val parentId: String? = null,
    val version: Int,
    val age: Quantity,                  // radiocarbon age, 期望单位 14C yr BP
    val sigma: Quantity,                // 实验室标准差
    val sourceRef: String,              // 来源（实验室报告 / 记录人）
    val active: Boolean = true,
    val createdAt: String
)

// ---------- 储库偏移（delta-R 分布） ----------

@Serializable
data class OffsetComponent(
    val id: String,
    val weight: Double,
    val mean: Double,                   // 14C years
    val sigma: Double,                 // 14C years
    val basis: String                  // 来源/依据
)

@Serializable
data class ReservoirOffset(
    val id: String,
    val projectId: String,
    val label: String,
    val kind: String,                   // gaussian | mixture
    val components: List<OffsetComponent>,
    val sourceRef: String,
    val parentId: String? = null,
    val active: Boolean = true,
    val createdAt: String
)

// ---------- 年代系统 ----------

@Serializable
data class DatingSystem(
    val id: String,
    val name: String,
    val basisYearCE: Int,               // cal BP 基准（1950）
    val yearZeroConvention: String,     // astronomical
    val sourceRef: String
)

// ---------- 层序约束 ----------

@Serializable
data class SequenceConstraint(
    val id: String,
    val projectId: String,
    val olderLabCode: String,           // olderThan: olderLabCode 早于 youngerLabCode
    val youngerLabCode: String,
    val sourceRef: String,
    val status: String,                 // asserted | retracted
    val createdAt: String
)

// ---------- 排除决定 ----------

@Serializable
data class ExclusionDecision(
    val id: String,
    val projectId: String,
    val labCode: String,
    val excluded: Boolean,
    val reason: String,
    val sourceRef: String,
    val status: String,                 // active | retracted
    val createdAt: String
)

// ---------- 校准曲线 ----------

@Serializable
data class CurvePoint(
    val calBP: Double,                  // 坐标基准：cal BP (1950)
    val c14Age: Double,                 // 14C yr BP
    val c14Sigma: Double               // 14C years
)

@Serializable
data class CalibrationCurve(
    val curveId: String,                // e.g. CARBON-BENCH-WIGGLE
    val version: String,                // e.g. 1
    val minCalBP: Double,
    val maxCalBP: Double,
    val spacing: Double,
    val sourceRef: String,
    val points: List<CurvePoint>
) {
    val fingerprint: String get() = carbonbench.util.Hashing.shortFingerprint(
        curveId + "|" + version + "|" + points.joinToString(";") {
            "%g,%g,%g".format(it.calBP, it.c14Age, it.c14Sigma)
        }
    )
}

// ---------- 校准结果（派生值：算法版本 + 参数指纹 + 直接来源） ----------

@Serializable
data class GridPoint(val id: String, val calBP: Double, val density: Double)

@Serializable
data class HpdInterval(
    val id: String,
    val startCalBP: Double,
    val endCalBP: Double,
    val level: Double,                  // 0.68 / 0.95
    val probability: Double,
    val componentIndex: Int,
    val integralEvidence: IntegralEvidence
)

@Serializable
data class IntegralEvidence(
    val integration: String,            // trapezoid
    val gridStep: Double,
    val includedGridIds: List<String>,
    val normalizedAgainst: String,      // 归一化区间与版本
    val subSteps: List<IntegralSubStep>
)

@Serializable
data class IntegralSubStep(
    val gridId: String,
    val calBP: Double,
    val density: Double,
    val weight: Double,
    val contribution: Double
)

@Serializable
data class BoundaryDiagnostic(
    val type: String,                   // clipped-window | unsupported-window | internal-boundary
    val message: String,
    val calBP: Double? = null
)

@Serializable
data class CalibrationResult(
    val id: String,
    val projectId: String,
    val labCode: String,
    val measurementId: String,
    val offsetId: String?,
    val curveId: String,
    val curveVersion: String,
    val curveFingerprint: String,
    val algorithmVersion: String,
    val parameterFingerprint: String,
    val grid: List<GridPoint>,
    val intervals68: List<HpdInterval>,
    val intervals95: List<HpdInterval>,
    val diagnostics: List<BoundaryDiagnostic>,
    val seed: Long,
    val rawMass: Double,
    val normalized: Boolean,
    val diagnosticMeanCalBP: Double?,
    val multimodal: Boolean,
    val status: String,                 // current | stale | superseded
    val staleReason: String? = null,
    val computedAt: String,
    val directSources: List<String>
)

// ---------- 层序分析 ----------

@Serializable
data class PairSequenceResult(
    val constraintId: String,
    val olderLabCode: String,
    val youngerLabCode: String,
    val probabilityOlderFirst: Double,
    val probabilityYoungerFirst: Double,
    val undecidedMass: Double,
    val verdict: String                 // consistent | conflict | undecided
)

@Serializable
data class CandidateAdjustment(
    val id: String,
    val kind: String,                   // relax-constraint | add-constraint | exclude-sample | recheck-offset
    val targetId: String,
    val description: String,
    val preservesMeasurement: Boolean,
    val accepted: Boolean = false
)

@Serializable
data class SequenceAnalysis(
    val id: String,
    val projectId: String,
    val calibrationIds: List<String>,
    val constraintIds: List<String>,
    val pairs: List<PairSequenceResult>,
    val candidates: List<CandidateAdjustment>,
    val algorithmVersion: String,
    val parameterFingerprint: String,
    val status: String,                 // current | stale
    val staleReason: String? = null,
    val computedAt: String,
    val directSources: List<String>
)

// ---------- 候选区间裁定（接受/否定/参数调整） ----------

@Serializable
data class IntervalDecision(
    val id: String,
    val calibrationId: String,
    val intervalId: String,
    val decision: String,               // accepted | rejected | parameter-adjusted
    val note: String,
    val replacementCalibrationId: String? = null,
    val status: String,                 // active | retracted
    val decidedAt: String
)

// ---------- 事件溯源 ----------

@Serializable
data class Event(
    val id: String,
    val projectId: String,
    val seq: Long,
    val type: String,
    val atTime: String,
    val actor: String,
    val payload: kotlinx.serialization.json.JsonObject,
    val parentEventId: String? = null,
    val retractsEventId: String? = null
)

@Serializable
data class ImportDiagnostic(
    val segment: String,
    val severity: String,               // error | warning
    val code: String,
    val message: String,
    val retainedRaw: String? = null
)

@Serializable
data class Project(
    val id: String,
    val name: String,
    val casePackId: String? = null,
    val casePackVersion: String? = null,
    val datingSystem: DatingSystem,
    val createdAt: String
)
