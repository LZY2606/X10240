package c14.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encodeToString

@Serializable
data class ImportIssue(val level: String, val code: String, val message: String, val pointer: String = "")

@Serializable
data class ImportReport(
    val projectId: String,
    val eventsAdded: Int,
    val diagnostics: List<ImportIssue>,
    val accepted: Boolean
)

/**
 * Source-oriented input format. One document registers one project baseline:
 * measurements, curve versions, offset distributions, the era system,
 * sequence constraints and exclusion decisions each point at a source.
 */
@Serializable
data class BaselineBundle(
    val projectId: String,
    val title: String,
    val eraSystem: String = "BP/BCE-CE astronomical",
    val notes: String = "",
    val actor: String = "importer",
    val sources: List<JsonObject> = emptyList(),
    val curves: List<JsonObject> = emptyList(),
    val measurements: List<JsonObject> = emptyList(),
    val offsets: List<JsonObject> = emptyList(),
    val assumptions: List<JsonObject> = emptyList(),
    val links: List<JsonObject> = emptyList(),
    val exclusions: List<JsonObject> = emptyList()
)

@Serializable
data class EventBundle(
    val projectId: String,
    val title: String,
    val format: String = "c14-bench.events/v1",
    val events: List<Event>
)

object Importer {
    /**
     * Import a baseline document. Format/conversion errors are collected and
     * the import is rejected atomically; unconvertible-but-retained values
     * arrive as diagnostics without aborting the document.
     */
    fun importBaseline(engine: Engine, bundle: BaselineBundle, diagnose: (JsonObject, String) -> List<ImportIssue> = { _, _ -> emptyList() }): ImportReport {
        val issues = mutableListOf<ImportIssue>()
        checkDuplicateIds(engine, bundle, issues)
        requireShape(bundle, issues)
        if (issues.any { it.level == "ERROR" }) {
            return ImportReport(bundle.projectId, 0, issues, false)
        }
        if (engine.hasProject(bundle.projectId)) {
            issues += ImportIssue("ERROR", "PROJECT_EXISTS", "project already exists", "/projectId")
            return ImportReport(bundle.projectId, 0, issues, false)
        }
        engine.startProject(bundle.projectId, bundle.title, bundle.eraSystem, bundle.notes, bundle.actor)
        var count = 1
        try {
            for ((i, obj) in bundle.sources.withIndex()) {
                issues += diagnose(obj, "/sources/$i")
                val src = domainJson.decodeFromJsonElement(Source.serializer(), obj)
                engine.importCommand(bundle.projectId, Command("REGISTER_SOURCE", domainJson.encodeToString(SourceRegistered(src)), src.id, bundle.actor))
                count++
            }
            for ((i, obj) in bundle.curves.withIndex()) {
                val curve = domainJson.decodeFromJsonElement(CalibrationCurve.serializer(), obj)
                engine.importCommand(bundle.projectId, Command("REGISTER_CURVE", domainJson.encodeToString(CurveRegistered(curve)), curve.sourceId, bundle.actor))
                count++
            }
            for ((i, obj) in bundle.measurements.withIndex()) {
                val m = domainJson.decodeFromJsonElement(Measurement.serializer(), obj)
                engine.importCommand(bundle.projectId, Command("SUBMIT_MEASUREMENT", domainJson.encodeToString(MeasurementSubmitted(m)), m.sourceId, bundle.actor))
                count++
            }
            for (obj in bundle.offsets) {
                val off = domainJson.decodeFromJsonElement(OffsetModel.serializer(), obj)
                engine.importCommand(bundle.projectId, Command("REGISTER_OFFSET", domainJson.encodeToString(OffsetModelRegistered(off)), off.sourceId, bundle.actor))
                count++
            }
            for (obj in bundle.assumptions) {
                val a = domainJson.decodeFromJsonElement(AssumptionSet.serializer(), obj)
                engine.importCommand(bundle.projectId, Command("SET_ASSUMPTION", domainJson.encodeToString(a), a.sourceId, bundle.actor))
                count++
            }
            engine.calibrateAllMissing(bundle.projectId, bundle.actor)
            for (obj in bundle.links) {
                val l = domainJson.decodeFromJsonElement(LinkDeclared.serializer(), obj)
                engine.importCommand(bundle.projectId, Command("DECLARE_LINK", domainJson.encodeToString(l), l.link.sourceId, bundle.actor))
                count++
            }
            for (obj in bundle.exclusions) {
                val x = domainJson.decodeFromJsonElement(ExclusionDecided.serializer(), obj)
                engine.importCommand(bundle.projectId, Command("DECIDE_EXCLUSION", domainJson.encodeToString(x), x.exclusion.sourceId, bundle.actor))
                count++
            }
            count = engine.eventLog(bundle.projectId).size
        } catch (ex: Exception) {
            issues += ImportIssue("ERROR", "IMPORT_ABORTED", ex.message ?: ex.javaClass.simpleName)
            return ImportReport(bundle.projectId, 0, issues, false)
        }
        return ImportReport(bundle.projectId, count, issues, true)
    }

