package org.chronobench.db

import java.sql.Connection
import java.sql.DriverManager

object Database {
    fun open(path: String): Connection {
        Class.forName("org.sqlite.JDBC")
        val url = if (path == ":memory:") "jdbc:sqlite::memory:" else "jdbc:sqlite:$path"
        val conn = DriverManager.getConnection(url)
        conn.createStatement().use { st ->
            st.executeUpdate("PRAGMA journal_mode=WAL")
            st.executeUpdate("PRAGMA foreign_keys=ON")
        }
        schema(conn)
        return conn
    }

    private fun schema(conn: Connection) {
        conn.createStatement().use { st ->
            st.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS event_log (
                    seq INTEGER NOT NULL,
                    project_id TEXT NOT NULL,
                    event_id TEXT NOT NULL,
                    event_type TEXT NOT NULL,
                    at_epoch_second INTEGER NOT NULL,
                    causation_id TEXT,
                    payload_json TEXT NOT NULL,
                    PRIMARY KEY (project_id, seq),
                    UNIQUE (project_id, event_id)
                )
                """.trimIndent()
            )
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_event_project ON event_log(project_id, seq)")
            st.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS key_value (
                    k TEXT PRIMARY KEY,
                    v TEXT NOT NULL
                )
                """.trimIndent()
            )
        }
    }
}
