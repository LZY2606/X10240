package org.chronocarbon

import kotlinx.serialization.Serializable
import java.nio.file.Path

@Serializable
data class ExportBundle(
    val bundleFormat: String = "chronocarbon-bundle/1",
    val projectId: String,
    val exportedAt: String,
    val events: List<EventEnvelope>,
    val derivations: List<DerivationRecord>,
    val candidates: List<AdjustmentCandidate>
)

interface EventStore : AutoCloseable {
    val projectId: String
    fun nextSeq(): Long
    fun append(envelope: EventEnvelope)
    fun events(atSeq: Long? = null): List<EventEnvelope>
    fun event(eventId: String): EventEnvelope?

    fun saveDerivation(record: DerivationRecord): DerivationRecord
    fun derivations(kind: String? = null): List<DerivationRecord>
    fun derivation(id: String): DerivationRecord?
    fun markCalibrationsStale(predicate: (DerivationRecord) -> Boolean)
    fun markSequenceStale(predicate: (DerivationRecord) -> Boolean)

    fun saveCandidate(candidate: AdjustmentCandidate)
    fun candidates(): List<AdjustmentCandidate>
    fun candidate(id: String): AdjustmentCandidate?
    fun resolveCandidate(id: String, accept: Boolean, byEventId: String, reason: String?)

    fun markUndone(eventId: String)
    fun markMeasurementSuperseded(measurementId: String)

    fun exportBundle(exportedAt: String): ExportBundle
    override fun close()
}
