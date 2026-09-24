package org.chrono.workbench.db

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.chrono.workbench.domain.CalibrationInput
import org.chrono.workbench.domain.CalibrationResult
import org.chrono.workbench.events.StoredEvent
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager

class SqliteStore(private val dbPath: Path) {
    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    private lateinit var conn: Connection

    fun connect() {
        Files.createDirectories(dbPath.toAbsolutePath().parent)
        conn = DriverManager.getConnection("jdbc:sqlite:${dbPath.toAbsolutePath()}")
        conn.createStatement().execute("PRAGMA foreign_keys=ON")
        migrate()
    }

    private fun migrate() {
        conn.createStatement().use { st ->
            st.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS meta(
                  k TEXT PRIMARY KEY,
                  v TEXT NOT NULL
                )
                """.trimIndent()
            )
            st.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS events(
                  seq INTEGER PRIMARY KEY,
                  event_id TEXT UNIQUE NOT NULL,
                  parent_event_id TEXT,
                  type TEXT NOT NULL,
                  at_instant TEXT NOT NULL,
                  actor TEXT NOT NULL,
                  summary TEXT NOT NULL,
                  payload TEXT NOT NULL,
                  sources TEXT NOT NULL,
                  affected TEXT NOT NULL,
                  reversed_event_id TEXT,
                  replay_tag TEXT
                )
                """.trimIndent()
            )
            st.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS sources(
                  id TEXT NOT NULL,
                  kind TEXT NOT NULL,
                  seq INTEGER NOT NULL,
                  body TEXT NOT NULL,
                  PRIMARY KEY(kind, id)
                )
                """.trimIndent()
            )
            st.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS derived(
                  derived_id TEXT PRIMARY KEY,
                  input_json TEXT NOT NULL,
                  result_json TEXT NOT NULL,
                  created_event_seq INTEGER NOT NULL,
                  fingerprint TEXT NOT NULL
                )
                """.trimIndent()
            )
        }
    }

    @Synchronized
    fun appendEvent(event: StoredEvent) {
        conn.prepareStatement(
            """
            INSERT INTO events(seq,event_id,parent_event_id,type,at_instant,actor,summary,payload,sources,affected,reversed_event_id,replay_tag)
            VALUES(?,?,?,?,?,?,?,?,?,?,?,?)
            """.trimIndent()
        ).use { ps ->
            ps.setInt(1, event.seq)
            ps.setString(2, event.eventId)
            ps.setString(3, event.parentEventId)
            ps.setString(4, event.type)
            ps.setString(5, event.atInstant)
            ps.setString(6, event.actor)
            ps.setString(7, event.summary)
            ps.setString(8, json.encodeToString(event.payload))
            ps.setString(9, json.encodeToString(event.sources))
            ps.setString(10, json.encodeToString(event.affectedObjectIds))
            ps.setString(11, event.reversedEventId)
            ps.setString(12, event.replayTag)
            ps.executeUpdate()
        }
    }

    @Synchronized
    fun loadEvents(): List<StoredEvent> {
        val out = mutableListOf<StoredEvent>()
        conn.prepareStatement("SELECT * FROM events ORDER BY seq ASC").use { ps ->
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    out.add(
                        StoredEvent(
                            seq = rs.getInt("seq"),
                            eventId = rs.getString("event_id"),
                            parentEventId = rs.getString("parent_event_id"),
                            type = rs.getString("type"),
                            atInstant = rs.getString("at_instant"),
                            actor = rs.getString("actor"),
                            summary = rs.getString("summary"),
                            payload = json.parseToJsonElement(rs.getString("payload")),
                            sources = json.decodeFromString(rs.getString("sources")),
                            affectedObjectIds = json.decodeFromString(rs.getString("affected")),
                            reversedEventId = rs.getString("reversed_event_id"),
                            replayTag = rs.getString("replay_tag")
                        )
                    )
                }
            }
        }
        return out
    }

    @Synchronized
    fun putSource(id: String, kind: String, seq: Int, body: Any) {
        conn.prepareStatement(
            "INSERT OR REPLACE INTO sources(id,kind,seq,body) VALUES(?,?,?,?)"
        ).use { ps ->
            ps.setString(1, id)
            ps.setString(2, kind)
            ps.setInt(3, seq)
            ps.setString(4, json.encodeToString(body))
            ps.executeUpdate()
        }
    }

    @Synchronized
    fun deleteSource(id: String, kind: String) {
        conn.prepareStatement("DELETE FROM sources WHERE id=? AND kind=?").use { ps ->
            ps.setString(1, id); ps.setString(2, kind); ps.executeUpdate()
        }
    }

    @Synchronized
    fun loadSources(): List<Triple<String, String, String>> {
        val out = mutableListOf<Triple<String, String, String>>()
        conn.prepareStatement("SELECT id,kind,body FROM sources ORDER BY seq ASC, id ASC").use { ps ->
            ps.executeQuery().use { rs ->
                while (rs.next()) out.add(Triple(rs.getString(1), rs.getString(2), rs.getString(3)))
            }
        }
        return out
    }

    @Synchronized
    fun putDerived(derivedId: String, input: CalibrationInput, result: CalibrationResult, eventSeq: Int) {
        conn.prepareStatement(
            """
            INSERT OR REPLACE INTO derived(derived_id,input_json,result_json,created_event_seq,fingerprint)
            VALUES(?,?,?,?,?)
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, derivedId)
            ps.setString(2, json.encodeToString(input))
            ps.setString(3, json.encodeToString(result))
            ps.setInt(4, eventSeq)
            ps.setString(5, result.parameterFingerprint)
            ps.executeUpdate()
        }
    }

    @Synchronized
    fun loadDerived(): List<DerivedRow> {
        val out = mutableListOf<DerivedRow>()
        conn.prepareStatement("SELECT input_json,result_json,created_event_seq FROM derived ORDER BY created_event_seq ASC").use { ps ->
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    out.add(
                        DerivedRow(
                            json.decodeFromString(rs.getString(1)),
                            json.decodeFromString(rs.getString(2)),
                            rs.getInt(3)
                        )
                    )
                }
            }
        }
        return out
    }

    @Synchronized
    fun setMeta(k: String, v: String) {
        conn.prepareStatement("INSERT OR REPLACE INTO meta(k,v) VALUES(?,?)").use { ps ->
            ps.setString(1, k); ps.setString(2, v); ps.executeUpdate()
        }
    }

    @Synchronized
    fun getMeta(k: String): String? =
        conn.prepareStatement("SELECT v FROM meta WHERE k=?").use { ps ->
            ps.setString(1, k)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
        }

    @Synchronized
    fun deleteAll() {
        conn.createStatement().use { st ->
            st.executeUpdate("DELETE FROM events")
            st.executeUpdate("DELETE FROM sources")
            st.executeUpdate("DELETE FROM derived")
            st.executeUpdate("DELETE FROM meta")
        }
    }

    fun close() {
        if (::conn.isInitialized) conn.close()
    }

    data class DerivedRow(val input: CalibrationInput, val result: CalibrationResult, val eventSeq: Int)
}
