package bench

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import java.security.MessageDigest
import java.time.Instant

/** 虚拟时钟：测试注入固定时间，运行时用系统时间。 */
interface Clock { fun now(): Instant }
class SystemClock : Clock { override fun now(): Instant = Instant.now() }
class MutableClock(private var t: Instant) : Clock {
    override fun now(): Instant = t
    fun advanceSeconds(s: Long) { t = t.plusSeconds(s) }
}

object Json {
    val gson: Gson = GsonBuilder().serializeNulls().create()
    inline fun <reified T> from(json: String): T = gson.fromJson(json, T::class.java)
    fun to(v: Any?): String = gson.toJson(v)
    fun mapOf(json: String): Map<String, Any?> =
        gson.fromJson(json, object : TypeToken<Map<String, Any?>>() {}.type) ?: emptyMap()
    fun listOfMaps(json: String): List<Map<String, Any?>> =
        gson.fromJson(json, object : TypeToken<List<Map<String, Any?>>>() {}.type) ?: emptyList()
}

/** 稳定身份：由内容哈希派生，导出/删除/再导入后保持不变。 */
object Ids {
    fun of(kind: String, canonicalContent: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$kind|$canonicalContent".toByteArray(Charsets.UTF_8))
        val hex = digest.joinToString("") { "%02x".format(it) }.substring(0, 20)
        return "$kind-$hex"
    }
}

// ---------- 领域量：全部携带单位或坐标基准 ----------

/** 放射性碳年龄，单位：14C years BP (BP 基准 1950)。 */
data class C14Age(val bp: Double, val sigma: Double) {
    init { require(sigma > 0) { "sigma must be positive" } }
}

/** 日历年，坐标基准 cal BP（1950 为 0）。 */
data class CalBP(val value: Double)

enum class EraSystem(val label: String) { CAL_BP("cal BP"), BCE_CE("BCE/CE") }

/** BCE/CE 转换，无公元零年：calBP 1950 = 1 CE；calBP 1951 = 1 BCE。 */
object Era {
    /** 返回 (era, year)：era 为 "BCE" 或 "CE"。 */
    fun calBpToBceCe(calBp: Double): Pair<String, Int> {
        val ce = 1950.0 - calBp
        return if (ce >= 1.0) "CE" to kotlin.math.floor(ce).toInt()
        else "BCE" to kotlin.math.floor(1.0 - ce).toInt()
    }
    fun bceCeToCalBp(era: String, year: Int): Double =
        if (era == "CE") 1950.0 - year else 1949.0 + year
    fun format(calBp: Double, system: EraSystem): String = when (system) {
        EraSystem.CAL_BP -> "%.0f cal BP".format(calBp)
        EraSystem.BCE_CE -> { val (e, y) = calBpToBceCe(calBp); "$y $e" }
    }
}

// ---------- 校准曲线 ----------

data class CurvePoint(val calBp: Double, val c14Age: Double, val c14Sigma: Double)

data class CurveTable(val name: String, val points: List<CurvePoint>) {
    init { require(points.size >= 2 && points.zipWithNext().all { (a, b) -> b.calBp > a.calBp }) }

    val minCalBp get() = points.first().calBp
    val maxCalBp get() = points.last().calBp

    /** 线性插值；越界时沿端点段外推并返回 boundary=true（边界插值诊断）。 */
    fun interpolate(calBp: Double): Pair<CurvePoint, Boolean> {
        if (calBp <= minCalBp) return extrapolate(points[0], points[1], calBp, calBp < minCalBp)
        if (calBp >= maxCalBp) return extrapolate(points[points.size - 2], points.last(), calBp, calBp > maxCalBp)
        var lo = 0; var hi = points.size - 1
        while (hi - lo > 1) { val mid = (lo + hi) / 2; if (points[mid].calBp <= calBp) lo = mid else hi = mid }
        val a = points[lo]; val b = points[hi]
        val f = (calBp - a.calBp) / (b.calBp - a.calBp)
        return CurvePoint(calBp, a.c14Age + f * (b.c14Age - a.c14Age),
            a.c14Sigma + f * (b.c14Sigma - a.c14Sigma)) to false
    }

    private fun extrapolate(a: CurvePoint, b: CurvePoint, calBp: Double, boundary: Boolean): Pair<CurvePoint, Boolean> {
        val f = (calBp - a.calBp) / (b.calBp - a.calBp)
        return CurvePoint(calBp, a.c14Age + f * (b.c14Age - a.c14Age),
            a.c14Sigma + f * (b.c14Sigma - a.c14Sigma)) to boundary
    }
}

