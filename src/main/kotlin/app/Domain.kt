package app

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.security.MessageDigest

val json = Json { prettyPrint = false; ignoreUnknownKeys = true; explicitNulls = false }

/** 所有领域量都携带单位或坐标基准。 */
@Serializable
data class Quantity(val value: Double, val unit: String)

/** 支持的单位/坐标基准；无法转换的值保留原始表示并附诊断。 */
object Units {
    val KNOWN = setOf("BP14C", "14C BP", "yr BP", "calBP", "BCE", "CE")
    fun normalize(unit: String): String? = when (unit.trim()) {
        "BP14C", "14C BP", "yr BP", "BP" -> "BP14C"
        "calBP", "cal BP" -> "calBP"
        "BCE", "BC" -> "BCE"
        "CE", "AD" -> "CE"
        else -> null
    }
}

/** 年代系统：calBP(1950 基准) 与 BCE/CE 互转；无 0 年。 */
object Era {
    const val BASIS = "calBP1950"
    /** calBP -> "BCE n" / "CE n" */
    fun label(calBP: Double, basis: String = BASIS): String {
        require(basis == BASIS) { "未知年代基准: $basis" }
        val y = 1950.0 - calBP
        return if (y >= 1.0) "CE ${y.toLong()}" else "BCE ${(1.0 - y).toLong()}"
    }
    /** 区间 [loCalBP, hiCalBP] 是否跨越 BCE/CE 边界 (calBP=1950)。 */
    fun crosses(loCalBP: Double, hiCalBP: Double): Boolean = loCalBP < 1950.0 && hiCalBP >= 1950.0
}

@Serializable
data class CurvePoint(val calBP: Double, val c14: Double, val sigma: Double)

@Serializable
data class MeasurementPayload(
    val labCode: String,
    val age: Quantity,
    val sigma: Quantity,
    val offsetKey: String? = null,
    val eraKey: String? = null,
    val note: String = ""
)

@Serializable
data class CurvePayload(val curveName: String, val curveVersion: Int, val points: List<CurvePoint>)

@Serializable
data class OffsetPayload(val name: String, val mean: Quantity, val sd: Quantity)

@Serializable
data class EraPayload(val name: String, val basis: String = Era.BASIS)

@Serializable
data class ConstraintPayload(val older: String, val younger: String, val note: String = "")

@Serializable
data class ExclusionPayload(val sampleKey: String, val reason: String)

object SourceType {
    const val MEASUREMENT = "MEASUREMENT"
    const val CURVE = "CURVE"
    const val OFFSET = "OFFSET"
    const val ERA = "ERA"
    const val CONSTRAINT = "CONSTRAINT"
    const val EXCLUSION = "EXCLUSION"
    val ALL = setOf(MEASUREMENT, CURVE, OFFSET, ERA, CONSTRAINT, EXCLUSION)
}

object EventType {
    const val SOURCE_SUBMITTED = "SOURCE_SUBMITTED"
    const val SOURCE_REVISED = "SOURCE_REVISED"
    const val CALIBRATION_COMPUTED = "CALIBRATION_COMPUTED"
    const val SEQUENCE_EVALUATED = "SEQUENCE_EVALUATED"
    const val DECISION_RECORDED = "DECISION_RECORDED"
    const val DECISION_UNDONE = "DECISION_UNDONE"
    const val DECISION_CONFLICT_RAISED = "DECISION_CONFLICT_RAISED"
    const val DECISION_CONFLICT_RESOLVED = "DECISION_CONFLICT_RESOLVED"
    const val ARCHIVE_EXPORTED = "ARCHIVE_EXPORTED"
    const val ARCHIVE_IMPORTED = "ARCHIVE_IMPORTED"
}

/** 规范 JSON（键排序）用于稳定身份哈希。 */
fun canonical(el: JsonElement): String = when (el) {
    is JsonObject -> el.entries.sortedBy { it.key }
        .joinToString(",", "{", "}") { "\"${it.key}\":" + canonical(it.value) }
    is kotlinx.serialization.json.JsonArray -> el.joinToString(",", "[", "]") { canonical(it) }
    else -> el.toString()
}

fun sha256(s: String): String =
    MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

fun stableId(kind: String, payloadCanonical: String): String =
    "$kind-" + sha256("$kind|$payloadCanonical").take(20)

fun canonicalOf(jsonText: String): String = canonical(Json.parseToJsonElement(jsonText).jsonObject)

interface Clock { fun now(): String }
object SystemClock : Clock { override fun now(): String = java.time.Instant.now().toString() }
class FixedClock(var t: String = "2026-01-01T00:00:00Z") : Clock {
    override fun now(): String = t
}
