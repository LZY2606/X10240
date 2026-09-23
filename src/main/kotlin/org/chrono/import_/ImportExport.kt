package org.chrono.import_

import kotlinx.serialization.Serializable
import org.chrono.model.CalibrationCurveSpec
import org.chrono.model.EpochSystemSpec
import org.chrono.model.ExclusionSpec
import org.chrono.model.Hashing
import org.chrono.model.IdKinds
import org.chrono.model.MeasurementSpec
import org.chrono.model.OffsetBindingSpec
import org.chrono.model.Qty
import org.chrono.model.ReservoirSpec
import org.chrono.model.SequenceConstraintSpec
import org.chrono.model.StoredEvent
import org.chrono.model.Units
import org.chrono.model.canonicalJson
import kotlinx.serialization.encodeToString
import kotlinx.serialization.serializer
import org.chrono.store.ItemKinds
import org.chrono.store.SourceCommitInput
import org.chrono.store.SqliteEventStore

class ImportFailure(message: String) : RuntimeException(message)

@Serializable
data class ImportDiagnostic(
    val path: String,
    val code: String,
    val severity: String,
    val message: String,
    val rawValue: String? = null,
)

/**
 * Small, copyable case package. This is domain material (curves, samples, reservoir offsets,
 * epoch systems, sequence constraints, exclusions) - never a generic spreadsheet.
 */
@Serializable
data class CasePackage(
    val packageFormat: String = "carbon-seat-case/v1",
    val packageId: String,
    val title: String,
    val projectCode: String,
    val narrative: String? = null,
    val curves: List<CalibrationCurveSpec> = emptyList(),
    val measurements: List<MeasurementSpec> = emptyList(),
    val reservoirs: List<ReservoirSpec> = emptyList(),
    val offsetBindings: List<OffsetBindingSpec> = emptyList(),
    val epochSystems: List<EpochSystemSpec> = emptyList(),
    val sequences: List<SequenceConstraintSpec> = emptyList(),
    val exclusions: List<ExclusionSpec> = emptyList(),
)

@Serializable
data class EventPackage(
    val packageFormat: String = "carbon-seat-events/v1",
    val packageId: String,
    val title: String,
    val events: List<StoredEvent>,
)

object CaseImporter {

    fun parseCasePackage(jsonText: String): CasePackage {
        val parsed = runCatching { canonicalJson.decodeFromString<CasePackage>(jsonText) }
            .getOrElse { failure -> throw ImportFailure("invalid case package JSON: ${failure.message}") }
        if (parsed.packageFormat !in setOf("carbon-seat-case/v1")) {
            throw ImportFailure("unsupported packageFormat: ${parsed.packageFormat}")
        }
        if (parsed.curves.isEmpty()) throw ImportFailure("case must contain at least one calibration curve")
        if (parsed.measurements.isEmpty()) throw ImportFailure("case must contain at least one measurement")
        return parsed
    }

