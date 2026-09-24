package workbench

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.sql.DriverManager
import java.time.Instant

interface Clock { fun now(): String }
class FixedClock(private val instant: Instant) : Clock { override fun now(): String = instant.toString() }
class SystemClock : Clock { override fun now(): String = Instant.now().toString() }

// ---------- 事件 ----------

data class Event(val seq: Long, val id: String, val type: String, val createdAt: String, val payload: JsonObject) {
    inline fun <reified T> decode(): T = Json.decodeFromJsonElement(payload)
}

object EventTypes {
    const val SOURCE_SUBMITTED = "source-submitted"
    const val DERIVED_RECORDED = "derived-recorded"
    const val DERIVED_STALENESS = "derived-staleness"
    const val DECISION_MADE = "decision-made"
    const val DECISION_VOIDED = "decision-voided"
}

@Serializable
data class SourcePayload(
    val id: String, val family: String, val kind: SourceKind, val version: Int,
    val parentId: String? = null, val provenance: String, val payload: String,
    val unit: String, val diagnostics: List<String> = emptyList()
)

@Serializable
data class DerivedPayload(
    val id: String, val kind: String, val algorithmVersion: String,
    val paramFingerprint: String, val sourceIds: List<String>,
    val result: JsonObject, val recordedAtEvent: Long
)

@Serializable
data class StalenessPayload(val derivedId: String, val staleReason: String, val atEvent: Long)

@Serializable
data class DecisionPayload(
    val id: String, val targetType: String,      // "derived" | "source"
    val targetId: String, val labCode: String?,
    val verdict: String,                          // ACCEPT | REJECT | ADJUST
    val reason: String, val author: String,
    val newSourceId: String? = null
)

@Serializable
data class VoidPayload(val decisionId: String, val reason: String)

// ---------- 当前态（由事件折叠得到；历史态 = 前缀折叠） ----------

data class Decision(val p: DecisionPayload, val voidedBy: String?)

data class State(
    val projectId: String,
    val events: List<Event>,
    val sources: Map<String, SourceRecord>,
    val sourceFamily: Map<String, String>,
    val sourceDiag: Map<String, List<String>>,
    val derived: Map<String, DerivedRecord>,
    val derivedResultJson: Map<String, JsonObject>,
    val decisions: List<Decision>
) {
    fun families(): Map<String, List<SourceRecord>> =
        sources.values.groupBy { sourceFamily[it.id] ?: it.id }

    fun latestInFamily(family: String): SourceRecord? =
        sources.values.filter { (sourceFamily[it.id] ?: it.id) == family }.maxByOrNull { it.version }

    fun activeDecisions(): List<Decision> = decisions.filter { it.voidedBy == null }

    /** 同一对象上互相冲突且均未撤销的人工决定。 */
    fun conflicts(): Map<String, List<Decision>> =
        activeDecisions().groupBy { it.p.targetId }
            .filter { (_, ds) -> ds.map { it.p.verdict }.distinct().size > 1 }

    fun isCurrentSource(id: String): Boolean = latestInFamily(sourceFamily[id] ?: id)?.id == id

    fun derivedStatusNow(id: String): DerivedStatus {
        val d = derived[id] ?: return DerivedStatus.UNKNOWN
        if (d.status == DerivedStatus.STALE) return DerivedStatus.STALE
        val conflictTargets = conflicts().keys
        if (conflictTargets.contains(d.id) || d.sourceIds.any { conflictTargets.contains(it) })
            return DerivedStatus.CONFLICTED
        if (sourceIdsStale(d)) return DerivedStatus.STALE
        return DerivedStatus.CURRENT
    }

    private fun sourceIdsStale(d: DerivedRecord): Boolean =
        d.sourceIds.any { sid ->
            val fam = sourceFamily[sid] ?: sid
            val latest = latestInFamily(fam)
            latest != null && latest.id != sid
        }
}

class Store(private val dbPath: String, private val clock: Clock, private val projectId: String = "default-project") : AutoCloseable {
    private val conn = DriverManager.getConnection("jdbc:sqlite:$dbPath")

