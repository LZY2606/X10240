package app

import kotlinx.serialization.json.JsonObject
import app.JsonUtil.obj
import app.JsonUtil.str
import app.JsonUtil.num
import app.JsonUtil.bool
import app.JsonUtil.reqString
import app.JsonUtil.optString
import app.JsonUtil.reqInt
import app.JsonUtil.reqLong
import app.JsonUtil.reqObject
import app.JsonUtil.reqArray
import app.JsonUtil.reqDouble
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

fun JsonObject.reqLong(key: String): Long =
    (this[key] as? JsonPrimitive)?.longOrNull ?: throw IllegalArgumentException("字段 $key 不是整数")

/** 事件：一切状态变化的唯一事实来源。 */
data class Event(val seq: Long, val id: String, val type: String, val payload: JsonObject, val clock: Long)

/** 源数据版本：原始表示一经提交不可覆盖，修订以新版本+父版本表达。 */
data class SourceVersion(
    val id: String,
    val kind: String,
    val version: Int,
    val parent: String?,
    val provenance: String,
    val payload: JsonObject,
    val submittedAt: Long
) {
    fun toJson() = obj(
        "id" to str(id), "kind" to str(kind), "version" to num(version),
        "parent" to (parent?.let { str(it) } ?: JsonPrimitive(null as String?)),
        "provenance" to str(provenance), "submittedAt" to num(submittedAt),
        "payload" to payload
    )

    companion object {
        fun fromJson(o: JsonObject) = SourceVersion(
            id = o.reqString("id"), kind = o.reqString("kind"), version = o.reqInt("version"),
            parent = o.optString("parent"), provenance = o.reqString("provenance"),
            submittedAt = o.reqLong("submittedAt"), payload = o.reqObject("payload")
        )
    }
}

/** 区间积分证据：任一区间概率可展开的机器可读依据。 */
data class IntervalEvidence(
    val level: Double,
    val startCalBP: Int,
    val endCalBP: Int,
    val points: Int,
    val rawSum: Double,
    val totalSum: Double,
    val probability: Double
) {
    fun toJson() = obj(
        "level" to num(level), "startCalBP" to num(startCalBP), "endCalBP" to num(endCalBP),
        "startEra" to str(Era.label(startCalBP)), "endEra" to str(Era.label(endCalBP)),
        "points" to num(points), "rawSum" to num(rawSum), "totalSum" to num(totalSum),
        "probability" to num(probability)
    )

    companion object {
        fun fromJson(o: JsonObject) = IntervalEvidence(
            level = o.reqDouble("level"), startCalBP = o.reqInt("startCalBP"),
            endCalBP = o.reqInt("endCalBP"), points = o.reqInt("points"),
            rawSum = o.reqDouble("rawSum"), totalSum = o.reqDouble("totalSum"),
            probability = o.reqDouble("probability")
        )
    }
}

data class Peak(val calBP: Int, val density: Double) {
    fun toJson() = obj(
        "calBP" to num(calBP), "era" to str(Era.label(calBP)), "density" to num(density)
    )

    companion object {
        fun fromJson(o: JsonObject) = Peak(o.reqInt("calBP"), o.reqDouble("density"))
    }
}

/** 校准结果：完整多峰密度作为一等数据保存，绝不压缩为平均年。 */
data class CalResult(
    val id: String,
    val measurementId: String, val measurementVersion: Int,
    val curveId: String, val curveVersion: Int,
    val offsetId: String?, val offsetVersion: Int?,
    val algorithmVersion: String,
    val paramsHash: String,
    val gridStart: Int, val gridEnd: Int, val gridStep: Int,
    val density: List<Double>,
    val totalSum: Double,
    val densityHash: String,
    val peaks: List<Peak>,
    val intervals68: List<IntervalEvidence>,
    val intervals95: List<IntervalEvidence>,
    val diagnostics: List<String>,
    val computedAt: Long
) {
    /** 依赖身份：角色 -> 源id@版本 */
    fun deps(): Map<String, String> {
        val m = linkedMapOf(
            "measurement" to "$measurementId@$measurementVersion",
            "curve" to "$curveId@$curveVersion"
        )
        if (offsetId != null) m["offset"] = "$offsetId@$offsetVersion"
        return m
    }

    fun toJson() = obj(
        "id" to str(id),
        "measurementId" to str(measurementId), "measurementVersion" to num(measurementVersion),
        "curveId" to str(curveId), "curveVersion" to num(curveVersion),
        "offsetId" to (offsetId?.let { str(it) } ?: JsonPrimitive(null as String?)),
        "offsetVersion" to (offsetVersion?.let { num(it) } ?: JsonPrimitive(null as String?)),
        "algorithmVersion" to str(algorithmVersion),
        "paramsHash" to str(paramsHash),
        "gridStart" to num(gridStart), "gridEnd" to num(gridEnd), "gridStep" to num(gridStep),
        "density" to JsonArray(density.map { num(it) }),
        "totalSum" to num(totalSum), "densityHash" to str(densityHash),
        "peaks" to JsonArray(peaks.map { it.toJson() }),
        "intervals68" to JsonArray(intervals68.map { it.toJson() }),
        "intervals95" to JsonArray(intervals95.map { it.toJson() }),
        "diagnostics" to JsonArray(diagnostics.map { str(it) }),
        "computedAt" to num(computedAt)
    )

    companion object {
        fun fromJson(o: JsonObject): CalResult {
            fun d(key: String) = o.reqDouble(key)
            return CalResult(
                id = o.reqString("id"),
                measurementId = o.reqString("measurementId"), measurementVersion = o.reqInt("measurementVersion"),
                curveId = o.reqString("curveId"), curveVersion = o.reqInt("curveVersion"),
                offsetId = o.optString("offsetId"),
                offsetVersion = (o["offsetVersion"] as? JsonPrimitive)?.content?.toIntOrNull(),
                algorithmVersion = o.reqString("algorithmVersion"),
                paramsHash = o.reqString("paramsHash"),
                gridStart = o.reqInt("gridStart"), gridEnd = o.reqInt("gridEnd"), gridStep = o.reqInt("gridStep"),
                density = o.reqArray("density").map { (it as JsonPrimitive).doubleOrNull ?: 0.0 },
                totalSum = d("totalSum"), densityHash = o.reqString("densityHash"),
                peaks = o.reqArray("peaks").map { Peak.fromJson(it as JsonObject) },
                intervals68 = o.reqArray("intervals68").map { IntervalEvidence.fromJson(it as JsonObject) },
                intervals95 = o.reqArray("intervals95").map { IntervalEvidence.fromJson(it as JsonObject) },
                diagnostics = o.reqArray("diagnostics").map { (it as JsonPrimitive).content },
                computedAt = o.reqLong("computedAt")
            )
        }
    }
}

