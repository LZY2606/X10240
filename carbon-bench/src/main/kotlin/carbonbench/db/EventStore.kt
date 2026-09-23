package carbonbench.db

import carbonbench.domain.Event

interface EventStore {
    fun projectExists(projectId: String): Boolean
    fun nextSeq(projectId: String): Long
    fun appendEvent(event: Event)
    fun listEvents(projectId: String): List<Event>
    fun listProjectIds(): List<String>
    fun deleteProject(projectId: String)
    fun close()
}