    init {
        conn.createStatement().executeUpdate(
            """CREATE TABLE IF NOT EXISTS events (
                 seq INTEGER PRIMARY KEY,
                 id TEXT NOT NULL UNIQUE,
                 type TEXT NOT NULL,
                 created_at TEXT NOT NULL,
                 project_id TEXT NOT NULL,
                 payload TEXT NOT NULL
               )"""
        )
        conn.createStatement().executeUpdate(
            "CREATE INDEX IF NOT EXISTS idx_events_seq ON events(seq)"
        )
    }

    fun eventCount(): Int =
        conn.createStatement().executeQuery("SELECT COUNT(*) FROM events").use { rs -> rs.next().let { rs.getInt(1) } }

    fun append(type: String, payloadBuilder: (seq: Long) -> JsonObject): Event {
        conn.autoCommit = false
        return try {
            val seq: Long = conn.createStatement()
                .executeQuery("SELECT COALESCE(MAX(seq),0)+1 FROM events").use { rs -> rs.next(); rs.getLong(1) }
            val id = Ids.of(type.prefix(), seq)
            val payload = payloadBuilder(seq)
            val evt = Event(seq, id, type, clock.now(), payload)
            conn.prepareStatement("INSERT INTO events(seq,id,type,created_at,project_id,payload) VALUES(?,?,?,?,?,?)").use { ps ->
                ps.setLong(1, seq); ps.setString(2, id); ps.setString(3, type)
                ps.setString(4, evt.createdAt); ps.setString(5, projectId)
                ps.setString(6, Json.encodeToString(JsonObject.serializer(), payload))
                ps.executeUpdate()
            }
            conn.commit()
            evt
        } catch (e: Exception) { conn.rollback(); throw e } finally { conn.autoCommit = true }
    }

    fun loadEvents(): List<Event> =
        conn.createStatement()
            .executeQuery("SELECT seq,id,type,created_at,payload FROM events ORDER BY seq")
            .use { rs ->
                val out = mutableListOf<Event>()
                while (rs.next()) {
                    out.add(Event(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        Json.parseToJsonElement(rs.getString(5)).jsonObject))
                }
                out
            }

    fun state(upToSeq: Long = Long.MAX_VALUE): State = fold(loadEvents().filter { it.seq <= upToSeq }, projectId)

    fun wipe() { conn.createStatement().executeUpdate("DELETE FROM events") }

    fun snapshotJson(): String {
        val evts = loadEvents()
        val obj = buildJsonObject {
            put("schemaVersion", "rw-snapshot-1")
            put("projectId", projectId)
            put("exportedAt", clock.now())
            putJsonArray("events") {
                for (e in evts) {
                    add(buildJsonObject {
                        put("seq", e.seq); put("id", e.id); put("type", e.type)
                        put("createdAt", e.createdAt); put("payload", e.payload)
                    })
                }
            }
        }
        return Json.encodeToString(JsonObject.serializer(), obj)
    }

