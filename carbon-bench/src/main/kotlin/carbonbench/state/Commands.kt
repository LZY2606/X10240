package carbonbench.state

import carbonbench.domain.DatingSystem

data class CreateProjectCmd(
    val name: String,
    val casePackId: String? = null,
    val casePackVersion: String? = null,
    val datingSystem: DatingSystem
)

data class OffsetComponentCmd(val id: String, val weight: Double, val mean: Double, val sigma: Double, val basis: String)

data class RecordOffsetCmd(
    val label: String,
    val kind: String,
    val components: List<OffsetComponentCmd>,
    val sourceRef: String,
    val parentId: String? = null
)

data class SubmitMeasurementCmd(
    val labCode: String,
    val ageRaw: String,
    val ageValue: Double?,
    val ageUnit: String,
    val sigmaRaw: String,
    val sigmaValue: Double?,
    val sigmaUnit: String,
    val sourceRef: String,
    val diagnostics: List<String> = emptyList()
)

data class ReviseMeasurementCmd(val labCode: String, val ageValue: Double?, val ageRaw: String, val sigmaValue: Double?, val sigmaRaw: String, val sourceRef: String, val note: String = "")

data class AssertConstraintCmd(val olderLabCode: String, val youngerLabCode: String, val sourceRef: String)
data class RetractConstraintCmd(val constraintId: String, val note: String)
data class ExclusionCmd(val labCode: String, val excluded: Boolean, val reason: String, val sourceRef: String)
data class RetractDecisionCmd(val decisionId: String, val note: String)
data class CalibrateCmd(
    val labCode: String,
    val offsetId: String? = null,
    val curveVersion: String = "1",
    val gridStep: Double = 2.0,
    val label: String? = null
)
data class AnalyzeSequenceCmd(val calibrationIds: List<String> = emptyList(), val label: String? = null)
data class IntervalDecisionCmd(val calibrationId: String, val intervalId: String, val decision: String, val note: String, val replacementCalibrationId: String? = null)
data class AcceptCandidateCmd(val analysisId: String, val candidateId: String, val note: String)
data class CurveUpgradeCmd(val calibrationId: String, val targetCurveVersion: String, val note: String = "")
