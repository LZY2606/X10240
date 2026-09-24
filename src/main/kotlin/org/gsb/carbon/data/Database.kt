package org.gsb.carbon.data

import java.sql.Connection
import java.sql.DriverManager
import java.nio.file.Files
import java.nio.file.Path

object Database {
    init {
        Class.forName("org.sqlite.JDBC")
    }

    fun open(file: Path): Connection {
        Files.createDirectories(file.toAbsolutePath().parent)
        val conn = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath())
        conn.createStatement().use { st ->
            st.execute("PRAGMA foreign_keys = ON")
            st.execute("PRAGMA journal_mode = WAL")
        }
        Schema.create(conn)
        return conn
    }

    fun openInMemory(): Connection {
        val conn = DriverManager.getConnection("jdbc:sqlite::memory:")
        conn.createStatement().use { it.execute("PRAGMA foreign_keys = ON") }
        Schema.create(conn)
        return conn
    }
}

object Schema {
    const val VERSION = 1

    val DDL: List<String> = listOf(
        """
        CREATE TABLE IF NOT EXISTS meta (
            key TEXT PRIMARY KEY,
            value TEXT NOT NULL
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS projects (
            project_id TEXT PRIMARY KEY,
            name TEXT NOT NULL,
            case_pack_id TEXT,
            created_at_event_seq INTEGER NOT NULL
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS events (
            seq INTEGER PRIMARY KEY AUTOINCREMENT,
            event_id TEXT NOT NULL UNIQUE,
            type TEXT NOT NULL,
            clock INTEGER NOT NULL,
            actor TEXT NOT NULL,
            summary TEXT NOT NULL,
            payload_json TEXT NOT NULL,
            sources_json TEXT NOT NULL
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS curves (
            version_id TEXT PRIMARY KEY,
            curve_id TEXT NOT NULL,
            version TEXT NOT NULL,
            parent_version TEXT,
            payload_json TEXT NOT NULL,
            recorded_event_seq INTEGER NOT NULL
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS measurements (
            id_version TEXT PRIMARY KEY,
            measurement_id TEXT NOT NULL,
            version INTEGER NOT NULL,
            parent_version INTEGER,
            payload_json TEXT NOT NULL,
            recorded_event_seq INTEGER NOT NULL
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS offsets (
            offset_id TEXT PRIMARY KEY,
            payload_json TEXT NOT NULL,
            recorded_event_seq INTEGER NOT NULL
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS era_systems (
            era_system_id TEXT PRIMARY KEY,
            payload_json TEXT NOT NULL,
            recorded_event_seq INTEGER NOT NULL
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS sequence_edges (
            edge_id TEXT PRIMARY KEY,
            payload_json TEXT NOT NULL,
            recorded_event_seq INTEGER NOT NULL
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS exclusions (
            exclusion_id TEXT PRIMARY KEY,
            payload_json TEXT NOT NULL,
            recorded_event_seq INTEGER NOT NULL
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS calibration_runs (
            run_id TEXT PRIMARY KEY,
            payload_json TEXT NOT NULL,
            created_event_seq INTEGER NOT NULL,
            current INTEGER NOT NULL DEFAULT 1,
            stale_reason TEXT
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS sequence_evaluations (
            evaluation_id TEXT PRIMARY KEY,
            payload_json TEXT NOT NULL,
            created_event_seq INTEGER NOT NULL,
            current INTEGER NOT NULL DEFAULT 1,
            stale_reason TEXT
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS adjustment_candidates (
            candidate_id TEXT PRIMARY KEY,
            evaluation_id TEXT NOT NULL,
            payload_json TEXT NOT NULL
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS decisions (
            decision_id TEXT PRIMARY KEY,
            payload_json TEXT NOT NULL,
            created_event_seq INTEGER NOT NULL
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS case_archive (
            archive_id TEXT NOT NULL,
            stage TEXT NOT NULL,
            payload_json TEXT NOT NULL,
            event_seq INTEGER NOT NULL,
            PRIMARY KEY (archive_id, stage)
        )
        """,
    )

    fun create(conn: Connection) {
        conn.createStatement().use { st ->
            DDL.forEach { st.execute(it) }
            st.execute("INSERT OR IGNORE INTO meta(key, value) VALUES ('schema_version', '$VERSION')")
        }
    }
}
