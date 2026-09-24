package org.chrono.workbench.db

import org.chrono.workbench.events.EventTypes
import org.chrono.workbench.events.StoredEvent
import org.chrono.workbench.model.Curve
import org.chrono.workbench.model.EraSystem
import org.chrono.workbench.model.ExclusionDecision
import org.chrono.workbench.model.Measurement
import org.chrono.workbench.model.ReservoirOffset
import org.chrono.workbench.model.SequenceConstraint

object Reducer {
    fun reduce(state: ProjectState, events: List<StoredEvent>, json: kotlinx.serialization.json.Json) {
        for (e in events) apply(state, e, json)
    }

    @Suppress("UNUSED_PARAMETER")
    fun apply(state: ProjectState, e: StoredEvent, json: kotlinx.serialization.json.Json) {
        when (e.type) {
            EventTypes.PROJECT_OPENED -> {
                val p = json.decodeFromString<Payloads.ProjectOpendedSafe>(e.payload.toString())
                state.projectId = p.projectId
                state.projectName = p.name
                state.opened = true
            }
            EventTypes.CURVE_REGISTERED, EventTypes.CURVE_SUPERSEDED -> {
                val p = json.decodeFromString<Payloads.CurvePayload>(e.payload.toString())
                state.curves[p.curve.id] = p.curve
                state.activeCurveId = p.curve.id
            }
            EventTypes.MEASUREMENT_IMPORTED, EventTypes.MEASUREMENT_REVISED -> {
                val p = json.decodeFromString<Payloads.MeasurementPayload>(e.payload.toString())
                state.measurements[p.measurement.id] = p.measurement
            }
            EventTypes.RESERVOIR_SET -> {
                val p = json.decodeFromString<Payloads.ReservoirPayload>(e.payload.toString())
                state.reservoirs[p.reservoir.id] = p.reservoir
            }
            EventTypes.ERA_SET -> {
                val p = json.decodeFromString<Payloads.EraPayload>(e.payload.toString())
                state.eraSystem = p.era
            }
            EventTypes.SEQUENCE_SET -> {
                val p = json.decodeFromString<Payloads.ConstraintPayload>(e.payload.toString())
                state.constraints[p.constraint.id] = p.constraint
                state.sequenceAtEventSeq = e.seq
            }
            EventTypes.EXCLUSION_DECIDED -> {
                val p = json.decodeFromString<Payloads.DecisionPayload>(e.payload.toString())
                state.decisions[p.decision.id] = p.decision
            }
            EventTypes.EVENT_REVERSED -> {
                val p = json.decodeFromString<Payloads.ReversalPayload>(e.payload.toString())
                deactivate(state, p.reversedEventId, json)
            }
            else -> Unit
        }
        if (!state.events.any { it.seq == e.seq }) state.events.add(e)
    }

    private fun deactivate(state: ProjectState, reversedEventId: String, json: kotlinx.serialization.json.Json) {
        val original = state.events.firstOrNull { it.eventId == reversedEventId } ?: return
        when (original.type) {
            EventTypes.RESERVOIR_SET -> {
                val p = json.decodeFromString<Payloads.ReservoirPayload>(original.payload.toString())
                state.reservoirs.remove(p.reservoir.id)
            }
            EventTypes.SEQUENCE_SET -> {
                val p = json.decodeFromString<Payloads.ConstraintPayload>(original.payload.toString())
                state.constraints[p.constraint.id]?.let { state.constraints[p.constraint.id] = it.copy(active = false) }
            }
            EventTypes.EXCLUSION_DECIDED -> {
                val p = json.decodeFromString<Payloads.DecisionPayload>(original.payload.toString())
                state.decisions.remove(p.decision.id)
            }
            else -> Unit
        }
    }
}
