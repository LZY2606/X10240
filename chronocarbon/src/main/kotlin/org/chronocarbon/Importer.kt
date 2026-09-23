package org.chronocarbon

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Result of parsing one input line: entity plus diagnostics. Nothing is silently dropped. */
@Serializable
data class ParsedMeasurement(
    val measurement: Measurement,
    val offset: OffsetDist? = null,
    val constraintTo: String? = null,
    val exclude: Boolean? = null,
    val decisionReason: String? = null
)

object InputParser {
    private val lenient = Json { ignoreUnknownKeys = false }

    /** Parse the shipped small-sample JSONL format. Returns rows with per-row diagnostics. */
    fun parseMeasurementLines(text: String, defaultSource: String): List<ParsedMeasurement> {
        val rows = ArrayList<ParsedMeasurement>()
        text.trim().lines().filter { it.isNotBlank() }.forEachIndexed { index, line ->
            rows += parseMeasurementLine(line, index + 1, defaultSource)
        }
        return rows
    }

    fun parseMeasurementLine(line: String, lineNo: Int, defaultSource: String): ParsedMeasurement {
        val diagnostics = ArrayList<InputDiagnostic>()
        val obj = try {
            lenient.parseToJsonElement(line).jsonObject
        } catch (e: Exception) {
            throw ValidationException("line $lineNo: malformed JSON: ${e.message}")
        }

        val id = obj["id"]?.jsonPrimitive?.content
            ?: throw ValidationException("line $lineNo: missing required field 'id'")
        val code = obj["code"]?.jsonPrimitive?.content ?: id
        val rawAge = obj["radiocarbon_age_bp"]?.jsonPrimitive?.content
            ?: throw ValidationException("line $lineNo ($id): missing 'radiocarbon_age_bp'")
        val rawSigma = obj["sigma_bp"]?.jsonPrimitive?.content
            ?: throw ValidationException("line $lineNo ($id): missing 'sigma_bp'")

        val age = parsePositiveDouble(rawAge) { code1 ->
            diagnostics += InputDiagnostic("error.age_unconvertible", "line $lineNo: age '$rawAge' $code1", "radiocarbon_age_bp")
        }
        val sigma = parsePositiveDouble(rawSigma) { code1 ->
            diagnostics += InputDiagnostic("error.sigma_unconvertible", "line $lineNo ($id): sigma '$rawSigma' $code1", "sigma_bp")
        }
        if (age != null && age < 0) diagnostics += InputDiagnostic("error.age_negative", "line $lineNo: negative radiocarbon age", "radiocarbon_age_bp")
        if (sigma != null && sigma <= 0) diagnostics += InputDiagnostic("error.sigma_nonpositive", "line $lineNo: sigma must be > 0", "sigma_bp")

        val era = when (obj["era_system"]?.jsonPrimitive?.content ?: "bce_ce") {
            "cal_bp" -> EraSystem.CAL_BP
            "bce_ce" -> EraSystem.BCE_CE
            else -> {
                diagnostics += InputDiagnostic("error.unknown_era", "line $lineNo: unknown era_system", "era_system")
                EraSystem.BCE_CE
            }
        }
        val unit = obj["unit"]?.jsonPrimitive?.content ?: "14C yr BP"
        val source = obj["source"]?.jsonPrimitive?.content ?: defaultSource
        val document = obj["document"]?.jsonPrimitive?.content
        val measurement = Measurement(
            id = id,
            code = code,
            rawAgeText = rawAge,
            rawSigmaText = rawSigma,
            ageBp = age,
            sigma = sigma,
            unit = unit,
            eraSystem = era,
            provenance = Provenance(source = source, document = document, detail = "JSONL line $lineNo"),
            diagnostics = diagnostics
        )
        val offset = parseOffset(obj, lineNo, id)
        return ParsedMeasurement(
            measurement = measurement,
            offset = offset,
            constraintTo = obj["stratigraphically_older_than"]?.jsonPrimitive?.content,
            exclude = obj["excluded"]?.jsonPrimitive?.content?.toBooleanStrictOrNull(),
            decisionReason = obj["exclusion_reason"]?.jsonPrimitive?.content
        )
    }

    private fun parsePositiveDouble(raw: String, onError: (String) -> Unit): Double? =
        raw.trim().toDoubleOrNull()?.also { if (it <= 0) onError("must be positive") } ?: run {
            onError("not a number"); null
        }

    private fun parseOffset(obj: JsonObject, lineNo: Int, id: String): OffsetDist? {
        val reservoir = obj["reservoir"]?.jsonObject ?: return null
        return when (reservoir["kind"]?.jsonPrimitive?.content ?: "gaussian") {
            "gaussian", "delta_r" -> {
                val mean = reservoir["mean_bp"]?.jsonPrimitive?.content?.toDoubleOrNull()
                    ?: throw ValidationException("line $lineNo ($id): reservoir.mean_bp missing")
                val sigma = reservoir["sigma_bp"]?.jsonPrimitive?.content?.toDoubleOrNull()
                    ?: throw ValidationException("line $lineNo ($id): reservoir.sigma_bp missing")
                if (sigma < 0) throw ValidationException("line $lineNo ($id): reservoir sigma negative")
                OffsetDist.Gaussian(mean, sigma)
            }
            "mixture" -> {
                val components = (reservoir["components"] as? kotlinx.serialization.json.JsonArray)
                    ?: throw ValidationException("line $lineNo ($id): reservoir mixture needs components[]")
                OffsetDist.Mixture(components.map { element ->
                    val o = element.jsonObject
                    OffsetDist.Component(
                        mean = o["mean_bp"]?.jsonPrimitive?.content?.toDoubleOrNull()
                            ?: throw ValidationException("line $lineNo: component mean_bp missing"),
                        sigma = o["sigma_bp"]?.jsonPrimitive?.content?.toDoubleOrNull()
                            ?: throw ValidationException("line $lineNo: component sigma_bp missing"),
                        weight = o["weight"]?.jsonPrimitive?.content?.toDoubleOrNull()
                            ?: throw ValidationException("line $lineNo: component weight missing")
                    )
                })
            }
            "none", "zero" -> OffsetDist.Zero
            else -> throw ValidationException("line $lineNo ($id): unknown reservoir kind")
        }
    }

    /** Parse a .14l-style / chronocarbon curve JSON document. */
    fun parseCurve(json: String): CalibrationCurve = try {
        lenient.decodeFromString<CalibrationCurve>(json)
    } catch (e: Exception) {
        throw ValidationException("curve document invalid: ${e.message}")
    }
}

/** Whole-project bundle import: stable identities, order and source links are preserved verbatim. */
class BundleImporter(private val store: SqliteStore, private val clock: Clock) {
    fun importBundle(bundle: ExportBundle, replace: Boolean = true): Int {
        if (store.events().isNotEmpty()) {
            if (!replace) throw ValidationException("database already contains events")
            store.replaceAll(bundle)
        } else {
            store.replaceAll(bundle)
        }
        return bundle.events.size
    }
}
