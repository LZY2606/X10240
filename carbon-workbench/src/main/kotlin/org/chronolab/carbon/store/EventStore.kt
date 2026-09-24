package org.chronolab.carbon.store

import org.chronolab.carbon.model.Branch
import org.chronolab.carbon.model.ProjectEvent
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager

/**
 * SQLite 事件日志。events 仅追加；branches 保存当前 head。
 * 派生数据不单独存表——重放事件即可逐步重建投影（满足“重新打开项目后仍能逐步重放”）。
 */
class EventStore(dbPath: Path) {
    val conn: Connection

    init {
        if (dbPath.parent != null) Files.createDirectories(dbPath.parent)
        conn = DriverManager.getConnection("jdbc:sqlite:${dbPath.toAbsolutePath()}")
        conn.autoCommit = false
        conn.createStatement().use { st ->
            st.executeUpdate("PRAGMA journal_mode=WAL")
            st.executeUpdate("PRAGMA foreign_keys=ON")
            st.executeUpdate(
                """CREATE TABLE IF NOT EXISTS events (
                     seq INTEGER PRIMARY KEY AUTOINCREMENT,
                     event_id TEXT NOT NULL UNIQUE,
                     branch_id TEXT NOT NULL,
                     virtual_time TEXT NOT NULL,
                     type TEXT NOT NULL,
                     payload_json TEXT NOT NULL,
                     basis_json TEXT
                   )""".trimIndent()
            )
            st.executeUpdate(
                """CREATE TABLE IF NOT EXISTS branches (
                     branch_id TEXT PRIMARY KEY,
                     label TEXT NOT NULL,
                     parent_branch_id TEXT,
                     forked_from_event_id TEXT,
                     head_seq INTEGER NOT NULL,
                     is_main INTEGER NOT NULL
                   )""".trimIndent()
            )
            st.executeUpdate(
                "CREATE INDEX IF NOT EXISTS idx_events_branch ON events(branch_id, seq)"
            )
        }
        conn.commit()
    }

    fun initMainBranch() {
        val exists = conn.prepareStatement("SELECT 1 FROM branches WHERE is_main=1").use { it.executeQuery().next() }
        if (!exists) {
            conn.prepareStatement(
                "INSERT INTO branches(branch_id,label,parent_branch_id,forked_from_event_id,head_seq,is_main) VALUES(?,?,?,?,?,1)"
            ).use { ps ->
                ps.setString(1, "main"); ps.setString(2, "主分支")
                ps.setNull(3, java.sql.Types.VARCHAR); ps.setNull(4, java.sql.Types.VARCHAR)
                ps.setLong(5, 0L); ps.executeUpdate()
            }
            conn.commit()
        }
    }

    fun append(branchId: String, type: String, payloadJson: String, virtualTime: String, basisJson: String? = null): ProjectEvent {
        val eventId = "ev-%05d".format(nextSeq())
        val ps = conn.prepareStatement(
            "INSERT INTO events(event_id,branch_id,virtual_time,type,payload_json,basis_json) VALUES(?,?,?,?,?,?)"
        )
        ps.setString(1, eventId); ps.setString(2, branchId); ps.setString(3, virtualTime)
        ps.setString(4, type); ps.setString(5, payloadJson)
        if (basisJson == null) ps.setNull(6, java.sql.Types.VARCHAR) else ps.setString(6, basisJson)
        ps.executeUpdate()
        val seq = conn.createStatement().executeQuery("SELECT last_insert_rowid()").use { it.getLong(1) }
        conn.prepareStatement("UPDATE branches SET head_seq=? WHERE branch_id=?").use { u ->
            u.setLong(1, seq); u.setString(2, branchId); u.executeUpdate()
        }
        conn.commit()
        return ProjectEvent(eventId, seq, branchId, virtualTime, type, payloadJson, basisJson)
    }

    private fun nextSeq(): Long =
        conn.createStatement().executeQuery("SELECT COALESCE(MAX(seq),0)+1 FROM events").use { it.getLong(1) }

    fun listBranches(): List<Branch> =
        conn.createStatement().executeQuery(
            "SELECT branch_id,label,parent_branch_id,forked_from_event_id,head_seq,is_main FROM branches ORDER BY is_main DESC, branch_id"
        ).let { rs ->
            buildList {
                while (rs.next()) add(
                    Branch(
                        rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getLong(5), rs.getInt(6) == 1
                    )
                )
            }
        }

    fun upsertBranch(branch: Branch, withEventSeq: Long? = null) {
        conn.prepareStatement(
            """INSERT INTO branches(branch_id,label,parent_branch_id,forked_from_event_id,head_seq,is_main)
               VALUES(?,?,?,?,?,?)
               ON CONFLICT(branch_id) DO UPDATE SET head_seq=excluded.head_seq""".trimIndent()
        ).use { ps ->
            ps.setString(1, branch.branchId); ps.setString(2, branch.label)
            ps.setString(3, branch.parentBranchId)
            ps.setString(4, branch.forkedFromEventId)
            ps.setLong(5, withEventSeq ?: branch.headSeq)
            ps.setInt(6, if (branch.isMain) 1 else 0)
            ps.executeUpdate()
        }
        conn.commit()
    }

    fun branch(branchId: String): Branch? =
        listBranches().firstOrNull { it.branchId == branchId }

    /** 读取主分支从头至指定 seq（或 head）的事件；分支重放=自身事件+祖先链直至分叉点。 */
    fun eventsOnBranch(branchId: String, upToSeqInclusive: Long? = null): List<ProjectEvent> {
        val branch = branch(branchId) ?: return emptyList()
        val own = readEvents(branchId, Long.MAX_VALUE)
        if (branch.parentBranchId == null || branch.forkedFromEventId == null) {
            return own.filter { upToSeqInclusive == null || it.seq <= upToSeqInclusive }
        }
        val parent = readEvents(branch.parentBranchId, Long.MAX_VALUE)
        val forkSeq = parent.first { it.eventId == branch.forkedFromEventId }.seq
        val inherited = parent.filter { it.seq <= forkSeq }
        return (inherited + own).filter { upToSeqInclusive == null || it.seq <= upToSeqInclusive }
    }

    private fun readEvents(branchId: String, upTo: Long): List<ProjectEvent> =
        conn.prepareStatement("SELECT seq,event_id,branch_id,virtual_time,type,payload_json,basis_json FROM events WHERE branch_id=? AND seq<=? ORDER BY seq")
            .use { ps ->
                ps.setString(1, branchId); ps.setLong(2, upTo)
                val rs = ps.executeQuery()
                buildList {
                    while (rs.next()) add(
                        ProjectEvent(rs.getString(2), rs.getLong(1), rs.getString(3),
                            rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7))
                    )
                }
            }

    fun allEvents(): List<ProjectEvent> =
        conn.createStatement().executeQuery(
            "SELECT seq,event_id,branch_id,virtual_time,type,payload_json,basis_json FROM events ORDER BY seq"
        ).let { rs ->
            buildList {
                while (rs.next()) add(
                    ProjectEvent(rs.getString(2), rs.getLong(1), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7))
                )
            }
        }

    fun close() = conn.close()
}
