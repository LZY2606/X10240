package c14.web

import c14.domain.Clock
import c14.domain.Engine
import c14.store.EventStore
import java.nio.file.Path

/** Engine wired to durable SQLite storage; hydrates by replaying every event. */
class AppEngine(clock: Clock, dbPath: Path) {
    val store = EventStore(dbPath)
    val engine: Engine = Engine(clock).also { eng ->
        val projects = store.loadProjects()
        for ((id, title) in projects) {
            eng.hydrate(id, title, store.loadEvents(id))
        }
        eng.onEvent = { projectId, event ->
            val title = store.loadProjects()[projectId] ?: projectId
            store.upsertProject(projectId, title)
            store.insertEvent(event, projectId)
        }
    }
}
