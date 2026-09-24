package org.chronology.workbench

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class Provenance(
    val sourceId: String,
    val title: String,
    val kind: String,
    val reference: String = "",
    val retrievedAt: String? = null,
)

@Serializable
data class Quantity(
    val rawRepresentation: String,
    val numericValue: Double? = null,
    val unit: String,
    val coordinateDatum: String? = null,
    val diagnostic: String? = null,
)

@Serializable
data class CurvePoint(
    val calendarBP: Double,
    val radiocarbonBP: Double,
    val sigmaRadiocarbonBP: Double,
    val sourceId: String,
)

@Serializable
data class EventRecord(
    val id: String,
    val seq: Long = 0,
    val at: String,
    val type: String,
    val subjectId: String,
    val versionId: String? = null,
    val parentVersionId: String? = null,
    val payload: JsonObject,
    val directSources: List<String> = emptyList(),
    val algorithmVersion: String? = null,
    val parameterFingerprint: String? = null,
    val seed: Long? = null,
)

@Serializable
data class Bundle(
    val schema: String,
    val project: ProjectInfo,
    val events: List<EventRecord>,
)

@Serializable
data class ProjectInfo(
    val projectId: String,
    val title: String,
    val description: String = "",
    val coordinateDatum: String = "CAL_BP_CE1950",
)

@Serializable
data class ImportReport(
    val importedEvents: Long,
    val projectId: String,
    val diagnostics: List<Diagnostic>,
)

@Serializable
data class Diagnostic(
    val code: String,
    val subjectId: String,
    val message: String,
    val severity: String,
    val eventId: String? = null,
)