    /**
     * Translate package sources into commit inputs. Each call returns one commit input per
     * source plus diagnostics for rows that cannot be normalized. Unconvertible values stay
     * at their source position (the diagnostic references the exact item/path); nothing is
     * silently dropped to make the plots work.
     */
    fun toCommitInputs(pkg: CasePackage): Pair<List<SourceCommitInput>, List<ImportDiagnostic>> {
        val diagnostics = mutableListOf<ImportDiagnostic>()
        val inputs = mutableListOf<SourceCommitInput>()

        pkg.curves.forEach { curve ->
            if (curve.points.size < 2) {
                diagnostics += ImportDiagnostic("curves.${curve.curveId}", "CURVE-TOO-SHORT", "error",
                    "curve needs >=2 points")
                return@forEach
            }
            if (curve.points.zipWithNext().any { it.first.calBp <= it.second.calBp }) {
                diagnostics += ImportDiagnostic("curves.${curve.curveId}.points", "CURVE-NOT-ORDERED", "error",
                    "curve points must be strictly decreasing in cal BP")
                return@forEach
            }
            inputs += source("curve-${curve.curveId}@${curve.version}", ItemKinds.CURVE,
                "CalibrationCurveSpec", canonicalJson.encodeToString(serializer<CalibrationCurveSpec>(), curve),
                curve.sourceRef ?: "case-package:${pkg.packageId}")
        }

        pkg.measurements.forEach { measurement ->
            val path = "measurements.${measurement.sampleCode}"
            if (measurement.age.unit != Units.C14_YR_BP) {
                diagnostics += ImportDiagnostic("$path.age", "UNIT-UNCONVERTED", "warn",
                    "age unit '${measurement.age.unit}' retained as-is; derived layer expects ${Units.C14_YR_BP}",
                    rawValue = measurement.rawAgeText)
            }
            if (measurement.sigma.value <= 0) {
                diagnostics += ImportDiagnostic("$path.sigma", "NONPOSITIVE-SIGMA", "error",
                    "measurement sigma must be > 0", rawValue = measurement.rawSigmaText)
                return@forEach
            }
            inputs += source("measurement-${measurement.sampleCode}", ItemKinds.MEASUREMENT,
                "MeasurementSpec", canonicalJson.encodeToString(serializer<MeasurementSpec>(), measurement),
                measurement.sourceRef ?: "case-package:${pkg.packageId}")
        }

        pkg.reservoirs.forEach { reservoir ->
            inputs += source("reservoir-${reservoir.reservoirCode}", ItemKinds.RESERVOIR,
                "ReservoirSpec", canonicalJson.encodeToString(serializer<ReservoirSpec>(), reservoir),
                reservoir.sourceRef ?: "case-package:${pkg.packageId}")
        }
        pkg.offsetBindings.forEach { binding ->
            inputs += source("binding-${binding.sampleCode}", ItemKinds.OFFSET_BINDING,
                "OffsetBindingSpec", canonicalJson.encodeToString(serializer<OffsetBindingSpec>(), binding),
                binding.sourceRef ?: "case-package:${pkg.packageId}")
        }
        pkg.epochSystems.forEach { epoch ->
            inputs += source("epoch-${epoch.code}", ItemKinds.EPOCH,
                "EpochSystemSpec", canonicalJson.encodeToString(serializer<EpochSystemSpec>(), epoch),
                epoch.sourceRef ?: "case-package:${pkg.packageId}")
        }
        pkg.sequences.forEach { sequence ->
            inputs += source("sequence-${sequence.chainCode}", ItemKinds.SEQUENCE,
                "SequenceConstraintSpec", canonicalJson.encodeToString(serializer<SequenceConstraintSpec>(), sequence),
                sequence.sourceRef ?: "case-package:${pkg.packageId}")
        }
        pkg.exclusions.forEach { exclusion ->
            inputs += source("exclusion-${exclusion.sampleCode}", ItemKinds.EXCLUSION,
                "ExclusionSpec", canonicalJson.encodeToString(serializer<ExclusionSpec>(), exclusion),
                exclusion.sourceRef ?: "case-package:${pkg.packageId}")
        }
        return inputs to diagnostics
    }

    private fun source(
        itemId: String,
        kind: String,
        payloadClass: String,
        json: String,
        sourceRef: String,
    ) = SourceCommitInput(itemId, kind, json, payloadClass, sourceRef)
}

object EventPackages {
    fun export(packageId: String, title: String, events: List<StoredEvent>): EventPackage =
        EventPackage(packageId = packageId, title = title, events = events)

    fun exportJson(pkg: EventPackage): String = canonicalJson.encodeToString(serializer<EventPackage>(), pkg)

    fun packageSha(json: String): String = Hashing.sha256Hex(json)

    fun parseEventPackage(jsonText: String): EventPackage {
        val pkg = runCatching { canonicalJson.decodeFromString<EventPackage>(jsonText) }
            .getOrElse { failure -> throw ImportFailure("invalid event package JSON: ${failure.message}") }
        if (pkg.packageFormat != "carbon-seat-events/v1") {
            throw ImportFailure("unsupported event package format: ${pkg.packageFormat}")
        }
        SqliteEventStore.verifyImport(pkg.events)
        return pkg
    }
}
