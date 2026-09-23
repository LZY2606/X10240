package rcbench

import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import java.sql.Connection
import java.sql.DriverManager
import java.util.concurrent.atomic.AtomicLong

interface Clock {
    fun now(): String
}

object SystemClock : Clock {
    override fun now(): String = java.time.Instant.now().toString()
}

class FixedClock(private val instant: String) : Clock {
    override fun now(): String = instant
}

val json = Json { prettyPrint = false; ignoreUnknownKeys = false }

class Projection {
    val measurements = mutableMapOf<String, MutableList<Measurement>>()
    val curves = mutableMapOf<String, MutableList<CalibrationCurve>>()
    val offsets = mutableMapOf<String, MutableList<ReservoirOffset>>()
    val eras = mutableMapOf<String, MutableList<EraSystem>>()
    val constraints = mutableMapOf<String, MutableList<SequenceConstraint>>()
    val exclusions = mutableMapOf<String, MutableList<ExclusionDecision>>()
    val results = mutableMapOf<String, CalibrationResult>()
    val analyses = mutableMapOf<String, SequenceAnalysis>()
    val decisions = mutableMapOf<String, ReviewDecision>()

    fun currentMeasurement(id: String) = measurements[id]?.maxByOrNull { it.version }
    fun currentCurve(id: String) = curves[id]?.maxByOrNull { it.version }
    fun currentOffset(id: String) = offsets[id]?.maxByOrNull { it.version }
    fun currentEra(id: String) = eras[id]?.maxByOrNull { it.version }
    fun currentConstraint(id: String) = constraints[id]?.maxByOrNull { it.version }

    fun currentVersionOf(id: String): Int? =
        measurements[id]?.maxOf { it.version }
            ?: curves[id]?.maxOf { it.version }
            ?: offsets[id]?.maxOf { it.version }
            ?: eras[id]?.maxOf { it.version }
            ?: constraints[id]?.maxOf { it.version }
            ?: exclusions[id]?.maxOf { it.version }

    fun staleReasons(deps: List<String>): List<String> {
        val reasons = mutableListOf<String>()
        for (ref in deps) {
            val (id, v) = parseRef(ref)
            val cur = currentVersionOf(id)
            if (cur != null && cur != v) reasons.add("$id 已更新至 v$cur（本结果基于 v$v）")
        }
        return reasons
    }

    fun isStale(deps: List<String>) = staleReasons(deps).isNotEmpty()

    fun activeDecisions() = decisions.values.filter { !it.undone }
}

class Store(private val dbPath: String, val clock: Clock) {
    private val conn: Connection
    val projection = Projection()
    private val idCounter = AtomicLong(0)

    init {
        conn = DriverManager.getConnection("jdbc:sqlite:$dbPath")
        conn.createStatement().use { st ->
            st.execute(
                """CREATE TABLE IF NOT EXISTS events(
                     seq INTEGER PRIMARY KEY,
                     id TEXT NOT NULL,
                     type TEXT NOT NULL,
                     payload TEXT NOT NULL,
                     at TEXT NOT NULL,
                     actor TEXT NOT NULL
                   )"""
            )
        }
        conn.createStatement().use { st ->
            st.executeQuery("SELECT COALESCE(MAX(seq),0) FROM events").use { rs ->
                if (rs.next()) idCounter.set(rs.getLong(1) * 1000)
            }
        }
        replay()
    }

    fun nextId(prefix: String): String {
        val n = idCounter.incrementAndGet()
        return "$prefix-" + Calibration.sha256("$prefix:$n").substring(0, 10)
    }

    private fun replay() {
        conn.createStatement().use { st ->
            st.executeQuery("SELECT seq,id,type,payload,at,actor FROM events ORDER BY seq").use { rs ->
                while (rs.next()) {
                    apply(
                        Event(
                            seq = rs.getLong(1), id = rs.getString(2), type = rs.getString(3),
                            payload = rs.getString(4), at = rs.getString(5), actor = rs.getString(6),
                        )
                    )
                }
            }
        }
    }

