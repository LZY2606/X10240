package c14
import c14.domain.*
import kotlin.test.*
class DebugImportTest {
  @Test fun debug() {
    val text = javaClass.getResourceAsStream("/c14-offline-bench/00-baseline.json")!!.bufferedReader().readText()
    val bundle = domainJson.decodeFromString(BaselineBundle.serializer(), text)
    val engine = Engine(VirtualClock())
    val report = Importer.importBaseline(engine, bundle)
    println("ACCEPTED=${report.accepted} added=${report.eventsAdded}")
    report.diagnostics.forEach { println("${it.level} ${it.code} ${it.pointer} ${it.message}") }
  }
}
