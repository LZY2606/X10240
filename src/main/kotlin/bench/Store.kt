package bench

import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant

/**
 * 事件溯源存储：
 * - inputs 不可覆盖，修订 = 新行（version+1, parentId 指向旧版）
 * - results 双时态（validFromSeq/validToSeq），过期可查看但不冒充当前
 * - events 追加式，机器可读取
 */
class Store(val dbPath: String, val clock: Clock) {
    val conn: Connection = DriverManager.getConnection("jdbc:sqlite:$dbPath")

    init {
        conn.createStatement().use { st ->
            st.executeUpdate("PRAGMA journal_mode=WAL")
            st.executeUpdate("""CREATE TABLE IF NOT EXISTS inputs(
                id TEXT PRIMARY KEY, kind TEXT NOT NULL, version INT NOT NULL,
                parent_id TEXT, source_ref TEXT NOT NULL, payload TEXT NOT NULL, created_seq INT NOT NULL)""")
            st.executeUpdate("""CREATE TABLE IF NOT EXISTS events(
                seq INTEGER PRIMARY KEY AUTOINCREMENT, id TEXT NOT NULL, type TEXT NOT NULL,
                at TEXT NOT NULL, actor TEXT NOT NULL, payload TEXT NOT NULL)""")
            st.executeUpdate("""CREATE TABLE IF NOT EXISTS results(
                id TEXT PRIMARY KEY, kind TEXT NOT NULL, algo_version TEXT NOT NULL,
                param_fingerprint TEXT NOT NULL, sources TEXT NOT NULL, payload TEXT NOT NULL,
                status TEXT NOT NULL, valid_from INT NOT NULL, valid_to INT, created_seq INT NOT NULL)""")
        }
    }

    fun close() = conn.close()

    // ---------- 事件 ----------

    fun recordEvent(type: String, actor: String, payload: Map<String, Any?>): Event {
        val at = clock.now()
        val id = Ids.of("evt", "$type|${at.toEpochMilli()}|${Json.to(payload)}|${nextSeqPeek()}")
        conn.prepareStatement("INSERT INTO events(id,type,at,actor,payload) VALUES(?,?,?,?,?)").use { ps ->
            ps.prepareStatement.let { }
            ps.setString(1, id); ps.setString(2, type); ps.setString(3, at.toString())
            ps.setString(4, actor); ps.setString(5, Json.to(payload))
            ps.executeUpdate()
        }
        val seq = conn.createStatement().use { st ->
            st.executeQuery("SELECT last_insert_rowid()").let { rs -> rs.next(); rs.getLong(1) }
        }
        return Event(seq, id, type, at, actor, Json.to(payload))
    }

    private fun nextSeqPeek(): Long = conn.createStatement().use { st ->
        st.executeQuery("SELECT COALESCE(MAX(seq),0)+1 FROM events").let { rs -> rs.next(); rs.getLong(1) }
    }

    fun events(): List<Event> = conn.createStatement().use { st ->
        st.executeQuery("SELECT seq,id,type,at,actor,payload FROM events ORDER BY seq").let { rs ->
            val out = mutableListOf<Event>()
            while (rs.next()) out.add(Event(rs.getLong(1), rs.getString(2), rs.getString(3),
                Instant.parse(rs.getString(4)), rs.getString(5), rs.getString(6)))
            out
        }
    }

    // ---------- 输入 ----------

