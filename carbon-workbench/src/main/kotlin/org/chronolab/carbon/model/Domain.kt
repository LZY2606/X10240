package org.chronolab.carbon.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** 算法与曲线版本在整个工作台中是显式的一等数据。 */
const val ALGO_CALIBRATION = "calib-gaussian-convolution/1.0.0"
const val ALGO_SEQUENCE = "sequence-feasibility/1.0.0"
const val ALGO_NORMALIZATION = "density-trapezoid-normalize/1.0.0"
const val ERA_BP = "BP (years before 1950 CE, radiocarbon convention)"
const val ERA_CALBP = "calBP (calendar years before 1950 CE)"
const val ERA_BCECE = "BCE/CE (astronomical numbering, no year zero)"

/** 领域量：值 + 单位 + 坐标基准，原始表示在导入层另外保留。 */
@Serializable
data class Quantity(
    val value: Double,
    val unit: String,
    val datum: String,
    val raw: String? = null
) {
    companion object {
        fun bp(value: Double, raw: String? = null) =
            Quantity(value, "14C years BP", ERA_BP, raw)
        fun calBp(value: Double, raw: String? = null) =
            Quantity(value, "calendar years BP", ERA_CALBP, raw)
        fun years(value: Double) = Quantity(value, "years", ERA_CALBP, null)
        fun dimensionless(value: Double) = Quantity(value, "probability", "unit interval [0,1]", null)
    }
}

/** calBP -> BCE/CE。采用天文纪年：calBP 1950 = 1 BCE，calBP 1949 = 1 CE，无“0 年”。 */
object CalendarEra {
    fun astronomicalYear(calBP: Double): Int = (1950.0 - calBP).toInt()
    fun label(calBP: Double): String {
        val astro = astronomicalYear(calBP)
        return when {
            astro <= 0 -> "${1 - astro} BCE"
            else -> "$astro CE"
        }
    }
    fun intervalLabel(startCalBP: Double, endCalBP: Double): String =
        "${label(endCalBP)} – ${label(startCalBP)}"
    /** 区间是否跨越 BCE/CE 分界（calBP 1949.5 两侧）。 */
    fun crossesBceCe(startCalBP: Double, endCalBP: Double): Boolean =
        startCalBP > 1949.5 && endCalBP < 1949.5
}

enum class SourceKind { MEASUREMENT, CALIBRATION_CURVE, RESERVOIR_MODEL, SEQUENCE_LINKS, EXCLUSION, GENERIC }

@Serializable
data class Origin(
    val citation: String,
    val suppliedBy: String,
    val receivedOn: String,
    val fragmentRef: String
)

/** 原始测量/曲线/偏移/层序等“直接来源”。一经提交不可覆盖；修订以新版本+父版本表达。 */
@Serializable
data class SourceNode(
    val nodeId: String,
    val logicalId: String,
    val kind: SourceKind,
    val version: Int,
    val parentNodeId: String? = null,
    val eraSystem: String,
    val origin: Origin,
    val fields: JsonObject,
    val rawRepresentations: Map<String, String> = emptyMap(),
    val diagnostics: List<Diagnostic> = emptyList()
) {
    val current: Boolean get() = true
}

@Serializable
data class Diagnostic(
    val code: String,
    val severity: String, // ERROR | WARNING | UNKNOWN
    val message: String,
    val field: String? = null
)

/** 派生层通用指纹：算法版本 + 直接来源(节点,版本) + 参数指纹。 */
@Serializable
data class Provenance(
    val algorithmVersion: String,
    val directSources: List<SourceRef>,
    val parameterFingerprint: String,
    val seed: Long,
    val computedAtVirtualTime: String
)

@Serializable
data class SourceRef(val nodeId: String, val version: Int, val role: String)

@Serializable
data class CalibrationInterval(
    val intervalId: String,
    val startCalBP: Double,
    val endCalBP: Double,
    val startLabel: String,
    val endLabel: String,
    val probability: Double,
    val level: Double,
    val crossesBceCe: Boolean
)

@Serializable
data class ModePeak(val modeId: String, val calBP: Double, val label: String, val density: Double)

@Serializable
data class CalibrationResult(
    val calibrationId: String,
    val measurementNodeId: String,
    val curveVersion: String,
    val reservoirNodeId: String?,
    val gridStartCalBP: Double,
    val gridStepYears: Double,
    val gridEndCalBP: Double,
    val supportSigma: Double,
    val density: List<Double>,
    val totalMass: Double,
    val intervals68: List<CalibrationInterval>,
    val intervals95: List<CalibrationInterval>,
    val modes: List<ModePeak>,
    val multimodal: Boolean,
    val tiedIntervals: Boolean,
    val crossesBceCe: Boolean,
    val provenance: Provenance
)

@Serializable
data class CandidateAdjustment(
    val candidateId: String,
    val kind: String, // RELAX_LINK | EXCLUDE_SAMPLE | REVISIT_MEASUREMENT | USE_ALTERNATIVE_INTERVAL
    val description: String,
    val targetNodeId: String?,
    val rewritesMeasurement: Boolean = false
)

enum class SequenceStatus { FEASIBLE, CONFLICT, UNKNOWN }

@Serializable
data class FeasibleChoice(
    val choiceId: String,
    val assignments: Map<String, String>, // measurementLogicalId -> intervalId
    val note: String
)

@Serializable
data class SequenceAssessment(
    val assessmentId: String,
    val orderedOldToYoung: List<String>,
    val links: List<SequenceLink>,
    val status: SequenceStatus,
    val feasibleChoices: List<FeasibleChoice>,
    val violatedLinkIds: List<String>,
    val candidateAdjustments: List<CandidateAdjustment>,
    val excludedSampleIds: List<String>,
    val provenance: Provenance
)

@Serializable
data class SequenceLink(
    val linkId: String,
    val olderLogicalId: String,
    val youngerLogicalId: String,
    val justification: String
)

enum class DecisionVerb { ACCEPT, REJECT, EXCLUDE, REINSTATE, ADJUST_PARAMETER }

@Serializable
data class DecisionRecord(
    val decisionId: String,
    val verb: DecisionVerb,
    val targetNodeId: String,
    val targetIntervalId: String? = null,
    val reason: String,
    val actor: String,
    val virtualTime: String,
    val undone: Boolean = false,
    val undoneByEventId: String? = null
)

/** 投影中给界面使用的派生节点：校准/层序评估与其状态。 */
@Serializable
data class DerivedNode(
    val nodeId: String,
    val logicalId: String,
    val kind: String, // CALIBRATION | SEQUENCE_ASSESSMENT
    val version: Int,
    val parentNodeId: String?,
    val status: String, // CURRENT | SUPERSEDED | STALE
    val calibration: CalibrationResult? = null,
    val assessment: SequenceAssessment? = null
)

enum class ArchiveStatus { PASS, FAIL, UNKNOWN }

@Serializable
data class ArchiveCaseResult(
    val caseId: String,
    val title: String,
    val changedDirectSource: String,
    val baselineEventIds: List<String>,
    val variantBranchId: String,
    val expectedScopeNodeIds: List<String>,
    val untouchedNodeIds: List<String>,
    val adoptedResultIds: List<String>,
    val rejectedResultIds: List<String>,
    val status: ArchiveStatus,
    val evidence: Map<String, String>,
    val machineJudgement: String
)