    /** 删除本地库后导入：保留 seq/id/顺序/来源关系，全部失败则整体回滚。 */
    fun restoreSnapshot(jsonText: String): Int {
        val errors = mutableListOf<String>()
        val root = try { Json.parseToJsonElement(jsonText).jsonObject }
        catch (e: Exception) { throw ImportException(listOf("snapshot 不是合法 JSON: ${e.message}")) }
        root["schemaVersion"]?.jsonPrimitive?.contentOrNull.let {
            if (it != "rw-snapshot-1") errors += "缺少或不认识的 schemaVersion"
        }
        val arr = root["events"]?.jsonArray
        if (arr == null) errors += "缺少 events 数组"
        val parsed = mutableListOf<Event>()
        if (errors.isEmpty() && arr != null) {
            arr.forEachIndexed { i, el ->
                val o = el.jsonObject
                val seq = o["seq"]?.jsonPrimitive?.longOrNull
                val id = o["id"]?.jsonPrimitive?.contentOrNull
                val type = o["type"]?.jsonPrimitive?.contentOrNull
                val createdAt = o["createdAt"]?.jsonPrimitive?.contentOrNull
                val payload = o["payload"]?.jsonObjectOrNull
                if (seq == null || id == null || type == null || createdAt == null || payload == null)
                    errors += "events[$i] 字段缺失（需要 seq/id/type/createdAt/payload）"
                else parsed += Event(seq, id, type, createdAt, payload)
            }
            if (parsed.map { it.seq }.toSet().size != parsed.size) errors += "events 中 seq 不唯一"
            if (parsed.zipWithNext().any { it.first.seq >= it.second.seq }) errors += "events 未按 seq 递增排列"
        }
        if (errors.isNotEmpty()) throw ImportException(errors)
        conn.autoCommit = false
        try {
            wipe()
            parsed.forEach { e ->
                conn.prepareStatement("INSERT INTO events(seq,id,type,created_at,project_id,payload) VALUES(?,?,?,?,?,?)").use { ps ->
                    ps.setLong(1, e.seq); ps.setString(2, e.id); ps.setString(3, e.type)
                    ps.setString(4, e.createdAt); ps.setString(5, projectId)
                    ps.setString(6, Json.encodeToString(JsonObject.serializer(), e.payload))
                    ps.executeUpdate()
                }
            }
            conn.commit()
        } catch (e: Exception) { conn.rollback(); throw e } finally { conn.autoCommit = true }
        return parsed.size
    }

    override fun close() = conn.close()

    companion object {
        @Suppress("unused")
        fun prefixOf(type: String): String = type.prefix()
        private fun String.prefix(): String = when (this) {
            EventTypes.SOURCE_SUBMITTED -> "src"
            EventTypes.DERIVED_RECORDED -> "der"
            EventTypes.DERIVED_STALENESS -> "stl"
            EventTypes.DECISION_MADE -> "dec"
            EventTypes.DECISION_VOIDED -> "void"
            else -> "evt"
        }

        fun fold(events: List<Event>, projectId: String = "default-project"): State {
            val sources = LinkedHashMap<String, SourceRecord>()
            val family = LinkedHashMap<String, String>()
            val diag = LinkedHashMap<String, List<String>>()
            val derived = LinkedHashMap<String, DerivedRecord>()
            val results = LinkedHashMap<String, JsonObject>()
            val decisions = mutableListOf<Decision>()
            for (e in events) {
                when (e.type) {
                    EventTypes.SOURCE_SUBMITTED -> {
                        val p: SourcePayload = e.decode()
                        sources[p.id] = SourceRecord(p.id, p.kind, p.version, p.parentId, p.provenance, p.payload, p.unit)
                        family[p.id] = p.family
                        if (p.diagnostics.isNotEmpty()) diag[p.id] = p.diagnostics
                    }
                    EventTypes.DERIVED_RECORDED -> {
                        val p: DerivedPayload = e.decode()
                        derived[p.id] = DerivedRecord(p.id, p.kind, p.algorithmVersion, p.paramFingerprint,
                            p.sourceIds, Json.encodeToString(JsonObject.serializer(), p.result), DerivedStatus.CURRENT)
                        results[p.id] = p.result
                    }
                    EventTypes.DERIVED_STALENESS -> {
                        val p: StalenessPayload = e.decode()
                        derived[p.derivedId]?.let {
                            derived[p.derivedId] = it.copy(status = DerivedStatus.STALE, staleReason = p.staleReason)
                        }
                    }
                    EventTypes.DECISION_MADE -> {
                        val p: DecisionPayload = e.decode()
                        decisions += Decision(p, null)
                    }
                    EventTypes.DECISION_VOIDED -> {
                        val p: VoidPayload = e.decode()
                        val idx = decisions.indexOfLast { it.p.id == p.decisionId }
                        if (idx >= 0) decisions[idx] = decisions[idx].copy(voidedBy = e.id)
                    }
                }
            }
            return State(projectId, events, sources, family, diag, derived, results, decisions)
        }
    }
}

class ImportException(val errors: List<String>) : RuntimeException(errors.joinToString("; "))
