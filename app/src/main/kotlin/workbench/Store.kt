package workbench

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.sql.Connection
import java.sql.DriverManager

/** 项目状态：由事件流折叠而成；可折叠到任意历史事件。 */
class ProjectState {
    val sources = LinkedHashMap<String, SourceRecord>()          // id -> 最新版本
    val sourceVersions = LinkedHashMap<String, MutableList<SourceRecord>>() // 根 id -> 全部版本
    val derived = LinkedHashMap<String, DerivedRecord>()
    val decisions = LinkedHashMap<String, DecisionRecord>()
    val conflicts = LinkedHashMap<String, ConflictRecord>()
    val events = mutableListOf<Event>()

    fun rootOf(id: String): String {
        var cur = sources[id] ?: return id
        while (cur.parentId != null && sources.containsKey(cur.parentId)) cur = sources[cur.parentId]!!
        return cur.id
    }

    /** 派生结果是否过期：其直接来源已有更新版本。 */
    fun isStale(d: DerivedRecord): Boolean {
        if (d.status != "current") return false
        return d.sources.any { ref ->
            val id = ref.substringBefore("@")
            val v = ref.substringAfter("@").toInt()
            val root = rootOf(id)
            (sourceVersions[root]?.maxOf { it.version } ?: v) > v
        }
    }
}

class Store(private val dbPath: String, private val clock: Clock) {
    val conn: Connection

