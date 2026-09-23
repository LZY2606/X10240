package gsb

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.security.MessageDigest

val JsonFmt = Json { ignoreUnknownKeys = true; prettyPrint = false; encodeDefaults = true }

interface Clock { fun now(): String }
object SystemClock : Clock { override fun now(): String = java.time.Instant.now().toString() }
class FixedClock(var t: String = "2026-01-01T00:00:00Z") : Clock {
    override fun now(): String = t
    fun advance(seconds: Long) { t = java.time.Instant.parse(t).plusSeconds(seconds).toString() }
}

fun sha256(s: String): String =
    MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

fun canon(el: JsonElement): String = when (el) {
    is JsonObject -> el.entries.sortedBy { it.key }.joinToString(",", "{", "}") { "\"${it.key}\":" + canon(it.value) }
    is JsonArray -> el.joinToString(",", "[", "]") { canon(it) }
    else -> el.toString()
}

// ---------- 输入载荷（原始表示保留，派生层才规范化） ----------
@Serializable
data class Measurement(
    val labCode: String,
    val c14AgeBP: Double? = null,
    val sigmaBP: Double? = null,
    val material: String = "",
    val unit: String = "14C yr BP",
    val raw: String = "",
    val diagnostic: String? = null
)

@Serializable
data class CurveRef(val curveId: String, val curveVersion: String, val coordinateBasis: String = "cal BP (1950)")

@Serializable
data class OffsetComponent(val weight: Double, val mean: Double, val sigma: Double)

@Serializable
data class OffsetDistribution(
    val kind: String = "normal",
    val mean: Double = 0.0,
    val sigma: Double = 0.0,
    val components: List<OffsetComponent> = emptyList(),
    val unit: String = "14C yr"
) {
    fun effectiveComponents(): List<OffsetComponent> =
        if (kind == "mixture" && components.isNotEmpty()) components
        else listOf(OffsetComponent(1.0, mean, sigma))
}

@Serializable
data class EraSystem(val system: String, val basis: String)

@Serializable
data class StratConstraint(val olderStream: String, val youngerStream: String, val basis: String)

@Serializable
data class ExclusionDecision(val sampleStream: String, val excluded: Boolean, val reason: String)

// ---------- 存储记录 ----------
@Serializable
data class InputRecord(
    val id: String,
    val streamId: String,
    val kind: String,
    val version: Int,
    val parentId: String?,
    val source: String,
    val payload: JsonObject,
    val createdAt: String
)

@Serializable
data class Dep(val streamId: String, val inputId: String)

@Serializable
data class DerivedRecord(
    val id: String,
    val kind: String,
    val algoVersion: String,
    val fingerprint: String,
    val deps: List<Dep>,
    val result: JsonObject,
    val createdAt: String
)

@Serializable
data class Event(val seq: Long, val id: String, val type: String, val payload: JsonObject, val createdAt: String)

object Kinds {
    const val MEASUREMENT = "measurement"
    const val CURVE_REF = "curveRef"
    const val OFFSET = "offsetDistribution"
    const val ERA = "eraSystem"
    const val STRAT = "stratConstraint"
    const val EXCLUSION = "exclusionDecision"
    val ALL = listOf(MEASUREMENT, CURVE_REF, OFFSET, ERA, STRAT, EXCLUSION)
}

object Ev {
    const val INPUT_SUBMITTED = "input.submitted"
    const val CALIB_COMPUTED = "calibration.computed"
    const val DERIVED_STALED = "derived.staled"
    const val STRAT_ASSESSED = "strat.assessed"
    const val ADJUDICATION = "adjudication.recorded"
    const val CONFLICT_RESOLVED = "conflict.resolved"
    const val CONFLICT_UNDONE = "conflict.undone"
}

// ---------- 年代系统换算（天文纪年：0 = 1 BCE，-1 = 2 BCE） ----------
object Era {
    fun astroYear(calBp: Double): Double = 1950.0 - calBp
    fun label(calBp: Double): String {
        val y = astroYear(calBp)
        return if (y > 0) "${y.toInt()} CE" else "${1 - y.toInt()} BCE"
    }
    fun crossesEraBoundary(loBp: Double, hiBp: Double): Boolean =
        loBp < 1950.0 && hiBp > 1949.0
}
