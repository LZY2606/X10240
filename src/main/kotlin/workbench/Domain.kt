package workbench

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.security.MessageDigest

val JsonElement.jsonObjectOrNull: JsonObject? get() = this as? JsonObject
val JsonElement.jsonArrayOrNull: JsonArray? get() = this as? JsonArray

/** 年代系统：calBP 与 BCE/CE 互转。无 0 年。 */
object Era {
    /** calBP -> 天文纪年（1950 - calBP；<=0 表示 BCE 侧） */
    fun calBpToAstronomical(calBp: Double): Double = 1950.0 - calBp

    /** calBP -> "CE 750" / "BCE 100"（无 0 年：天文年 0 = 1 BCE） */
    fun calBpToLabel(calBp: Double): String {
        val astro = calBpToAstronomical(calBp)
        return if (astro >= 1) "CE ${astro.toInt()}" else "BCE ${(1 - astro).toInt()}"
    }

    /** 区间 [olderCalBp, youngerCalBp] 是否跨越 BCE/CE 边界（calBP = 0 即 1950 CE 不算；边界为 astro year 1/0） */
    fun crossesBceCe(olderCalBp: Double, youngerCalBp: Double): Boolean {
        val a = calBpToAstronomical(olderCalBp)
        val b = calBpToAstronomical(youngerCalBp)
        return (a < 1 && b >= 1) || (b < 1 && a >= 1)
    }
}

/** 稳定身份：所有实体 ID 形如 "src-0001" / "der-0003"，由事件序号派生，导出/导入后保持不变。 */
object Ids {
    fun of(prefix: String, seq: Long): String = "%s-%04d".format(prefix, seq)
}

fun sha256(text: String): String =
    MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

/** 参数指纹：算法版本 + 规范化参数文本 -> 稳定 hash。任何直接来源或参数变化都会改变指纹。 */
fun fingerprintOf(algorithmVersion: String, canonicalParams: String): String =
    sha256("$algorithmVersion|$canonicalParams").take(16)

// ---------- 源输入（一经提交不可覆盖；修订 = 新版本 + 父版本） ----------

enum class SourceKind { MEASUREMENT, CURVE, RESERVOIR_OFFSET, ERA_SYSTEM, SEQUENCE_CONSTRAINT, EXCLUSION_DECISION }

data class SourceRecord(
    val id: String,
    val kind: SourceKind,
    val version: Int,          // 1 起；修订产生 version+1
    val parentId: String?,        // 修订的父版本 id；首版为 null
    val provenance: String,    // 来源说明（实验室、文献、操作者…）
    val payload: String,       // 原始表示（JSON 文本，原样保留，不做规范化）
    val unit: String,          // 单位 / 坐标基准，如 "BP", "calBP", "BCE/CE"
    val supersedes: String? = null
)

// ---------- 领域载荷（解析后的视图；原始文本仍保存在 SourceRecord.payload） ----------

data class Measurement(val labCode: String, val ageBp: Double, val sigma: Double, val material: String)

data class CurvePoint(val calBp: Double, val c14Age: Double, val sigma: Double)

data class CurveData(val name: String, val points: List<CurvePoint>) {
    val minCalBp = points.minOf { it.calBp }
    val maxCalBp = points.maxOf { it.calBp }
}

/** 储库偏移分布：均值/ sigma 或显式样本（离散分布）。 */
data class ReservoirOffset(val mean: Double, val sigma: Double, val samples: List<Double> = emptyList())

data class SequenceConstraint(val beforeId: String, val afterId: String, val note: String)

data class ExclusionDecision(val targetLabCode: String, val excluded: Boolean, val reason: String, val author: String)

// ---------- 派生结果（一等数据：候选、拒绝原因、误差、未知状态） ----------

enum class DerivedStatus { CURRENT, STALE, CONFLICTED, UNKNOWN }

data class DerivedRecord(
    val id: String,
    val kind: String,               // "calibration" | "sequence-check" | "interval-probability"
    val algorithmVersion: String,
    val paramFingerprint: String,
    val sourceIds: List<String>,    // 直接来源
    val payload: String,            // JSON 结果（含候选、拒绝原因、误差、诊断）
    val status: DerivedStatus,
    val staleReason: String? = null
)
