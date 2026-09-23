package gsb

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.time.Instant

/** Virtual-time clock: tests inject fixed instants, production uses system time. */
fun interface Clock { fun now(): String }
object SystemClock : Clock { override fun now(): String = Instant.now().toString() }
class FixedClock(private var t: String = "2026-01-01T00:00:00Z") : Clock {
    override fun now(): String = t
    fun tick(seconds: Long) { t = Instant.parse(t).plusSeconds(seconds).toString() }
}

val CanonicalJson = Json {
    prettyPrint = false
    encodeDefaults = true
    explicitNulls = false
}

fun sha256(s: String): String =
    MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
        .joinToString("") { "%02x".format(it) }

enum class SourceKind { MEASUREMENT, CURVE, OFFSET, ERA_SYSTEM, STRATIGRAPHY, EXCLUSION }

@Serializable
data class Measurement(
    val sampleId: String,
    val labCode: String,
    val c14AgeBP: Double,
    val sigma: Double,
    val material: String,
    val raw: String,          // original representation as submitted, never normalized away
    val unit: String = "14C yr BP"
)

@Serializable
data class CurvePoint(val calBP: Double, val c14BP: Double, val sigma: Double)

@Serializable
data class Curve(val name: String, val curveVersion: String, val points: List<CurvePoint>)

@Serializable
data class OffsetDist(
    val name: String,
    val deltaR: Double,
    val sigma: Double,
    val unit: String = "14C yr"
)

@Serializable
data class EraSystem(val name: String = "BCE/CE", val epoch: String = "1950 CE = 0 cal BP", val note: String = "no year zero")

@Serializable
data class StratConstraint(val olderSample: String, val youngerSample: String, val note: String = "")

@Serializable
data class Stratigraphy(val constraints: List<StratConstraint>)

@Serializable
data class Exclusion(val sampleId: String, val excluded: Boolean, val reason: String)

@Serializable
data class Exclusions(val items: List<Exclusion>)

/** A direct-source reference pinned to an exact version: "streamId@version". */
@Serializable
data class SourceRef(val streamId: String, val version: Int) {
    override fun toString() = "$streamId@$version"
    companion object {
        fun parse(s: String): SourceRef {
            val i = s.lastIndexOf('@')
            require(i > 0) { "bad source ref: $s" }
            return SourceRef(s.substring(0, i), s.substring(i + 1).toInt())
        }
    }
}
