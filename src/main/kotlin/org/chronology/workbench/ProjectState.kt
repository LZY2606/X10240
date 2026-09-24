package org.chronology.workbench

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class SourceRange(
    val subjectId: String,
    val versionId: String,
    val startBP: Double,
    val endBP: Double,
    val changeStartBP: Double? = null,
    val changeEndBP: Double? = null,
)

@Serializable
data class InputVersion(
    val subjectId: String,
    val versionId: String,
    val parentVersionId: String? = null,
    val kind: String,
    val eventId: String,
    val seq: Long,
    val payload: JsonObject,
    val directSources: List<String>,
    val supersededBy: String? = null,
)

@Serializable
data class DerivedView(
    val eventId: String,
    val requestEventId: String,
    val kind: String,
    val subjectId: String,
    val versionId: String,
    val seq: Long,
    val status: String,
    val payload: JsonObject,
    val directSources: List<String>,
    val algorithmVersion: String,
    val parameterFingerprint: String,
    val sourceRanges: List<SourceRange>,
)

@Serializable
data class DecisionView(
    val eventId: String,
    val subjectId: String,
    val versionId: String,
    val seq: Long,
    val action: String,
    val targetType: String,
    val targetId: String,
    val payload: JsonObject,
    val directSources: List<String>,
    val reversed: Boolean = false,
)

@Serializable
data class ProjectState(
    val projectId: String,
    val title: String,
    val description: String,
    val coordinateDatum: String,
    val events: List<EventRecord> = emptyList(),
    val inputs: Map<String, InputVersion> = emptyMap(),
    val inputHistory: Map<String, List<InputVersion>> = emptyMap(),
    val derived: Map<String, DerivedView> = emptyMap(),
    val decisions: Map<String, DecisionView> = emptyMap(),
    val diagnostics: List<Diagnostic> = emptyList(),
    val maxSeq: Long = 0,
)
