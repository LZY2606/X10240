package carbonbench.case

import carbonbench.domain.DatingSystem
import kotlinx.serialization.Serializable

@Serializable
data class CaseComponent(
    val id: String,
    val weight: Double,
    val mean: Double,
    val sigma: Double,
    val basis: String
)

@Serializable
data class CaseAction(
    val atSeconds: Long,
    val op: String,
    // create
    val name: String? = null,
    val datingSystem: DatingSystem? = null,
    // measurement
    val labCode: String? = null,
    val ageRaw: String? = null,
    val ageValue: Double? = null,
    val ageUnit: String? = null,
    val sigmaRaw: String? = null,
    val sigmaValue: Double? = null,
    val sigmaUnit: String? = null,
    val sourceRef: String? = null,
    val diagnostics: List<String> = emptyList(),
    // offset
    val label: String? = null,
    val kind: String? = null,
    val components: List<CaseComponent>? = null,
    val parentLabel: String? = null,
    // constraint
    val olderLabCode: String? = null,
    val youngerLabCode: String? = null,
    val constraintId: String? = null,
    // exclusion
    val excluded: Boolean? = null,
    val reason: String? = null,
    // calibration
    val offsetLabel: String? = null,
    val curveVersion: String? = null,
    val gridStep: Double? = null,
    val calibrationId: String? = null,
    // decisions
    val intervalId: String? = null,
    val decision: String? = null,
    val note: String? = null,
    val replacementCalibrationId: String? = null,
    // sequence / candidate
    val calibrationIds: List<String>? = null,
    val analysisId: String? = null,
    val candidateId: String? = null,
    val targetCurveVersion: String? = null,
    // utility
    val expectMultimodal: Boolean? = null,
    val expectStatus: String? = null,
    val expectConflict: Boolean? = null
)

@Serializable
data class CasePack(
    val casePackId: String,
    val title: String,
    val version: String,
    val description: String,
    val projectId: String,
    val narrative: List<String>,
    val actions: List<CaseAction>
)
