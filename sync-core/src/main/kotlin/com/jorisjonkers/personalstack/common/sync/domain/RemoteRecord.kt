package com.jorisjonkers.personalstack.common.sync.domain

import java.time.Duration
import java.time.Instant

/**
 * The framework's projection of a remote record: the raw record plus its external id, match
 * [keys], lifecycle and import eligibility. Produced by a [RemoteProjector].
 *
 * @param R the consumer's remote record type.
 * @param RID the consumer's remote id type.
 * @param KEY the consumer's match key type.
 */
data class RemoteRecord<R : Any, RID : Any, KEY : Any>(
    val record: R,
    val externalId: RID,
    val keys: Set<KEY>,
    val lifecycle: RemoteRecordLifecycle = RemoteRecordLifecycle.ACTIVE,
    val importable: Boolean = true,
    val version: VersionStamp? = null,
    val observedAt: Instant? = null,
)

/** Whether a remote record is currently present/active or has been deleted on the remote side. */
enum class RemoteRecordLifecycle {
    ACTIVE,
    DELETED,
}

/**
 * A single change observed in a remote stream/page: either an upsert of a full record or a
 * delete carrying a [RemoteDeleteSignal]. Variance is open so pages can mix both.
 */
sealed interface RemoteChange<out R : Any, out RID : Any, out KEY : Any> {
    val externalId: RID
    val version: VersionStamp?

    data class Upsert<R : Any, RID : Any, KEY : Any>(
        val remote: RemoteRecord<R, RID, KEY>,
        override val externalId: RID = remote.externalId,
        override val version: VersionStamp? = remote.version,
    ) : RemoteChange<R, RID, KEY>

    data class Delete<RID : Any>(
        val signal: RemoteDeleteSignal<RID>,
        override val version: VersionStamp?,
        override val externalId: RID = signal.remoteId,
    ) : RemoteChange<Nothing, RID, Nothing>
}

/**
 * One page of remote changes plus paging metadata. [nextCursor] is `null` when the stream is
 * exhausted; [retryAfter] is a hint from rate-limited sources.
 */
data class RemotePage<R : Any, RID : Any, KEY : Any>(
    val changes: List<RemoteChange<R, RID, KEY>>,
    val nextCursor: SyncCursor?,
    val highWatermark: SyncCursor?,
    val retryAfter: Duration? = null,
)

/**
 * Result of a remote fetch. Crucially distinguishes [Missing] (authoritative absence, may lead
 * to delete) from [Failed] (an outage, must never become a delete) and [Partial] (some data
 * plus a failure; plan only what is present and requeue).
 */
sealed interface RemoteFetch<out T : Any> {
    data class Found<T : Any>(
        val value: T,
    ) : RemoteFetch<T>

    data class Missing(
        val reason: MissingReason,
    ) : RemoteFetch<Nothing>

    data class Failed(
        val failure: SyncFailure,
    ) : RemoteFetch<Nothing>

    data class Partial<T : Any>(
        val value: T,
        val failure: SyncFailure,
    ) : RemoteFetch<T>
}

/** Why a remote record is reported as missing; only authoritative absences can drive deletes. */
enum class MissingReason {
    NOT_FOUND,
    GONE,
    FILTERED_OUT,
    UNAUTHORIZED_AS_MISSING,
}
