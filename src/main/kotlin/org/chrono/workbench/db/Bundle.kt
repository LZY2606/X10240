package org.chrono.workbench.db

import kotlinx.serialization.Serializable
import org.chrono.workbench.domain.CalibrationInput
import org.chrono.workbench.domain.CalibrationResult
import org.chrono.workbench.events.StoredEvent

@Serializable
data class BundleDerivedRow(
    val input: CalibrationInput,
    val result: CalibrationResult,
    val eventSeq: Int
)

@Serializable
data class ExportBundle(
    val format: String = "gsb-workbench-bundle",
    val formatVersion: Int = 1,
    val projectId: String,
    val projectName: String,
    val exportedAtInstant: String,
    val events: List<StoredEvent>,
    val derived: List<BundleDerivedRow>
)

@Serializable
data class ImportReport(
    val importedEvents: Int,
    val importedDerived: Int,
    val eventIdsStable: Boolean,
    val firstEventId: String,
    val lastEventId: String
)