    /** 提交原始输入（version=1）或修订（parentId 指向旧版）。原始输入一经提交不可覆盖。 */
    fun submitInput(kind: InputKind, payload: Map<String, Any?>, sourceRef: String,
                    parentId: String? = null, actor: String = "system"): InputRecord {
        val parent = parentId?.let { getInput(it) ?: throw IllegalArgumentException("parent input not found: $it") }
        val version = (parent?.version ?: 0) + 1
        val id = Ids.of(kind.name.lowercase(), Json.to(payload) + "|v$version|" + (parentId ?: ""))
        if (getInput(id) != null) return getInput(id)!!  // 幂等
        val ev = recordEvent(if (parent == null) "input.submitted" else "input.revised", actor,
            mapOf("inputId" to id, "kind" to kind.name, "version" to version, "parentId" to parentId, "sourceRef" to sourceRef))
        conn.prepareStatement("INSERT INTO inputs(id,kind,version,parent_id,source_ref,payload,created_seq) VALUES(?,?,?,?,?,?,?)").use { ps ->
            ps.setString(1, id); ps.setString(2, kind.name); ps.setInt(3, version)
            ps.setString(4, parentId); ps.setString(5, sourceRef); ps.setString(6, Json.to(payload)); ps.setLong(7, ev.seq)
            ps.executeUpdate()
        }
        if (parent != null) staleDependentsOf(parent.id, ev.seq, "input ${parent.id} revised to $id")
        return InputRecord(id, kind, version, parentId, sourceRef, Json.to(payload), ev.seq)
    }

    fun getInput(id: String): InputRecord? = conn.prepareStatement("SELECT * FROM inputs WHERE id=?").use { ps ->
        ps.setString(1, id)
        ps.executeQuery().let { rs ->
            if (!rs.next()) null else InputRecord(rs.getString("id"), InputKind.valueOf(rs.getString("kind")),
                rs.getInt("version"), rs.getString("parent_id"), rs.getString("source_ref"),
                rs.getString("payload"), rs.getLong("created_seq"))
        }
    }

    fun inputs(kind: InputKind? = null): List<InputRecord> {
        val sql = "SELECT * FROM inputs" + (if (kind != null) " WHERE kind='${kind.name}'" else "") + " ORDER BY created_seq, id"
        return conn.createStatement().use { st ->
            st.executeQuery(sql).let { rs ->
                val out = mutableListOf<InputRecord>()
                while (rs.next()) out.add(InputRecord(rs.getString("id"), InputKind.valueOf(rs.getString("kind")),
                    rs.getInt("version"), rs.getString("parent_id"), rs.getString("source_ref"),
                    rs.getString("payload"), rs.getLong("created_seq")))
                out
            }
        }
    }

    /** 某输入的最新版本。 */
    fun latestVersionOf(inputId: String): InputRecord {
        var cur = getInput(inputId) ?: throw IllegalArgumentException("unknown input $inputId")
        while (true) {
            val child = inputs(cur.kind).firstOrNull { it.parentId == cur.id } ?: return cur
            cur = child
        }
    }

    // ---------- 派生结果 ----------

    fun putResult(kind: ResultKind, algoVersion: String, fingerprint: String, sources: List<String>,
                  payload: Map<String, Any?>, actor: String = "system"): ResultRecord {
        val id = Ids.of(kind.name.lowercase(), fingerprint + "|" + Json.to(payload))
        getResult(id)?.let { return it }  // 幂等：同指纹同内容不重复
        val ev = recordEvent("result.derived", actor,
            mapOf("resultId" to id, "kind" to kind.name, "algoVersion" to algoVersion,
                "paramFingerprint" to fingerprint, "sources" to sources))
        conn.prepareStatement("INSERT INTO results(id,kind,algo_version,param_fingerprint,sources,payload,status,valid_from,valid_to,created_seq) VALUES(?,?,?,?,?,?,?,?,?,?)").use { ps ->
            ps.setString(1, id); ps.setString(2, kind.name); ps.setString(3, algoVersion); ps.setString(4, fingerprint)
            ps.setString(5, Json.to(sources)); ps.setString(6, Json.to(payload)); ps.setString(7, ResultStatus.CURRENT.name)
            ps.setLong(8, ev.seq); ps.setNull(9, java.sql.Types.INTEGER); ps.setLong(10, ev.seq)
            ps.executeUpdate()
        }
        return getResult(id)!!
    }