// ---------- 储库偏移分布 ----------

data class OffsetComponent(val weight: Double, val mean: Double, val sigma: Double)

/** 储库偏移 ΔR 分布，单位：14C years。支持高斯或高斯混合。 */
data class OffsetDistribution(val components: List<OffsetComponent>) {
    init {
        require(components.isNotEmpty())
        require(components.all { it.sigma > 0 && it.weight > 0 })
    }
    val totalWeight get() = components.sumOf { it.weight }
    companion object { fun gaussian(mean: Double, sigma: Double) = OffsetDistribution(listOf(OffsetComponent(1.0, mean, sigma))) }
}

// ---------- 密度网格（多峰分布，不压成平均年） ----------

data class GridSpec(val startCalBp: Double, val endCalBp: Double, val step: Double) {
    val size get() = ((endCalBp - startCalBp) / step).toInt() + 1
    fun at(i: Int) = startCalBp + i * step
    fun fingerprint() = "%.3f:%.3f:%.3f".format(startCalBp, endCalBp, step)
}

data class DensityGrid(
    val spec: GridSpec,
    val density: DoubleArray,          // 已归一化（梯形积分 = 1）
    val boundaryInterpolated: Boolean, // 是否触发边界插值
    val diagnostics: List<String>      // 无法转换/越界等诊断，留在原位置
) {
    fun at(i: Int) = spec.at(i)
    /** 梯形积分求 [fromBp, toBp] 区间概率，并给出积分证据。 */
    fun probabilityWithEvidence(fromBp: Double, toBp: Double): IntervalEvidence {
        val lo = minOf(fromBp, toBp); val hi = maxOf(fromBp, toBp)
        val points = mutableListOf<EvidencePoint>()
        var total = 0.0
        fun dens(t: Double): Double {
            if (t <= spec.startCalBp) return density.first()
            if (t >= spec.endCalBp) return density.last()
            val pos = (t - spec.startCalBp) / spec.step
            val i = pos.toInt().coerceIn(0, density.size - 2)
            val f = pos - i
            return density[i] + f * (density[i + 1] - density[i])
        }
        var t = lo
        while (t < hi - 1e-12) {
            val next = minOf(hi, spec.startCalBp + (kotlin.math.floor((t - spec.startCalBp) / spec.step) + 1) * spec.step)
            val d0 = dens(t); val d1 = dens(next)
            val mass = (d0 + d1) / 2.0 * (next - t)
            points.add(EvidencePoint(t, next, d0, d1, mass))
            total += mass
            t = next
        }
        return IntervalEvidence(lo, hi, total, points)
    }
}

data class EvidencePoint(val fromCalBp: Double, val toCalBp: Double, val dFrom: Double, val dTo: Double, val mass: Double)
data class IntervalEvidence(val fromCalBp: Double, val toCalBp: Double, val probability: Double, val points: List<EvidencePoint>)

/** 一个日历区间（峰），概率来自网格积分。 */
data class CalInterval(val fromCalBp: Double, val toCalBp: Double, val probability: Double, val level: Double)

// ---------- 输入（不可覆盖，修订 = 新版本 + 父版本） ----------

enum class InputKind { MEASUREMENT, CURVE, OFFSET, ERA_SYSTEM, STRATIGRAPHY, EXCLUSION }

data class InputRecord(
    val id: String, val kind: InputKind, val version: Int, val parentId: String?,
    val sourceRef: String, val payload: String, val createdSeq: Long
)

// ---------- 派生结果（带算法版本、参数指纹、直接来源；双时态可见性） ----------

enum class ResultKind { CALIBRATION, INTERVAL_SET, ADJUSTMENT_CANDIDATE, DECISION, CONFLICT }
enum class ResultStatus { CURRENT, STALE, SUPERSEDED }

data class ResultRecord(
    val id: String, val kind: ResultKind, val algoVersion: String, val paramFingerprint: String,
    val sources: List<String>, val payload: String, val status: ResultStatus,
    val validFromSeq: Long, val validToSeq: Long?, val createdSeq: Long
)

// ---------- 事件（机器可读取的判断依据） ----------

data class Event(
    val seq: Long, val id: String, val type: String, val at: Instant,
    val actor: String, val payload: String
)
