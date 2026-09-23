=package rcw
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.MessageDigest

object Jsons {
    val json = Json { ignoreUnknownKeys = true; prettyPrint = false }
    val pretty = Json { ignoreUnknownKeys = true; prettyPrint = true }
}

fun sha256(s: String): String =
    MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

class Engine(val store: Store, private val clock: Clock, val seedHint: Long = 0L) {

    val json = Jsons.json

    private fun event(type: String, summary: String, data: JsonObject = buildJsonObject {}): EventRow {
        val payload = json.encodeToString(EventPayload.serializer(), EventPayload(summary, data))
        return store.appendEvent(type, clock.nowMillis(), payload)
    }

    private fun eventData(ev: EventRow): EventPayload = json.decodeFromString(EventPayload.serializer(), ev.payload)

    /* ---------- source versioning: raw inputs are immutable, revisions create new versions ---------- */

    fun importSource(input: SourceInput, allowRevise: Boolean = false): SourceRow {
        validateSource(input)
        val existing = store.currentSource(input.series)
        if (existing != null) throw ImportException(listOf("source series '${input.series}' already exists; use a revision"))
        return insertVersion(input, parent = null)
    }

    fun reviseSource(series: String, content: JsonObject, reason: String = "param revision"): SourceRow {
        val prev = store.currentSource(series) ?: throw ImportException(listOf("cannot revise unknown series '$series'"))
        val input = SourceInput(series, prev.kind, content)
        validateSource(input)
        val row = insertVersion(input, parent = prev.id)
        event(
            "SOURCE_REVISED",
            "$series revised to v${row.version} ($reason)",
            buildJsonObject {
                put("series", series); put("parent", prev.id); put("version", row.version)
                put("kind", prev.kind); put("reason", reason)
            }
        )
        invalidateDependents(series)
        return row
    }

    private fun insertVersion(input: SourceInput, parent: SourceRow?): SourceRow {
        val version = (parent?.version ?: 0) + 1
        val ord = store.nextCounter("order")
        val id = "src:${input.series}:v$version"
        val row = SourceRow(id, input.series, input.kind, version, parent?.id, ord,
            json.encodeToString(JsonObject.serializer(), input.content), 0)
        val ev = event(
            "SOURCE_IMPORTED",
            "${input.kind} source '${input.series}' v$version imported",
            buildJsonObject {
                put("sourceId", id); put("series", input.series); put("kind", input.kind); put("version", version)
            }
        )
        val created = row.copy(createdSeq = ev.seq)
        store.insertSource(created)
        return created
    }