    fun getResult(id: String): ResultRecord? = conn.prepareStatement("SELECT * FROM results WHERE id=?").use { ps ->
        ps.setString(1, id)
        ps.executeQuery().let { rs -> if (!rs.next()) null else rowToResult(rs) }
    }

    private fun rowToResult(rs: java.sql.ResultSet) = ResultRecord(
        rs.getString("id"), ResultKind.valueOf(rs.getString("kind")), rs.getString("algo_version"),
        rs.getString("param_fingerprint"), Json.gson.fromJson(rs.getString("sources"), Array<String>::class.java).toList(),
        rs.getString("payload"), ResultStatus.valueOf(rs.getString("status")),
        rs.getLong("valid_from"), rs.getObject("valid_to")?.let { (it as Number).toLong() }, rs.getLong("created_seq"))

    fun results(kind: ResultKind? = null, asOfSeq: Long? = null): List<ResultRecord> {
        val conds = mutableListOf<String>()
        if (kind != null) conds.add("kind='${kind.name}'")
        if (asOfSeq != null) conds.add("valid_from <= $asOfSeq AND (valid_to IS NULL OR valid_to > $asOfSeq)")
        val sql = "SELECT * FROM results" + (if (conds.isEmpty()) "" else " WHERE " + conds.joinToString(" AND ")) + " ORDER BY created_seq, id"
        return conn.createStatement().use { st ->
            st.executeQuery(sql).let { rs ->
                val out = mutableListOf<ResultRecord>()
                while (rs.next()) out.add(rowToResult(rs))
                out
            }
        }
    }

    /** 局部过期：仅使直接来源包含 oldSourceId 的结果过期；过期结果仍可查看。 */
    fun staleDependentsOf(oldSourceId: String, atSeq: Long, reason: String, actor: String = "system") {
        val affected = results().filter { it.status == ResultStatus.CURRENT && oldSourceId in it.sources }
        for (r in affected) {
            conn.prepareStatement("UPDATE results SET status=?, valid_to=? WHERE id=?").use { ps ->
                ps.setString(1, ResultStatus.STALE.name); ps.setLong(2, atSeq); ps.setString(3, r.id); ps.executeUpdate()
            }
        }
        if (affected.isNotEmpty()) recordEvent("results.staled", actor,
            mapOf("cause" to reason, "staleSource" to oldSourceId, "affected" to affected.map { it.id }))
    }

    fun supersede(resultId: String, atSeq: Long) {
        conn.prepareStatement("UPDATE results SET status=?, valid_to=? WHERE id=?").use { ps ->
            ps.setString(1, ResultStatus.SUPERSEDED.name); ps.setLong(2, atSeq); ps.setString(3, resultId); ps.executeUpdate()
        }
    }

    // ---------- 导出 / 导入（身份、顺序、来源关系保持） ----------

    fun exportJson(): String {
        val dump = mapOf(
            "format" to "radiocarbon-bench-export/1",
            "exportedAt" to clock.now().toString(),
            "inputs" to inputs().map { mapOf("id" to it.id, "kind" to it.kind.name, "version" to it.version,
                "parentId" to it.parentId, "sourceRef" to it.sourceRef, "payload" to Json.mapOf(it.payload), "createdSeq" to it.createdSeq) },
            "results" to results().map { mapOf("id" to it.id, "kind" to it.kind.name, "algoVersion" to it.algoVersion,
                "paramFingerprint" to it.paramFingerprint, "sources" to it.sources, "payload" to Json.mapOf(it.payload),
                "status" to it.status.name, "validFromSeq" to it.validFromSeq, "validToSeq" to it.validToSeq, "createdSeq" to it.createdSeq) },
            "events" to events().map { mapOf("seq" to it.seq, "id" to it.id, "type" to it.type,
                "at" to it.at.toString(), "actor" to it.actor, "payload" to Json.mapOf(it.payload)) }
        )
        return Json.to(dump)
    }