    fun append(type: String, payloadObj: Any, actor: String = "system"): Event {
        val payload = when (payloadObj) {
            is Measurement -> json.encodeToString(Measurement.serializer(), payloadObj)
            is CalibrationCurve -> json.encodeToString(CalibrationCurve.serializer(), payloadObj)
            is ReservoirOffset -> json.encodeToString(ReservoirOffset.serializer(), payloadObj)
            is EraSystem -> json.encodeToString(EraSystem.serializer(), payloadObj)
            is SequenceConstraint -> json.encodeToString(SequenceConstraint.serializer(), payloadObj)
            is ExclusionDecision -> json.encodeToString(ExclusionDecision.serializer(), payloadObj)
            is CalibrationResult -> json.encodeToString(CalibrationResult.serializer(), payloadObj)
            is SequenceAnalysis -> json.encodeToString(SequenceAnalysis.serializer(), payloadObj)
            is ReviewDecision -> json.encodeToString(ReviewDecision.serializer(), payloadObj)
            else -> error("unsupported payload ${payloadObj.javaClass}")
        }
        val ev = Event(
            seq = 0,
            id = nextId("ev"),
            type = type,
            payload = payload,
            at = clock.now(),
            actor = actor,
        )
        conn.prepareStatement("INSERT INTO events(id,type,payload,at,actor) VALUES(?,?,?,?,?)").use { ps ->
            ps.setString(1, ev.id)
            ps.setString(2, ev.type)
            ps.setString(3, ev.payload)
            ps.setString(4, ev.at)
            ps.setString(5, ev.actor)
            ps.executeUpdate()
        }
        val seq = conn.createStatement().use { st ->
            st.executeQuery("SELECT last_insert_rowid()").use { rs -> rs.next(); rs.getLong(1) }
        }
        val stored = ev.copy(seq = seq)
        apply(stored)
        return stored
    }

    fun apply(ev: Event) {
        val p = projection
        when (ev.type) {
            "Measurement" -> {
                val m = json.decodeFromString(Measurement.serializer(), ev.payload)
                p.measurements.getOrPut(m.id) { mutableListOf() }.add(m)
            }
            "Curve" -> {
                val c = json.decodeFromString(CalibrationCurve.serializer(), ev.payload)
                p.curves.getOrPut(c.id) { mutableListOf() }.add(c)
            }
            "Offset" -> {
                val o = json.decodeFromString(ReservoirOffset.serializer(), ev.payload)
                p.offsets.getOrPut(o.id) { mutableListOf() }.add(o)
            }
            "Era" -> {
                val e = json.decodeFromString(EraSystem.serializer(), ev.payload)
                p.eras.getOrPut(e.id) { mutableListOf() }.add(e)
            }
            "Constraint" -> {
                val c = json.decodeFromString(SequenceConstraint.serializer(), ev.payload)
                p.constraints.getOrPut(c.id) { mutableListOf() }.add(c)
            }
            "Exclusion" -> {
                val x = json.decodeFromString(ExclusionDecision.serializer(), ev.payload)
                p.exclusions.getOrPut(x.id) { mutableListOf() }.add(x)
            }
            "Calibration" -> {
                val r = json.decodeFromString(CalibrationResult.serializer(), ev.payload)
                p.results[r.id] = r
            }
            "SequenceAnalysis" -> {
                val a = json.decodeFromString(SequenceAnalysis.serializer(), ev.payload)
                p.analyses[a.id] = a
            }
            "Decision" -> {
                val d = json.decodeFromString(ReviewDecision.serializer(), ev.payload)
                p.decisions[d.id] = d
            }
            "Undo" -> {
                val d = json.decodeFromString(ReviewDecision.serializer(), ev.payload)
                p.decisions[d.id] = d
            }
        }
    }

    fun events(): List<Event> {
        val out = mutableListOf<Event>()
        conn.createStatement().use { st ->
            st.executeQuery("SELECT seq,id,type,payload,at,actor FROM events ORDER BY seq").use { rs ->
                while (rs.next()) {
                    out.add(
                        Event(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6))
                    )
                }
            }
        }
        return out
    }

    fun export(): String = json.encodeToString(ExportBundle.serializer(), ExportBundle("rcbench-export/v1", events()))

    fun importEvents(bundleJson: String): Int {
        val bundle = json.decodeFromString(ExportBundle.serializer(), bundleJson)
        require(bundle.format == "rcbench-export/v1") { "未知导出格式: ${bundle.format}" }
        var count = 0
        conn.autoCommit = false
        try {
            for (ev in bundle.events.sortedBy { it.seq }) {
                conn.prepareStatement("INSERT INTO events(seq,id,type,payload,at,actor) VALUES(?,?,?,?,?,?)").use { ps ->
                    ps.setLong(1, ev.seq)
                    ps.setString(2, ev.id)
                    ps.setString(3, ev.type)
                    ps.setString(4, ev.payload)
                    ps.setString(5, ev.at)
                    ps.setString(6, ev.actor)
                    ps.executeUpdate()
                }
                apply(ev)
                count++
            }
            conn.commit()
        } catch (e: Exception) {
            conn.rollback()
            throw e
        } finally {
            conn.autoCommit = true
        }
        return count
    }

    fun close() = conn.close()
}
