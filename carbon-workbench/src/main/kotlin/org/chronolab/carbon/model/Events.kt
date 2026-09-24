package org.chronolab.carbon.model

import kotlinx.serialization.Serializable

@Serializable
data class ProjectEvent(
    val eventId: String,
    val seq: Long,
    val branchId: String,
    val virtualTime: String,
    val type: String,
    val payloadJson: String,
    val basisJson: String? = null
)

@Serializable
data class Branch(
    val branchId: String,
    val label: String,
    val parentBranchId: String? = null,
    val forkedFromEventId: String? = null,
    val headSeq: Long = 0L,
    val isMain: Boolean = false
)

@Serializable
data class EvSourceCommitted(val source: SourceNode)

@Serializable
data class EvCalibrationComputed(val result: CalibrationResult, val status: String)

@Serializable
data class EvSequenceComputed(val assessment: SequenceAssessment, val status: String)

@Serializable
data class EvDecision(val decision: DecisionRecord)

@Serializable
data class EvDecisionUndone(val decisionId: String, val undoEventId: String, val reason: String)

@Serializable
data class EvBranchCreated(val branch: Branch)

@Serializable
data class EvArchiveRecorded(val result: ArchiveCaseResult)

@Serializable
data class EvNote(val text: String, val actor: String, val refNodeId: String? = null)

@Serializable
data class EvDerivedStatus(val nodeId: String, val kind: String, val status: String, val reason: String, val changedSourceNodeId: String)
