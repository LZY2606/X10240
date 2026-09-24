package chrono.storage

import chrono.state.Event

/**
 * Append-only event log. The database is a durability detail: every fact is an event,
 * nothing is stored only as a mutable row.
 */
interface EventStore {
    fun append(event: Event): Event
    fun appendAll(events: List<Event>): List<Event>
    fun loadProject(projectId: String): List<Event>
    fun loadProjectAt(projectId: String, uptoSeq: Long): List<Event>
    fun listProjects(): List<String>
    fun nextSeq(projectId: String): Long
    fun countProject(projectId: String): Long
    fun deleteProject(projectId: String)
    fun allEvents(): List<Event>
}

/** One JSON document per project — used by export/import, never by live reads. */
@kotlinx.serialization.Serializable
data class ProjectArchive(
    val format: String = "chronolab-archive-1",
    val exportedFromAppVersion: String = "1.0.0",
    val projectId: String,
    val events: List<Event>
)
