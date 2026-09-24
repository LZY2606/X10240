package workbench

import java.sql.Connection
import java.sql.DriverManager

class Db(path: String) : AutoCloseable {
    val conn: Connection = DriverManager.getConnection("jdbc:sqlite:$path")

    init {
        conn.createStatement().use { st ->
            st.executeUpdate("PRAGMA journal_mode=WAL")
            st.executeUpdate("PRAGMA foreign_keys=ON")
            st.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS objects(
                  kind TEXT NOT NULL,
                  id TEXT NOT NULL,
                  version INTEGER NOT NULL,
                  json TEXT NOT NULL,
                  created_seq INTEGER NOT NULL,
                  PRIMARY KEY(kind,id,version)
                )
                """.trimIndent()
            )
            st.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS events(
                  seq INTEGER PRIMARY KEY AUTOINCREMENT,
                  id TEXT NOT NULL,
                  ts TEXT NOT NULL,
                  actor TEXT NOT NULL,
                  type TEXT NOT NULL,
                  payload TEXT NOT NULL
                )
                """.trimIndent()
            )
            st.executeUpdate("CREATE TABLE IF NOT EXISTS meta(k TEXT PRIMARY KEY, v TEXT NOT NULL)")
        }
    }

    override fun close() {
        conn.close()
    }
}
