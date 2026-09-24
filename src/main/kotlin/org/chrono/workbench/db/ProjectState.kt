package org.chrono.workbench.db

import org.chrono.workbench.domain.CalibrationInput
import org.chrono.workbench.domain.CalibrationResult
import org.chrono.workbench.events.StoredEvent
import org.chrono.workbench.model.*

data class DerivedRecord(
    val result: CalibrationResult,
    val input: CalibrationInput,
    val createdAtEventSeq: Int,
    val stale: Boolean,
    val staleReason: String?
)

data class ProjectState(
    var projectId: String = "gsb-seat",
    var projectName: String = "碳年候选席",
    var opened: Boolean = false,
    val curves: LinkedHashMap<String, Curve> = LinkedHashMap(),
    val measurements: LinkedHashMap<String, Measurement> = LinkedHashMap(),
    val reservoirs: LinkedHashMap<String, ReservoirOffset> = LinkedHashMap(),
    var eraSystem: EraSystem? = null,
    val constraints: LinkedHashMap<String, SequenceConstraint> = LinkedHashMap(),
    val decisions: LinkedHashMap<String, ExclusionDecision> = LinkedHashMap(),
    val events: MutableList<StoredEvent> = mutableListOf(),
    val derived: LinkedHashMap<String, DerivedRecord> = LinkedHashMap(),
    var activeCurveId: String? = null,
    var sequenceAtEventSeq: Int? = null
) {
    fun reservoirFor(measurementId: String): ReservoirOffset? {
        val candidates = reservoirs.values.filter { it.measurementId == measurementId }
        return candidates.maxByOrNull { it.seq }
    }

    fun decisionFor(measurementId: String): ExclusionDecision? =
        decisions.values.filter { it.measurementId == measurementId }.maxByOrNull { it.seq }

    fun curveVersionFor(curveId: String): Curve? = curves[curveId]

    fun activeCurve(): Curve? = activeCurveId?.let { curves[it] }
}