/** 层序冲突的候选调整：只产生候选，不改写测量。 */
data class Candidate(
    val id: String,
    val kind: String,          // exclude-source | relax-constraint
    val target: String,
    val rationale: String,
    var status: String = "pending"   // pending | accepted | rejected | superseded
) {
    fun toJson() = obj(
        "id" to str(id), "kind" to str(kind), "target" to str(target),
        "rationale" to str(rationale), "status" to str(status)
    )

    companion object {
        fun fromJson(o: JsonObject) = Candidate(
            o.reqString("id"), o.reqString("kind"), o.reqString("target"),
            o.reqString("rationale"), o.reqString("status")
        )
    }
}

data class Conflict(
    val id: String,
    val constraintDesc: String,
    val olderSource: String,
    val youngerSource: String,
    val probability: Double,
    val candidates: List<Candidate>,
    var status: String = "open",     // open | resolved | superseded
    val createdAt: Long
) {
    fun toJson() = obj(
        "id" to str(id), "constraintDesc" to str(constraintDesc),
        "olderSource" to str(olderSource), "youngerSource" to str(youngerSource),
        "probability" to num(probability),
        "candidates" to JsonArray(candidates.map { it.toJson() }),
        "status" to str(status), "createdAt" to num(createdAt)
    )

    companion object {
        fun fromJson(o: JsonObject) = Conflict(
            o.reqString("id"), o.reqString("constraintDesc"), o.reqString("olderSource"),
            o.reqString("youngerSource"), o.reqDouble("probability"),
            o.reqArray("candidates").map { Candidate.fromJson(it as JsonObject) },
            o.reqString("status"), o.reqLong("createdAt")
        )
    }
}

data class Decision(
    val id: String,
    val targetType: String,   // result | candidate | source
    val targetId: String,
    val action: String,       // accept | reject | adjust
    val reason: String,
    val reviewer: String,
    val at: Long,
    var undone: Boolean = false,
    var conflicting: Boolean = false
) {
    fun toJson() = obj(
        "id" to str(id), "targetType" to str(targetType), "targetId" to str(targetId),
        "action" to str(action), "reason" to str(reason), "reviewer" to str(reviewer),
        "at" to num(at)
    )

    companion object {
        fun fromJson(o: JsonObject) = Decision(
            o.reqString("id"), o.reqString("targetType"), o.reqString("targetId"),
            o.reqString("action"), o.reqString("reason"), o.reqString("reviewer"), o.reqLong("at")
        )
    }
}

/** 机器可读的判断依据事件。 */
data class Judgment(
    val id: String,
    val principle: String,
    val evidence: JsonObject,
    val at: Long
) {
    fun toJson() = obj(
        "id" to str(id), "principle" to str(principle), "evidence" to evidence, "at" to num(at)
    )

    companion object {
        fun fromJson(o: JsonObject) = Judgment(
            o.reqString("id"), o.reqString("principle"), o.reqObject("evidence"), o.reqLong("at")
        )
    }
}

data class Diagnostic(
    val id: String, val subject: String, val message: String, val at: Long
) {
    fun toJson() = obj(
        "id" to str(id), "subject" to str(subject), "message" to str(message), "at" to num(at)
    )

    companion object {
        fun fromJson(o: JsonObject) = Diagnostic(
            o.reqString("id"), o.reqString("subject"), o.reqString("message"), o.reqLong("at")
        )
    }
}

/** BCE/CE 与 calBP 换算：无零年。 */
object Era {
    fun label(calBP: Int): String {
        val y = 1950 - calBP
        return if (y >= 1) "CE $y" else "BCE ${1 - y}"
    }
}
