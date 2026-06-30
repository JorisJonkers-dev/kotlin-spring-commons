package com.jorisjonkers.personalstack.common.sync.domain

import java.time.Instant

/**
 * A pure description of a side effect to be carried out *after* the aggregate transaction
 * commits. Effects are persisted atomically via the outbox and relayed after commit; the domain
 * never performs the effect itself. [key] de-duplicates effects across replays.
 */
sealed interface SyncEffect {
    val key: String

    /** Request a follow-up entity sync for another remote id (e.g. fan-out from a spawn/list run). */
    data class EnqueueEntitySync<RID : Any>(
        override val key: String,
        val syncName: SyncName,
        val externalId: RID,
        val notBefore: Instant? = null,
    ) : SyncEffect

    /** Publish a domain/integration event; the payload is referenced indirectly to keep effects light. */
    data class PublishEvent(
        override val key: String,
        val type: String,
        val payloadRef: String,
    ) : SyncEffect

    /** Request recomputation of a derived value for a subject. */
    data class Recalculate(
        override val key: String,
        val subject: SyncSubject<*>,
        val reason: String,
    ) : SyncEffect
}
