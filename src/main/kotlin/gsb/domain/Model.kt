package gsb.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * 领域模型。所有进入系统的原始材料都不可变；修订以新版本 + parentId 表达。
 * 所有领域量携带单位/坐标基准；无法规范化的值留在原位并附诊断。
 */

@Serializable
data class SourceRef(
    val origin: String,          // CASE_PACK | MANUAL_ENTRY | IMPORT_FILE
    val detail: String,          // 案例包章节、文件名、录入说明
    val importedAt: String       // ISO-8601
)

@Serializable
data class Diagnostic(
    val code: String,            // OUT_OF_RANGE | BOUNDARY_INTERPOLATION | UNCONVERTIBLE_VALUE ...
    val severity: String,        // ERROR | WARNING | UNKNOWN | INFO
    val message: String,
    val at: String? = null
)

/** 带单位与坐标基准的量；value=null 表示无法转换，raw 保留原始表示。 */
@Serializable
data class Quantity(
    val value: Double?,
    val unit: String,
    val raw: String
)

// ---------------- 原始来源实体（不可变，修订即新版本） ----------------

@Serializable
data class Measurement(
    val id: String,
    val projectId: String,
    val labCode: String,
    val age14C: Double?,                 // null = 无法规范化
    val sigma14C: Double?,
    val ageUnit: String = "14C yr BP",
    val sampleMaterial: String? = null,
    val rawRepr: JsonObject,             // 逐字保留的原始 JSON 片段
    val source: SourceRef,
    val version: Int = 1,
    val parentId: String? = null,
    val superseded: Boolean = false,
    val diagnostics: List<Diagnostic> = emptyList()
)

@Serializable
data class CurveKnot(val calBp: Double, val radioAge: Double)

@Serializable
data class CalibrationCurve(
    val id: String,                     // 稳定身份，例如 SIMC-2026-v1
    val code: String,                   // SIMC
    val version: String,                // 2026-v1 / 2026-v2
    val name: String,
    val knots: List<CurveKnot>,         // 按 calBP 升序
    val calBpUnit: String = "cal BP (synthetic, 0=1950 CE)",
    val radioUnit: String = "14C yr BP",
    val builtin: Boolean = true,
    val builderVersion: String? = null,
    val fingerprintSha256: String,
    val source: SourceRef,
    val parentId: String? = null,
    val superseded: Boolean = false
) {
    val minCalBp get() = knots.first().calBp
    val maxCalBp get() = knots.last().calBp
}

@Serializable
data class OffsetComponent(
    val mean: Double,
    val sigma: Double,
    val weight: Double,
    val unit: String = "14C yr"
)

@Serializable
data class OffsetDist(
    val model: String,                  // DELTA_R | MIXTURE
    val components: List<OffsetComponent>
)

@Serializable
data class OffsetAssumption(
    val id: String,
    val code: String,
    val name: String,
    val distribution: OffsetDist,
    val source: SourceRef,
    val version: Int = 1,
    val parentId: String? = null,
    val superseded: Boolean = false
)

@Serializable
data class EraSystem(
    val id: String,
    val code: String,                   // BCE_CE_ASTRO
    val name: String,
    val anchorCalBp: Double = 1950.0,   // calBP=1950 对应 1 BCE/1 CE 天文纪年边界
    val note: String
)

@Serializable
data class EraLabel(val year: Int, val era: String, val display: String)

enum class SequenceType { BEFORE, CONTEMPORANEOUS }

@Serializable
data class SequenceConstraint(
    val id: String,
    val type: SequenceType,
    val fromSampleId: String,
    val toSampleId: String,
    val windowCalBp: Double? = null,    // CONTEMPORANEOUS 时的容差
    val source: SourceRef,
    val retracted: Boolean = false
)

@Serializable
data class ExclusionDecision(
    val id: String,
    val targetType: String,             // SAMPLE | MODE_REGION
    val targetId: String,
    val regionLabel: String? = null,
    val reason: String,
    val decidedBy: String,
    val source: SourceRef,
    val active: Boolean = true,
    val revertedByEventId: String? = null
)