    /** 导入：按 id 幂等合并；事件按原 seq 顺序重放，保持身份与来源关系。 */
    fun importJson(json: String, actor: String = "import"): ImportReport {
        val doc = Json.mapOf(json)
        if (doc["format"] != "radiocarbon-bench-export/1")
            throw IllegalArgumentException("format-error: 期望 radiocarbon-bench-export/1，实际 ${doc["format"]}")
        var insI = 0; var insR = 0; var insE = 0
        val inputs = doc["inputs"] as? List<*> ?: throw IllegalArgumentException("format-error: 缺少 inputs 数组")
        for (any in inputs) {
            val m = any as Map<*, *>
            val id = m["id"] as? String ?: throw IllegalArgumentException("format-error: input 缺少 id")
            if (getInput(id) != null) continue
            conn.prepareStatement("INSERT INTO inputs(id,kind,version,parent_id,source_ref,payload,created_seq) VALUES(?,?,?,?,?,?,?)").use { ps ->
                ps.setString(1, id); ps.setString(2, m["kind"] as String); ps.setInt(3, (m["version"] as Number).toInt())
                ps.setString(4, m["parentId"] as String?); ps.setString(5, m["sourceRef"] as String)
                ps.setString(6, Json.to(m["payload"])); ps.setLong(7, (m["createdSeq"] as Number).toLong())
                ps.executeUpdate()
            }
            insI++
        }
        val resultsL = doc["results"] as? List<*> ?: throw IllegalArgumentException("format-error: 缺少 results 数组")
        for (any in resultsL) {
            val m = any as Map<*, *>
            val id = m["id"] as? String ?: throw IllegalArgumentException("format-error: result 缺少 id")
            if (getResult(id) != null) continue
            conn.prepareStatement("INSERT INTO results(id,kind,algo_version,param_fingerprint,sources,payload,status,valid_from,valid_to,created_seq) VALUES(?,?,?,?,?,?,?,?,?,?)").use { ps ->
                ps.setString(1, id); ps.setString(2, m["kind"] as String); ps.setString(3, m["algoVersion"] as String)
                ps.setString(4, m["paramFingerprint"] as String); ps.setString(5, Json.to(m["sources"]))
                ps.setString(6, Json.to(m["payload"])); ps.setString(7, m["status"] as String)
                ps.setLong(8, (m["validFromSeq"] as Number).toLong())
                val vt = m["validToSeq"]; if (vt == null) ps.setNull(9, java.sql.Types.INTEGER) else ps.setLong(9, (vt as Number).toLong())
                ps.setLong(10, (m["createdSeq"] as Number).toLong())
                ps.executeUpdate()
            }
            insR++
        }
        val eventsL = doc["events"] as? List<*> ?: throw IllegalArgumentException("format-error: 缺少 events 数组")
        for (any in eventsL) {
            val m = any as Map<*, *>
            val id = m["id"] as? String ?: throw IllegalArgumentException("format-error: event 缺少 id")
            val exists = conn.prepareStatement("SELECT 1 FROM events WHERE id=?").use { ps ->
                ps.setString(1, id); ps.executeQuery().next() }
            if (exists) continue
            conn.prepareStatement("INSERT INTO events(seq,id,type,at,actor,payload) VALUES(?,?,?,?,?,?)").use { ps ->
                ps.setLong(1, (m["seq"] as Number).toLong()); ps.setString(2, id); ps.setString(3, m["type"] as String)
                ps.setString(4, m["at"] as String); ps.setString(5, m["actor"] as String); ps.setString(6, Json.to(m["payload"]))
                ps.executeUpdate()
            }
            insE++
        }
        recordEvent("import.completed", actor, mapOf("inputs" to insI, "results" to insR, "events" to insE))
        return ImportReport(insI, insR, insE)
    }

    data class ImportReport(val inputs: Int, val results: Int, val events: Int)
}
