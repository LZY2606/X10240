package app

import kotlinx.serialization.json.*
import java.security.MessageDigest

const val ALGO_VERSION = "calib-1.0.0"
const val EXPORT_FORMAT = "rcwb-export"
const val EXPORT_VERSION = 1

val JsonFmt = Json { prettyPrint = false; ignoreUnknownKeys = true }

/** Calendar era handling. Internally everything is cal BP (before 1950).
 *  Astronomical year numbering: astro = 1950 - calBp. astro 1 = CE 1, astro 0 = 1 BCE, astro -99 = 100 BCE. */
object Era {
    fun astroFromCalBp(calBp: Int): Int = 1950 - calBp
    fun display(astro: Int): String = if (astro > 0) "CE $astro" else "${1 - astro} BCE"
    fun displayCalBp(calBp: Int): String = display(astroFromCalBp(calBp))
    /** older (larger BP) first */
    fun displayRange(fromBp: Int, toBp: Int): String =
        "${displayCalBp(fromBp)} – ${displayCalBp(toBp)}"
}

fun sha256(text: String): String {
    val md = MessageDigest.getInstance("SHA-256")
    return md.digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}

fun canonical(json: JsonElement): String {
    fun norm(e: JsonElement): String = when (e) {
        is JsonObject -> e.entries.sortedBy { it.key }
            .joinToString(",", "{", "}") { "\"${it.key}\":${norm(it.value)}" }
        is JsonArray -> e.joinToString(",", "[", "]") { norm(it) }
        else -> e.toString()
    }
    return norm(json)
}

fun fingerprintOf(vararg parts: String): String = sha256(parts.joinToString("|"))

/** Source kinds, each carrying its own provenance. */
object SourceKind {
    const val MEASUREMENT = "measurement"       // 原测量值和标准差
    const val CURVE = "curve"                   // 曲线版本
    const val OFFSET = "offset"                 // 偏移分布 (reservoir offset)
    const val ERA_SYSTEM = "eraSystem"          // 年代系统
    const val STRAT_CONSTRAINT = "stratConstraint" // 层序约束
    const val EXCLUSION = "exclusion"           // 排除决定
    val ALL = listOf(MEASUREMENT, CURVE, OFFSET, ERA_SYSTEM, STRAT_CONSTRAINT, EXCLUSION)
}

data class Source(
    val id: String,
    val kind: String,
    val version: Int,
    val parentId: String?,
    val content: JsonObject,
    val submittedAt: String,
    val seq: Long
) {
    val label: String get() = content["label"]?.jsonPrimitive?.contentOrNull ?: id
}

data class DerivedResult(
    val id: String,
    val kind: String,                 // "calibration"
    val algoVersion: String,
    val paramsFingerprint: String,
    val sourceIds: List<String>,      // direct sources (exact version ids)
    val content: JsonObject,
    val stale: Boolean,
    val createdSeq: Long
)

data class Event(
    val seq: Long,
    val id: String,
    val type: String,
    val payload: JsonObject,
    val at: String
)

object EventType {
    const val SOURCE_IMPORTED = "source.imported"
    const val SOURCE_REVISED = "source.revised"
    const val IMPORT_REJECTED = "source.import.rejected"
    const val CALIBRATION_COMPUTED = "calibration.computed"
    const val CALIBRATION_REFUSED = "calibration.refused"
    const val RESULT_STALED = "result.staled"
    const val STRAT_CHECKED = "strat.checked"
    const val STRAT_CANDIDATE = "strat.candidate"
    const val DECISION_MADE = "decision.made"
    const val DECISION_CONFLICT = "decision.conflict"
    const val DECISION_UNDONE = "decision.undone"
    const val PROJECT_EXPORTED = "project.exported"
    const val PROJECT_IMPORTED = "project.imported"
    const val SCENARIO_STEP = "scenario.step"
    const val INTERVALS_COMPARED = "intervals.compared"
}

/** An active review decision over one calibrated interval. */
data class Decision(
    val eventSeq: Long,
    val resultId: String,
    val intervalIndex: Int,
    val level: String,       // "68" or "95"
    val verdict: String,     // "accept" | "reject"
    val reviewer: String,
    val rationale: String,
    val undone: Boolean
)

class ImportException(val diagnostics: List<String>) : Exception(diagnostics.joinToString("; "))
