package org.chrono.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant

/** Canonical JSON: stable key order and no whitespace, so fingerprints reproduce byte-for-byte. */
val canonicalJson: Json = Json {
    classDiscriminator = "t"
    encodeDefaults = true
    ignoreUnknownKeys = true
}

fun canonicalJsonString(value: Any): String = canonicalJson.encodeToString(value)

@Serializable
data class ProvenanceRef(
    val eventId: String,
    val kind: String,
    val sourceRef: String? = null,
)

@Serializable
sealed class DomainEvent {
    abstract val at: String
    abstract val actor: String
    abstract val note: String?

    @Serializable
    data class ProjectCreated(
        override val at: String,
        override val actor: String,
        val projectCode: String,
        val title: String,
        override val note: String? = null,
    ) : DomainEvent()

    @Serializable
    data class SourceCommitted(
        override val at: String,
        override val actor: String,
        val itemId: String,
        val kind: String,
        val version: Int,
        val parentVersion: Int?,
        val payloadClass: String,
        val payloadJson: String,
        val sourceRef: String,
        val contentSha: String,
        override val note: String? = null,
    ) : DomainEvent()

    @Serializable
    data class DerivationRecorded(
        override val at: String,
        override val actor: String,
        val derivationId: String,
        val kind: String,
        val algorithmVersion: String,
        val parameterFingerprint: String,
        val resultJson: String,
        val directSources: List<ProvenanceRef>,
        val status: String = "current",
        override val note: String? = null,
    ) : DomainEvent()

    @Serializable
    data class DecisionRecorded(
        override val at: String,
        override val actor: String,
        val decisionId: String,
        val subject: String,
        val verdict: String,
        val rationale: String,
        val candidates: List<String> = emptyList(),
        val rejectedReason: String? = null,
        val effective: Boolean = true,
        val directSources: List<ProvenanceRef> = emptyList(),
        override val note: String? = null,
    ) : DomainEvent()

    @Serializable
    data class DecisionRevoked(
        override val at: String,
        override val actor: String,
        val decisionId: String,
        val reason: String,
        override val note: String? = null,
    ) : DomainEvent()

    @Serializable
    data class ConflictRecorded(
        override val at: String,
        override val actor: String,
        val conflictId: String,
        val decisionA: String,
        val decisionB: String,
        val subject: String,
        val description: String,
        val resolved: Boolean = false,
        override val note: String? = null,
    ) : DomainEvent()

    @Serializable
    data class ConflictResolved(
        override val at: String,
        override val actor: String,
        val conflictId: String,
        val resolution: String,
        override val note: String? = null,
    ) : DomainEvent()

    @Serializable
    data class DiagnosticRecorded(
        override val at: String,
        override val actor: String,
        val diagnosticId: String,
        val severity: String,
        val code: String,
        val subject: String,
        val message: String,
        val directSources: List<ProvenanceRef> = emptyList(),
        override val note: String? = null,
    ) : DomainEvent()

    @Serializable
    data class ImportRecorded(
        override val at: String,
        override val actor: String,
        val packageId: String,
        val eventCount: Int,
        val contentSha: String,
        override val note: String? = null,
    ) : DomainEvent()

    @Serializable
    data class ExportRecorded(
        override val at: String,
        override val actor: String,
        val packageId: String,
        val eventCount: Int,
        val contentSha: String,
        override val note: String? = null,
    ) : DomainEvent()
}

/**
 * Envelope persisted in SQLite. Identity is content-derived from the event's exact bytes and
 * position, which is why export -> delete -> import preserves ids and ordering.
 */
@Serializable
data class StoredEvent(
    val seq: Long,
    val eventId: String,
    val prevId: String?,
    val eventType: String,
    val payload: DomainEvent,
    val sha: String,
)

object EventHashing {
    private fun material(seq: Long, eventId: String, prevId: String?, body: String): String =
        "$seq|$eventId|${prevId ?: ""}|$body"

    fun body(event: DomainEvent): String = canonicalJsonString(event)

    fun sha(seq: Long, eventId: String, prevId: String?, body: String): String =
        Hashing.sha256Hex(material(seq, eventId, prevId, body))

    fun envelope(seq: Long, prevId: String?, event: DomainEvent): StoredEvent {
        val body = body(event)
        val eventId = stableId(IdKinds.EVENT, "$seq|$body")
        val sha = sha(seq, eventId, prevId, body)
        return StoredEvent(seq, eventId, prevId, event::class.simpleName!!, event, sha)
    }

    fun verify(envelope: StoredEvent) {
        val body = body(envelope.payload)
        val expectedId = stableId(IdKinds.EVENT, "${envelope.seq}|$body")
        val expectedSha = sha(envelope.seq, expectedId, envelope.prevId, body)
        check(envelope.eventId == expectedId) { "event id mismatch at seq ${envelope.seq}" }
        check(envelope.sha == expectedSha) { "event sha mismatch at seq ${envelope.seq}" }
    }
}

fun nowIso(clock: org.chrono.timex.CClock): String = clock.now().toString()

fun Instant.asIso(): String = this.toString()