    private fun invalidateDependents(series: String): List<Long> {
        val marked = mutableListOf<Long>()
        for (d in store.listAllDerived()) {
            val inputIds = json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(kotlinx.serialization.builtins.serializer<String>()), d.inputs)
            if (inputIds.any { it.startsWith("src:$series:") }) {
                val ev = event(
                    "STALENESS_MARKED",
                    "${d.id} marked stale due to revision of $series",
                    buildJsonObject { put("derivedId", d.id); put("series", series) }
                )
                store.markStale(d.id, ev.seq)
                marked += ev.seq
            }
        }
        return marked
    }

    /* ---------------- validation ---------------- */

    private fun validateSource(input: SourceInput) {
        val errs = mutableListOf<String>()
        when (input.kind) {
            "MEASUREMENT" -> {
                val m = runCatching { json.decodeFromJsonElement(MeasurementSource.serializer(), input.content) }
                    .getOrElse { throw ImportException(listOf("MEASUREMENT '${input.series}': ${it.message}")) }
                if (!m.rawAge.contains(Regex("[0-9]"))) errs += "${input.series}: rawAge has no numeric representation"
                if (m.provenance.ref.isBlank()) errs += "${input.series}: provenance.ref required"
            }
            "CURVE" -> {
                val c = runCatching { json.decodeFromJsonElement(CurveSource.serializer(), input.content) }
                    .getOrElse { throw ImportException(listOf("CURVE '${input.series}': ${it.message}")) }
                if (c.points.size < 3) errs += "${input.series}: curve needs >= 3 points"
                var prev = Double.NEGATIVE_INFINITY
                for (pt in c.points) {
                    if (pt.size != 3) errs += "${input.series}: each point must be [calBP,c14age,sigma]";
                    if (pt.size == 3) {
                        if (pt[0] <= prev) errs += "${input.series}: calBP must be strictly ascending";
                        prev = pt[0]
                    }
                }
            }
            "OFFSET" -> {
                val o = runCatching { json.decodeFromJsonElement(OffsetSource.serializer(), input.content) }
                    .getOrElse { throw ImportException(listOf("OFFSET '${input.series}': ${it.message}")) }
                if (o.deltaRSigma < 0) errs += "${input.series}: sigma must be non-negative"
            }
            "ERA" -> {
                json.decodeFromJsonElementOrNull(EraSource.serializer(), input.content)
                    ?: throw ImportException(listOf("ERA '${input.series}': malformed content"))
            }
            "STRATIGRAPHY" -> {
                val s = runCatching { json.decodeFromJsonElement(StratigraphySource.serializer(), input.content) }
                    .getOrElse { throw ImportException(listOf("STRATIGRAPHY '${input.series}': ${it.message}")) }
                s.constraints.forEach {
                    if (it.before == it.after) errs += "${input.series}: constraint ${it.before}->${it.after} is self-referential"
                }
            }
            "EXCLUSION" -> {
                json.decodeFromJsonElementOrNull(ExclusionSource.serializer(), input.content)
                    ?: throw ImportException(listOf("EXCLUSION '${input.series}': malformed content"))
            }
            else -> errs += "unknown source kind '${input.kind}'"
        }
        if (errs.isNotEmpty()) throw ImportException(errs)
    }

    /* ---------------- calibration ---------------- */

    fun currentMeasurementSeries(): List<String> =
        store.listSources().filter { it.kind == "MEASUREMENT" }.map { it.series }.distinct().sorted()

    fun computeCalibration(sampleSeries: String, reason: String = "compute"): DerivedRow {
        val measSrc = store.currentSource(sampleSeries)
            ?: throw IllegalStateException("no measurement source for $sampleSeries")
        val curveSrc = store.currentSource("curve") ?: error("no curve source")
        val eraSrc = store.currentSource("era") ?: error("no era source")
        val offsets = store.listSources().filter { it.kind == "OFFSET" }
            .map { json.decodeFromString(OffsetSource.serializer(), it.content) to it }
        val offPair = offsets.lastOrNull { it.first.appliesTo.contains(sampleSeries) }
        val meas = json.decodeFromString(MeasurementSource.serializer(), measSrc.content)
        val curveC = json.decodeFromString(CurveSource.serializer(), curveSrc.content)
        val inputIds = buildList {
            add(measSrc.id); add(curveSrc.id); add(eraSrc.id); offPair?.second?.id?.let { add(it) }
        }
        val diag = mutableListOf<String>()
        val parsed = Calibration.parseRawAge(meas.rawAge, meas.unit)
        if (parsed == null) {
            diag += "cannot normalize rawAge '${meas.rawAge}' with unit '${meas.unit}'; raw value kept at source"
        }
        var result: CalibrationResult
        if (parsed == null) {
            result = CalibrationResult(
                sample = sampleSeries, status = "UNKNOWN", diagnostics = diag,
                measurementId = measSrc.id, curveId = curveSrc.id, offsetId = offPair?.second?.id,
                eraId = eraSrc.id, curveName = curveC.name, curveVersion = curveC.version,
                rawAge = meas.rawAge, unit = meas.unit,
                gridFrom = curveC.points.first()[0], gridTo = curveC.points.last()[0],
                gridStep = Calibration.GRID_STEP, likelihoodMass = 0.0,
                normalizationMethod = NORM_METHOD, density = emptyList(), peaks = emptyList(),
                intervals68 = emptyList(), intervals95 = emptyList(),
                truncatedLeft = false, truncatedRight = false
            )
        } else {
            val off = offPair?.first
            val curve = Calibration.Curve.fromPoints(curveC.points)
            val p = Calibration.posterior(
                curve, parsed.age, parsed.sigma,
                off?.deltaRMean ?: 0.0, off?.deltaRSigma ?: 0.0
            )
            if (p.truncatedLeft) diag += "left likelihood tail truncated at curve boundary ${curve.minX} ${curveC.calUnit}; renormalized"
            if (p.truncatedRight) diag += "right likelihood tail truncated at curve boundary ${curve.maxX} ${curveC.calUnit}; renormalized"
            val fpBase = fingerprintBase(ALGO_VERSION, inputIds, mapOf(
                "rawAge" to meas.rawAge, "unit" to meas.unit
            ))
            val derId = "der-cal-%04d".format(store.nextCounter("derived"))
            val peaks = Calibration.peaks(p).map { i ->
                Calibration.PeakResult(round6(p.grid[i]), round6(p.density[i]), Calibration.eraLabel(p.grid[i]))
            }
            val i68 = Calibration.buildIntervals(p, Calibration.hpdIntervals(p, Calibration.HPD_68, derId, "i68"), derId, "i68")
            val i95 = Calibration.buildIntervals(p, Calibration.hpdIntervals(p, Calibration.HPD_95, derId, "i95"), derId, "i95")
            result = CalibrationResult(
                sample = sampleSeries, status = "OK", diagnostics = diag,
                measurementId = measSrc.id, curveId = curveSrc.id, offsetId = offPair?.second?.id,
                eraId = eraSrc.id, curveName = curveC.name, curveVersion = curveC.version,
                rawAge = meas.rawAge, unit = meas.unit,
                ageBP = round3(parsed.age), sigmaBP = round3(parsed.sigma),
                offsetMean = off?.deltaRMean?.let { round3(it) }, offsetSigma = off?.deltaRSigma?.let { round3(it) },
                effectiveAgeBP = round3(parsed.age - (off?.deltaRMean ?: 0.0)),
                effectiveSigmaBP = round3(kotlin.math.sqrt(parsed.sigma * parsed.sigma + (off?.deltaRSigma ?: 0.0).let { it * it })),
                gridFrom = p.grid.first(), gridTo = p.grid.last(), gridStep = Calibration.GRID_STEP,
                likelihoodMass = round6(p.mass), normalizationMethod = NORM_METHOD,
                density = p.density.map { round6(it) }, peaks = peaks,
                intervals68 = i68, intervals95 = i95,
                truncatedLeft = p.truncatedLeft, truncatedRight = p.truncatedRight
            )
        }
        return storeDerived(sampleSeries, "CALIBRATION", inputIds, result, reason)
    }

    private inline fun <reified T> Json.decodeFromJsonElementOrNull(
        serializer: kotlinx.serialization.KSerializer<T>, el: kotlinx.serialization.json.JsonElement
    ): T? = runCatching { decodeFromJsonElement(serializer, el) }.getOrNull()

    private fun fingerprintBase(algo: String, inputs: List<String>, params: Map<String, String>): String {
        val canonical = buildString {
            append(algo).append('|')
            append(inputs.sorted().joinToString(",")).append('|')
            params.toSortedMap().entries.joinTo(this, ",") { "${it.key}=${it.value}" }
        }
        return sha256(canonical)
    }

    private fun storeDerived(sampleSeries: String, kind: String, inputIds: List<String>,
                             result: Any, reason: String, sampleKey: String? = sampleSeries): DerivedRow {
        val content = when (result) {
            is CalibrationResult -> json.encodeToString(CalibrationResult.serializer(), result)
            is StratigraphyResult -> json.encodeToString(StratigraphyResult.serializer(), result)
            is OffsetComparisonResult -> json.encodeToString(OffsetComparisonResult.serializer(), result)
            else -> error("unknown result type")
        }
        val fp = sha256("$ALGO_VERSION|${inputIds.sorted().joinToString(",")}|${result::class.simpleName}|$content".take(4096) + content.length)
        val id = if (result is CalibrationResult) {
            // ids assigned inside computeCalibration for interval ids; keep them stable by taking counter there
            "der-cal-" + store.getDerivedLastCalNum()
        } else "der-${kind.lowercase().replace("_","-")}-%04d".format(store.nextCounter("derived"))
        error("unused")
    }

    @org.intellij.lang.annotations.NotImplemented
    private fun unused(): Nothing = TODO()

    private fun round3(v: Double) = Math.round(v * 1000.0) / 1000.0
    private fun round6(v: Double) = Math.round(v * 1e6) / 1e6
}
