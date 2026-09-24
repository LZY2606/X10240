package org.chronolab.carbon.store

import kotlinx.serialization.json.Json
import org.chronolab.carbon.model.ArchiveCaseResult
import org.chronolab.carbon.model.Branch
import org.chronolab.carbon.model.CalibrationResult
import org.chronolab.carbon.model.DecisionRecord
import org.chronolab.carbon.model.DecisionVerb
import org.chronolab.carbon.model.DerivedNode
import org.chronolab.carbon.model.EvArchiveRecorded
import org.chronolab.carbon.model.EvBranchCreated
import org.chronolab.carbon.model.EvCalibrationComputed
import org.chronolab.carbon.model.EvDecision
import org.chronolab.carbon.model.EvDerivedStatus
import org.chronolab.carbon.model.EvDecisionUndone
import org.chronolab.carbon.model.EvNote
import org.chronolab.carbon.model.EvSequenceComputed
import org.chronolab.carbon.model.EvSourceCommitted
import org.chronolab.carbon.model.ProjectEvent
import org.chronolab.carbon.model.SequenceAssessment
import org.chronolab.carbon.model.SourceKind
import org.chronolab.carbon.model.SourceNode

data class ProjectState(
    val branches: Map<String, Branch>,
    val sourcesByLogical: Map<String, List<SourceNode>>,
    val calibrations: Map<String, List<DerivedNode>>,
    val assessments: Map<String, List<DerivedNode>>,
    val decisions: List<DecisionRecord>,
    val archiveResults: List<ArchiveCaseResult>,
    val notes: List<String>,
    val events: List<ProjectEvent>
) {
    fun currentSources(): List<SourceNode> = sourcesByLogical.values.mapNotNull { it.lastOrNull() }
    fun source(logicalId: String) = sourcesByLogical[logicalId]?.lastOrNull()
    fun sourceByNode(nodeId: String) = sourcesByLogical.values.flatten().firstOrNull { it.nodeId == nodeId }
    fun currentCalibration(logicalId: String) = calibrations[logicalId]?.lastOrNull()
    fun currentAssessment(logicalId: String) = assessments[logicalId]?.lastOrNull()
    fun activeDecisions(): List<DecisionRecord> = decisions.filter { !it.undone }

    val curves: List<SourceNode> get() = currentSources().filter { it.kind == SourceKind.CALIBRATION_CURVE }
    val measurements: List<SourceNode> get() = currentSources().filter { it.kind == SourceKind.MEASUREMENT }
}

object Projection {
    val json = Json { ignoreUnknownKeys = true }

    fun project(events: List<ProjectEvent>, branches: List<Branch>): ProjectState {
        val sources = mutableMapOf<String, MutableList<SourceNode>>()
        val calibs = mutableMapOf<String, MutableList<DerivedNode>>()
        val assess = mutableMapOf<String, MutableList<DerivedNode>>()
        val decisions = mutableListOf<DecisionRecord>()
        val archive = mutableListOf<ArchiveCaseResult>()
        val notes = mutableListOf<String>()
        for (ev in events) {
            when (ev.type) {
                "SourceCommitted" -> {
                    val s = json.decodeFromString<EvSourceCommitted>(ev.payloadJson).source
                    sources.getOrPut(s.logicalId) { mutableListOf() }.add(s)
                }
                "CalibrationComputed" -> {
                    val r = json.decodeFromString<EvCalibrationComputed>(ev.payloadJson)
                    val logical = logicalIdForCalibration(r.result.calibrationId)
                    calibs.getOrPut(logical) { mutableListOf() }.add(
                        DerivedNode(r.result.calibrationId, logical, "CALIBRATION",
                            versionOf(r.result.calibrationId), calibs[logical]?.lastOrNull()?.nodeId,
                            r.status, calibration = r.result)
                    )
                }
                "SequenceComputed" -> {
                    val r = json.decodeFromString<EvSequenceComputed>(ev.payloadJson)
                    val logical = r.assessment.assessmentId.substringBeforeLast("@v")
                    assess.getOrPut(logical) { mutableListOf() }.add(
                        DerivedNode(r.assessment.assessmentId, logical, "SEQUENCE_ASSESSMENT",
                            versionOf(r.assessment.assessmentId), assess[logical]?.lastOrNull()?.nodeId,
                            r.status, assessment = r.assessment)
                    )
                }
                "Decision" -> decisions += json.decodeFromString<EvDecision>(ev.payloadJson).decision
                "DecisionUndone" -> {
                    val u = json.decodeFromString<EvDecisionUndone>(ev.payloadJson)
                    val idx = decisions.indexOfLast { it.decisionId == u.decisionId }
                    if (idx >= 0) decisions[idx] = decisions[idx].copy(undone = true, undoneByEventId = u.undoEventId)
                }
                "DerivedStatus" -> {
                    val ds = json.decodeFromString<EvDerivedStatus>(ev.payloadJson)
                    val map = if (ds.kind == "CALIBRATION") calibs else assess
                    val logical = ds.nodeId.removePrefix("cal-").substringBefore("@v")
                    val list = map[logical]
                    if (list != null) {
                        val idx = list.indexOfLast { it.nodeId == ds.nodeId }
                        if (idx >= 0) list[idx] = list[idx].copy(status = ds.status)
                    }
                }
                "BranchCreated" -> Unit
                "ArchiveRecorded" -> archive += json.decodeFromString<EvArchiveRecorded>(ev.payloadJson).result
                "Note" -> notes += json.decodeFromString<EvNote>(ev.payloadJson).text
            }
        }
        return ProjectState(branches.associateBy { it.branchId }, sources, calibs, assess, decisions, archive, notes, events)
    }

    private fun versionOf(id: String): Int = id.substringAfter("@v").toIntOrNull() ?: 1
    private fun logicalIdForCalibration(calibrationId: String): String {
        // calibrationId = "cal-" + measurementLogicalId + "@vN"
        val core = calibrationId.removePrefix("cal-").substringBefore("@v")
        return core
    }
}
