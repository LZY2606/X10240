package org.chronology.workbench.model

import com.fasterxml.jackson.databind.JsonNode

/** Provenance pointer to an external source document and a locator inside it. */
data class SourceRef(val doc: String, val locator: String, val checksum: String? = null) {
    companion object {
        val UNKNOWN = SourceRef("unknown", "unknown", null)
    }
}

/**
 * A number exactly as submitted, with its declared unit/datum. The textual representation is
 * retained verbatim; [value] is the normalized numeric value, or null when conversion failed.
 * Unconvertible values stay in place and carry diagnostics instead of being dropped for plotting.
 */
data class RawNumber(val text: String, val unit: String, val value: Double?) {
    val convertible: Boolean get() = value != null
    fun diagnostics(path: String): List<String> =
        if (value == null) listOf("[$path] unparseable quantity '$text' ($unit); retained as raw text, not normalized")
        else emptyList()
}

/** A laboratory radiocarbon determination. Ages are normalized to conventional 14C yr BP (1950). */
data class Measurement(
    val id: String,
    val versionId: String,
    val parentVersionId: String?,
    val label: String,
    val age: RawNumber,
    val sigma: RawNumber,
    val material: String,
    val source: SourceRef,
    val eventId: String,
) {
    val usable: Boolean get() = age.convertible && sigma.convertible
}

/** One knot of a calibration curve. Knots are stored in descending calBP order (IntCal convention). */
data class CurvePoint(val calBP: Double, val c14BP: Double, val sigma: Double)

data class CalibrationCurve(
    val id: String,
    val versionId: String,
    val parentVersionId: String?,
    val name: String,
    val versionLabel: String,
    val points: List<CurvePoint>,
    val note: String,
    val source: SourceRef,
    val eventId: String,
) {
    val minCalBP: Double get() = points.last().calBP
    val maxCalBP: Double get() = points.first().calBP
}

/** One weighted component of a (possibly multi-modal) reservoir offset distribution. */
data class OffsetComponent(val deltaR: Double, val deltaRSigma: Double, val weight: Double)

data class ReservoirOffset(
    val id: String,
    val versionId: String,
    val parentVersionId: String?,
    val label: String,
    val region: String,
    val components: List<OffsetComponent>,
    val source: SourceRef,
    val eventId: String,
) {
    val weightSum: Double get() = components.sumOf { it.weight }
    val isMixture: Boolean get() = components.size > 1
}

/** Binds a measurement to a curve version and an offset hypothesis; the seed fixes any MC variant. */
data class SampleBinding(
    val id: String,
    val versionId: String,
    val parentVersionId: String?,
    val measurementId: String,
    val curveId: String,
    val offsetId: String?,
    val seed: Long,
    val source: SourceRef,
    val eventId: String,
)

/** Era/dating system convention used to render calendar years (BP vs BCE/CE against a fixed datum). */
data class EraSystem(
    val versionId: String,
    val convention: String,
    val originYearCE: Int,
    val displayDatum: String,
    val source: SourceRef,
    val eventId: String,
)

/** Stratigraphic ordering: [olderMeasurementId] is required older than [youngerMeasurementId]. */
data class OrderConstraint(
    val id: String,
    val versionId: String,
    val olderMeasurementId: String,
    val youngerMeasurementId: String,
    val source: SourceRef,
    val eventId: String,
    val supersededBy: String? = null,
) {
    val active: Boolean get() = supersededBy == null
}

/** Exclusion of a measurement from the accepted chronology (human or source decision). */
data class Exclusion(
    val id: String,
    val versionId: String,
    val measurementId: String,
    val reason: String,
    val origin: String,
    val source: SourceRef,
    val eventId: String,
    val supersededBy: String? = null,
) {
    val active: Boolean get() = supersededBy == null
}

/** Human ruling recorded against a candidate, interval or measurement. Never rewrites inputs. */
data class Decision(
    val id: String,
    val versionId: String,
    val kind: String,
    val targetRef: String,
    val effect: String,
    val rationale: String,
    val source: SourceRef,
    val eventId: String,
    val supersededBy: String? = null,
) {
    val active: Boolean get() = supersededBy == null
}

/** Immutable domain event. State is the ordered fold of payloads; the raw packet lives in an ingestion. */
data class Event(
    val id: String,
    val seq: Long,
    val atIso: String,
    val kind: String,
    val payload: JsonNode,
    val ingestionId: String?,
    val parentEventId: String? = null,
)