    init {
        conn = DriverManager.getConnection("jdbc:sqlite:$dbPath")
        conn.createStatement().use { st ->
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS events(
                  seq INTEGER PRIMARY KEY AUTOINCREMENT,
                  id TEXT UNIQUE NOT NULL,
                  ts TEXT NOT NULL,
                  kind TEXT NOT NULL,
                  ref_id TEXT NOT NULL,
                  summary TEXT NOT NULL,
                  payload TEXT NOT NULL
                )""".trimIndent())
        }
    }

    fun close() = conn.close()

    private fun nextId(prefix: String, table: String = "events"): String {
        val n = conn.createStatement().use { st ->
            st.executeQuery("SELECT COUNT(*) FROM $table").let { rs -> rs.next(); rs.getLong(1) }
        }
        // 计数同类前缀，保证身份稳定且可重放
        val like = conn.prepareStatement("SELECT COUNT(*) FROM events WHERE id LIKE ?").apply {
            setString(1, "$prefix-%")
        }.executeQuery().let { rs -> rs.next(); rs.getLong(1) }
        return "%s-%04d".format(prefix, like + 1)
    }

    fun appendEvent(kind: String, refId: String, summary: String, payload: JsonObject): Event {
        val id = nextId("evt")
        val ts = clock.now()
        conn.prepareStatement(
            "INSERT INTO events(id,ts,kind,ref_id,summary,payload) VALUES(?,?,?,?,?,?)"
        ).apply {
            setString(1, id); setString(2, ts); setString(3, kind)
            setString(4, refId); setString(5, summary); setString(6, payload.toString())
        }.executeUpdate()
        val seq = conn.createStatement().use { st ->
            st.executeQuery("SELECT seq FROM events WHERE id='$id'").let { rs -> rs.next(); rs.getLong(1) }
        }
        return Event(seq, id, ts, kind, refId, summary, payload)
    }

    fun loadEvents(): List<Event> {
        val out = mutableListOf<Event>()
        conn.createStatement().use { st ->
            val rs = st.executeQuery("SELECT seq,id,ts,kind,ref_id,summary,payload FROM events ORDER BY seq")
            while (rs.next()) {
                out.add(Event(
                    rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                    rs.getString(5), rs.getString(6),
                    JsonFmt.parseToJsonElement(rs.getString(7)) as JsonObject
                ))
            }
        }
        return out
    }

    /** 折叠事件流到指定 seq（含），恢复当时的依赖版本与可见结论。 */
    fun stateAt(maxSeq: Long = Long.MAX_VALUE): ProjectState {
        val st = ProjectState()
        for (e in loadEvents()) {
            if (e.seq > maxSeq) break
            st.events.add(e)
            when (e.kind) {
                "source.registered", "source.revised" -> {
                    val src = JsonFmt.decodeFromJsonElement(SourceRecord.serializer(), e.payload["source"]!!)
                    st.sources[src.id] = src
                    val root = st.rootOf(src.id)
                    st.sourceVersions.getOrPut(root) { mutableListOf() }.add(src)
                }
                "derived.computed" -> {
                    val d = JsonFmt.decodeFromJsonElement(DerivedRecord.serializer(), e.payload["derived"]!!)
                    st.derived[d.id] = d
                }
                "decision.recorded" -> {
                    val d = JsonFmt.decodeFromJsonElement(DecisionRecord.serializer(), e.payload["decision"]!!)
                    st.decisions[d.id] = d
                }
                "undo.recorded" -> {
                    val d = JsonFmt.decodeFromJsonElement(DecisionRecord.serializer(), e.payload["decision"]!!)
                    st.decisions[d.id] = d
                    val target = d.targetId
                    st.decisions[target]?.let { st.decisions[target] = it.copy(undoneBy = d.id) }
                }
                "conflict.raised" -> {
                    val c = JsonFmt.decodeFromJsonElement(ConflictRecord.serializer(), e.payload["conflict"]!!)
                    st.conflicts[c.id] = c
                }
                "conflict.resolved" -> {
                    val id = e.payload["conflictId"]!!.let { (it as JsonPrimitive).content }
                    val by = e.payload["resolvedBy"]!!.let { (it as JsonPrimitive).content }
                    st.conflicts[id]?.let { st.conflicts[id] = it.copy(status = "resolved", resolvedBy = by) }
                }
            }
        }
        return st
    }

    // ---- 领域操作：每个操作写入机器可读事件 ----

    fun registerSource(kind: String, origin: String, raw: String, data: JsonObject,
                       diagnostics: List<String> = emptyList()): SourceRecord {
        val id = nextId("src")
        val src = SourceRecord(id, kind, 1, null, origin, raw, data, diagnostics)
        appendEvent("source.registered", id, "登记来源[$kind] $origin",
            buildJsonObject { put("source", JsonFmt.encodeToJsonElement(SourceRecord.serializer(), src)) })
        return src
    }

    fun reviseSource(parentId: String, raw: String, data: JsonObject,
                     diagnostics: List<String> = emptyList()): SourceRecord {
        val st = stateAt()
        val parent = st.sources[parentId] ?: error("no such source $parentId")
        val id = nextId("src")
        val src = SourceRecord(id, parent.kind, parent.version + 1, parentId, parent.origin, raw, data, diagnostics)
        appendEvent("source.revised", id, "修订来源[${parent.kind}] v${parent.version}→v${src.version}",
            buildJsonObject { put("source", JsonFmt.encodeToJsonElement(SourceRecord.serializer(), src)) })
        return src
    }

    fun computeDerived(kind: String, algoVersion: String, sources: List<SourceRecord>,
                       payload: JsonObject, status: String = "current"): DerivedRecord =
        computeDerivedByRefs(kind, algoVersion, sources.map { "${it.id}@${it.version}" }, payload, status)

    fun computeDerivedByRefs(kind: String, algoVersion: String, refs: List<String>,
                             payload: JsonObject, status: String = "current"): DerivedRecord {
        val fp = sha256(algoVersion + "|" + refs.sorted().joinToString(","))
        val id = nextId("drv")
        val d = DerivedRecord(id, kind, algoVersion, fp, refs, payload, status)
        appendEvent("derived.computed", id, "计算派生[$kind] 算法=$algoVersion 指纹=${fp.take(12)}…",
            buildJsonObject { put("derived", JsonFmt.encodeToJsonElement(DerivedRecord.serializer(), d)) })
        return d
    }

    fun recordDecision(action: String, targetId: String, rationale: String): DecisionRecord {
        val id = nextId("dec")
        val d = DecisionRecord(id, action, targetId, rationale)
        appendEvent("decision.recorded", id, "人工裁定[$action] 对象=$targetId：$rationale",
            buildJsonObject { put("decision", JsonFmt.encodeToJsonElement(DecisionRecord.serializer(), d)) })
        return d
    }

    fun recordUndo(decisionId: String, rationale: String): DecisionRecord {
        val id = nextId("dec")
        val d = DecisionRecord(id, "undo", decisionId, rationale)
        appendEvent("undo.recorded", id, "撤销裁定 $decisionId：$rationale",
            buildJsonObject { put("decision", JsonFmt.encodeToJsonElement(DecisionRecord.serializer(), d)) })
        return d
    }

    fun raiseConflict(kind: String, involved: List<String>, candidates: List<JsonObject>): ConflictRecord {
        val id = nextId("cfl")
        val c = ConflictRecord(id, kind, involved, candidates)
        appendEvent("conflict.raised", id, "冲突[$kind] 涉及=${involved.joinToString()} 候选=${candidates.size}个",
            buildJsonObject { put("conflict", JsonFmt.encodeToJsonElement(ConflictRecord.serializer(), c)) })
        return c
    }

    fun resolveConflict(conflictId: String, decisionId: String) {
        appendEvent("conflict.resolved", conflictId, "冲突 $conflictId 由裁定 $decisionId 解决",
            buildJsonObject { put("conflictId", conflictId); put("resolvedBy", decisionId) })
    }
}