// ---------------- 派生层：校准结果（多峰一等数据，不压成平均年） ----------------

@Serializable
data class DensityCell(
    val calBp: Double,
    val radioAge: Double,
    val likelihood: Double,
    val density: Double                // 已按固定归一化常数归一化（单位 1/calBP）
)

@Serializable
data class Mode(
    val index: Int,
    val peakCalBp: Double,
    val peakDensity: Double,
    val startCalBp: Double,
    val endCalBp: Double,
    val eraPeak: EraLabel,
    val crossesAnchor: Boolean
)

@Serializable
data class IntervalEvidence(
    val cellIndexFrom: Int,
    val cellIndexTo: Int,
    val weightSum: Double,
    val targetLevel: Double,
    val achievedLevel: Double,
    val normalizationZ: Double,
    val gridStep: Double,
    val formula: String,
    val tieRule: String,
    val sourceVersions: Map<String, String>
)

@Serializable
data class ProbInterval(
    val key: String,                    // 稳定身份 ivl-<runId>-<level>-<rank>
    val level: String,                  // P68.2 | P95.4
    val rank: Int,
    val startCalBp: Double,
    val endCalBp: Double,
    val probability: Double,
    val eraStart: EraLabel,
    val eraEnd: EraLabel,
    val crossesAnchor: Boolean,
    val coequalGroup: String? = null,   // 并列日历区间组
    val evidence: IntervalEvidence
)

enum class RunStatus { CURRENT, STALE, UNKNOWN }

@Serializable
data class CalibrationRun(
    val id: String,
    val eventSeq: Int,
    val measurementId: String,
    val curveId: String,
    val offsetId: String,
    val eraId: String,
    val algorithmVersion: String,
    val gridStep: Double,
    val seed: Long,
    val paramFingerprint: String,
    val status: RunStatus,
    val staleReason: String? = null,
    val createdAt: String,
    val cells: List<DensityCell>,
    val normalizationZ: Double,
    val modes: List<Mode>,
    val intervals: List<ProbInterval>,
    val diagnostics: List<Diagnostic>,
    val parentRunId: String? = null
)

@Serializable
data class AdjustmentCandidate(
    val key: String,
    val kind: String,           // OFFSET_SHIFT | EXCLUDE_MODE | REVISE_CONSTRAINT | KEEP_ALL_LEGAL
    val description: String,
    val params: Map<String, String>,
    val support: Double
)

enum class SequenceVerdict { SATISFIED, CONTRADICTED, UNRESOLVED }

@Serializable
data class SequenceAnalysis(
    val id: String,
    val eventSeq: Int,
    val constraintId: String,
    val fromRunId: String,
    val toRunId: String,
    val verdict: SequenceVerdict,
    val probability: Double,             // BEFORE: P(calA>calB)；CONTEMP: P(|Δ|<=w)
    val reverseProbability: Double,
    val algorithmVersion: String,
    val paramFingerprint: String,
    val status: RunStatus,
    val staleReason: String? = null,
    val candidates: List<AdjustmentCandidate>,
    val createdAt: String
)

// ---------------- 人工决定（接受/否定/调参，可撤销，冲突保留） ----------------

enum class DecisionVerb { ACCEPT, REJECT, ADJUST_PARAMS, RESOLVE_CONFLICT }

@Serializable
data class Decision(
    val id: String,
    val eventSeq: Int,
    val targetKind: String,              // INTERVAL | MODE | SEQUENCE_CANDIDATE | SAMPLE
    val targetKey: String,
    val runId: String?,
    val verb: DecisionVerb,
    val note: String,
    val decidedBy: String,
    val paramChanges: Map<String, String>,
    val active: Boolean = true,
    val revertedByEventId: String? = null,
    val conflictWith: List<String> = emptyList(),
    val createdAt: String
)
