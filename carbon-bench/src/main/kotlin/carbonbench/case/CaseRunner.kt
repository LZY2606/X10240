package carbonbench.case

import carbonbench.domain.CalibrationResult
import carbonbench.state.AnalyzeSequenceCmd
import carbonbench.state.AssertConstraintCmd
import carbonbench.state.CalibrateCmd
import carbonbench.state.CreateProjectCmd
import carbonbench.state.CurveUpgradeCmd
import carbonbench.state.DecideExclusion
import carbonbench.state.ExclusionCmd
import carbonbench.state.IntervalDecisionCmd
import carbonbench.state.OffsetComponentCmd
import carbonbench.state.ProjectService
import carbonbench.state.RecordOffsetCmd
import carbonbench.state.RetractConstraintCmd
import carbonburn.placeholder
import carbonbench.state.SubmitMeasurementCmd

class CaseAssertionFailure(message: String) : RuntimeException(message)

data class CaseRunReport(
    val projectId: String,
    val calibrationByLabel: Map<String, String>,
    val checks: List<String>
)

class CaseRunner(private val service: ProjectService) {

    fun run(pack: CasePack, clock: carbonbench.time.VirtualClock): CaseRunReport {
        clock.setEpoch()
        val checks = mutableListOf<String>()
        val calByLabel = LinkedHashMap<String, String>()
        var projectId = pack.projectId

        for (action in pack.actions) {
            clock.at(action.atSeconds)
            when (action.op) {
                "create-project" -> {
                    val (id, _) = service.createProject(
                        CreateProjectCmd(
                            name = action.name!!,
                            casePackId = pack.casePackId,
                            casePackVersion = pack.version,
                            datingSystem = action.datingSystem!!
                        ),
                        proposedId = pack.projectId
                    )
                    projectId = id
                }
                "submit-measurement" -> service.submitMeasurement(
                    projectId,
                    SubmitMeasurementCmd(
                        labCode = action.labCode!!,
                        ageRaw = action.ageRaw!!,
                        ageValue = action.ageValue,
                        ageUnit = action.ageUnit!!,
                        sigmaRaw = action.sigmaRaw!!,
                        sigmaValue = action.sigmaValue,
                        sigmaUnit = action.sigmaUnit!!,
                        sourceRef = action.sourceRef!!,
                        diagnostics = action.diagnostics
                    )
                )
                "revise-measurement" -> service.reviseMeasurement(
                    projectId,
                    carbonbench.state.ReviseMeasurementCmd(
                        labCode = action.labCode!!,
                        ageValue = action.ageValue,
                        ageRaw = action.ageRaw!!,
                        sigmaValue = action.sigmaValue,
                        sigmaRaw = action.sigmaRaw!!,
                        sourceRef = action.sourceRef!!,
                        note = action.note ?: ""
                    )
                )
                "record-offset", "revise-offset" -> {
                    val snap = service.view(projectId)
                    val parentId = action.parentLabel?.let { label ->
                        snap.offsets.filter { it.label == label }.maxByOrNull { it.id }!!.id
                    }
                    service.recordOffset(
                        projectId,
                        RecordOffsetCmd(
                            label = action.label!!,
                            kind = action.kind!!,
                            components = action.components!!.map {
                                OffsetComponentCmd(it.id, it.weight, it.mean, it.sigma, it.basis)
                            },
                            sourceRef = action.sourceRef!!,
                            parentId = parentId
                        )
                    )
                }
                "assert-constraint" -> service.assertConstraint(
                    projectId,
                    AssertConstraintCmd(action.olderLabCode!!, action.youngerLabCode!!, action.sourceRef!!)
                )
                "retract-constraint" -> service.retractConstraint(
                    projectId, RetractConstraintCmd(action.constraintId!!, action.note ?: "")
                )
                "decide-exclusion" -> service.decideExclusion(
                    projectId,
                    ExclusionCmd(action.labCode!!, action.excluded!!, action.reason!!, action.sourceRef!!)
                )
                "calibrate" -> {
                    val snap = service.view(projectId)
                    val offsetId = action.offsetLabel?.let { label ->
                        snap.offsets.filter { it.label == label && it.active }
                            .maxByOrNull { it.id }?.id
                            ?: throw CaseAssertionFailure("偏移标签未解析：$label")
                    }
                    val (_, result) = service.calibrate(
                        projectId,
                        CalibrateCmd(
                            labCode = action.labCode!!,
                            offsetId = offsetId,
                            curveVersion = action.curveVersion ?: "1",
                            gridStep = action.gridStep ?: 2.0,
                            label = action.label
                        )
                    )
                    if (action.label != null) calByLabel[action.label] = result.id
                    if (action.expectMultimodal != null) {
                        if (result.multimodal != action.expectMultimodal)
                            throw CaseAssertionFailure(
                                "${action.label ?: result.id}: 期望 multimodal=${action.expectMultimodal} 实际=${result.multimodal}，68% 区间数=${result.intervals68.size}"
                            )
                        checks += "multimodal[${action.label}] = ${result.multimodal} (68%峰数=${result.intervals68.size})"
                    }
                    if (action.expectStatus != null && result.status != action.expectStatus)
                        throw CaseAssertionFailure("${result.id} 状态 ${result.status} != ${action.expectStatus}")
                }
                "analyze-sequence" -> {
                    val ids = action.calibrationIds?.map { calByLabel[it] ?: it } ?: emptyList()
                    val (_, analysis) = service.analyzeSequence(
                        projectId, AnalyzeSequenceCmd(ids, action.label)
                    )
                    if (action.label != null) calByLabel[action.label] = analysis.id
                    if (action.expectConflict != null) {
                        val hasConflict = analysis.pairs.any { it.verdict == "conflict" }
                        if (hasConflict != action.expectConflict)
                            throw CaseAssertionFailure(
                                "层序冲突期望 ${action.expectConflict} 实际 $hasConflict：${analysis.pairs.map { it.constraintId to it.verdict }}"
                            )
                        checks += "conflict[${action.label}] = $hasConflict (候选调整数=${analysis.candidates.size})"
                    }
                }
                "decide-interval" -> service.decideInterval(
                    projectId,
                    IntervalDecisionCmd(
                        calibrationId = calByLabel[action.calibrationId] ?: action.calibrationId!!,
                        intervalId = action.intervalId!!,
                        decision = action.decision!!,
                        note = action.note ?: "",
                        replacementCalibrationId = action.replacementCalibrationId?.let { calByLabel[it] ?: it }
                    )
                )
                "accept-candidate" -> service.acceptCandidate(
                    projectId,
                    calByLabel[action.analysisId] ?: action.analysisId!!,
                    action.candidateId!!,
                    action.note ?: ""
                )
                "upgrade-curve" -> {
                    val (_, result) = service.upgradeCurve(
                        projectId,
                        CurveUpgradeCmd(
                            calibrationId = calByLabel[action.calibrationId] ?: action.calibrationId!!,
                            targetCurveVersion = action.targetCurveVersion!!,
                            note = action.note ?: ""
                        )
                    )
                    if (action.label != null) calByLabel[action.label] = result.id
                    checks += "curve-upgrade -> ${result.id}@${result.curveVersion} fp=${result.curveFingerprint.take(8)}"
                }
                "verify-calibration" -> {
                    val targetId = calByLabel[action.calibrationId] ?: action.calibrationId!!
                    val (_, reproducible) = service.verifyCalibration(projectId, targetId)
                    if (!reproducible) throw CaseAssertionFailure("校准不可复现：$targetId")
                    checks += "reproducible[$targetId] = true"
                }
                else -> throw CaseAssertionFailure("未知案例动作：${action.op}")
            }
        }
        return CaseRunReport(projectId, calByLabel, checks)
    }
}