    /** Append a raw, hash-chained event stream (export -> delete -> re-import). */
    fun importEvents(engine: Engine, bundle: EventBundle): ImportReport {
        if (bundle.format != "c14-bench.events/v1") {
            return ImportReport(bundle.projectId, 0, listOf(ImportIssue("ERROR", "BAD_FORMAT", "unsupported format ${bundle.format}")), false)
        }
        val existed = engine.hasProject(bundle.projectId)
        var added = 0
        try {
            for (e in bundle.events) {
                engine.importEvent(bundle.projectId, e, if (!existed) bundle.title else null)
                added++
            }
        } catch (ex: Exception) {
            return ImportReport(bundle.projectId, added, listOf(ImportIssue("ERROR", "CHAIN_BROKEN", ex.message ?: ex.javaClass.simpleName)), false)
        }
        return ImportReport(bundle.projectId, added, emptyList(), true)
    }

    fun exportEvents(engine: Engine, projectId: String, title: String): EventBundle =
        EventBundle(projectId, title, events = engine.eventLog(projectId))

    private fun checkDuplicateIds(engine: Engine, bundle: BaselineBundle, issues: MutableList<ImportIssue>) {
        val seen = HashSet<String>()
        fun check(id: String, pointer: String) {
            if (id.isBlank()) issues += ImportIssue("ERROR", "BLANK_ID", "id must not be blank", pointer)
            if (!seen.add(id)) issues += ImportIssue("ERROR", "DUPLICATE_ID", "duplicate id $id", pointer)
        }
        bundle.sources.forEachIndexed { i, o -> check(o.string("id"), "/sources/$i/id") }
        bundle.curves.forEachIndexed { i, o -> check(o.string("id"), "/curves/$i/id") }
        bundle.measurements.forEachIndexed { i, o -> check(o.string("id"), "/measurements/$i/id") }
        bundle.offsets.forEachIndexed { i, o -> check(o.string("id"), "/offsets/$i/id") }
        bundle.links.forEachIndexed { i, w -> check((w["link"] as? JsonObject ?: w).string("id"), "/links/$i/id") }
    }

    private fun requireShape(bundle: BaselineBundle, issues: MutableList<ImportIssue>) {
        if (bundle.projectId.isBlank()) issues += ImportIssue("ERROR", "BLANK_ID", "projectId must not be blank", "/projectId")
        bundle.measurements.forEachIndexed { i, o ->
            val age = o["ageBp"]; val sigma = o["sigmaBp"]
            if (age == null || age.toString().isBlank()) issues += ImportIssue("ERROR", "MISSING_AGE", "ageBp required", "/measurements/$i/ageBp")
            if (sigma == null) issues += ImportIssue("ERROR", "MISSING_SIGMA", "sigmaBp required", "/measurements/$i/sigmaBp")
            val id = o.string("id"); val sourceId = o.string("sourceId"); val sampleId = o.string("sampleId")
            if (sourceId.isBlank()) issues += ImportIssue("ERROR", "NO_SOURCE", "measurement must name a source", "/measurements/$i/sourceId")
            if (sampleId.isBlank()) issues += ImportIssue("ERROR", "NO_SAMPLE", "sampleId required", "/measurements/$i/sampleId")
            if (!bundle.sources.any { it.string("id") == sourceId }) issues += ImportIssue("ERROR", "UNKNOWN_SOURCE", "source not in document: $sourceId", "/measurements/$i/sourceId")
        }
        bundle.curves.forEachIndexed { i, o ->
            val sourceId = o.string("sourceId")
            if (!bundle.sources.any { it.string("id") == sourceId }) issues += ImportIssue("ERROR", "UNKNOWN_SOURCE", "curve source not in document: $sourceId", "/curves/$i/sourceId")
        }
        bundle.links.forEachIndexed { i, wrapped ->
            val o = (wrapped["link"] as? JsonObject) ?: wrapped
            val older = o.string("olderSample"); val younger = o.string("youngerSample")
            if (older.isBlank() || younger.isBlank()) {
                issues += ImportIssue("ERROR", "MISSING_FIELD", "link requires olderSample and youngerSample", "/links/$i")
                return@forEachIndexed
            }
            if (older == younger) issues += ImportIssue("ERROR", "SELF_LINK", "a sample cannot precede itself", "/links/$i")
            val samples = bundle.measurements.map { it.string("sampleId") }.toSet()
            if (older !in samples) issues += ImportIssue("ERROR", "UNKNOWN_SAMPLE", "older sample not measured: $older", "/links/$i")
            if (younger !in samples) issues += ImportIssue("ERROR", "UNKNOWN_SAMPLE", "younger sample not measured: $younger", "/links/$i")
        }
    }

    private fun JsonObject.string(key: String): String = this[key]?.let {
        domainJson.decodeFromJsonElement(String.serializer(), it)
    } ?: ""
}

/** Import path also performs deterministic reactions — exposed for the importer. */
fun Engine.importCommand(projectId: String, command: Command): List<Event> = command(projectId, command)
