package com.jorisjonkers.personalstack.common.sync.domain

import java.time.Instant

/**
 * The link lifecycle between a local aggregate and its remote counterpart.
 *
 * [remoteId] is the *active* link; [rememberedRemoteId] is the last-known remote id retained
 * after an unlink or a remote delete so that restore and relink remain possible. The
 * transition methods are pure and return a new [SyncRegistration]; they do not mutate.
 *
 * @param RID the consumer's remote id type.
 */
data class SyncRegistration<RID : Any>(
    val remoteId: RID?,
    val rememberedRemoteId: RID?,
    val lifecycle: SyncRegistrationLifecycle,
    val changedAt: Instant?,
    val version: VersionStamp?,
) {
    /** Establish an active link to a remote record (fresh link or import). */
    fun link(
        remoteId: RID,
        version: VersionStamp?,
        at: Instant,
    ): SyncRegistration<RID> =
        copy(
            remoteId = remoteId,
            rememberedRemoteId = remoteId,
            lifecycle = SyncRegistrationLifecycle.LINKED,
            changedAt = at,
            version = version,
        )

    /** Bring a remotely-deleted record back to life under its previous (remembered) remote id. */
    fun restore(
        remoteId: RID,
        version: VersionStamp?,
        at: Instant,
    ): SyncRegistration<RID> =
        copy(
            remoteId = remoteId,
            rememberedRemoteId = remoteId,
            lifecycle = SyncRegistrationLifecycle.LINKED,
            changedAt = at,
            version = version,
        )

    /** Re-attach a local record to a remote record discovered by a soft (natural-key) match. */
    fun relink(
        remoteId: RID,
        version: VersionStamp?,
        at: Instant,
    ): SyncRegistration<RID> =
        copy(
            remoteId = remoteId,
            rememberedRemoteId = remoteId,
            lifecycle = SyncRegistrationLifecycle.LINKED,
            changedAt = at,
            version = version,
        )

    /** Record that the remote side reports this record as deleted; the link id is remembered. */
    fun markRemoteDeleted(
        signal: RemoteDeleteSignal<RID>,
        at: Instant,
    ): SyncRegistration<RID> =
        copy(
            remoteId = null,
            rememberedRemoteId = signal.remoteId,
            lifecycle = SyncRegistrationLifecycle.REMOTELY_DELETED,
            changedAt = at,
            version =
                (signal as? RemoteDeleteSignal.ExplicitDelete<RID>)?.version
                    ?: (signal as? RemoteDeleteSignal.Tombstone<RID>)?.version
                    ?: version,
        )

    /** Drop the active link while remembering the remote id, recording why. */
    fun unlink(at: Instant): SyncRegistration<RID> =
        copy(
            remoteId = null,
            rememberedRemoteId = rememberedRemoteId ?: remoteId,
            lifecycle = SyncRegistrationLifecycle.UNLINKED,
            changedAt = at,
            version = version,
        )

    companion object {
        /**
         * Build a registration from nullable aggregate fields, inferring the [lifecycle] unless one
         * is given explicitly. Inference order: explicit override, then remotely-deleted (a
         * [remotelyDeletedAt]), then linked (an active [remoteId]), then unlinked (only a
         * [rememberedRemoteId]), else never-linked. [rememberedRemoteId] is NOT defaulted from
         * [remoteId] — pass it explicitly to retain link history the aggregate actually stored.
         */
        fun <RID : Any> inferred(
            remoteId: RID?,
            rememberedRemoteId: RID? = null,
            remotelyDeletedAt: Instant? = null,
            changedAt: Instant? = remotelyDeletedAt,
            version: VersionStamp? = null,
            lifecycle: SyncRegistrationLifecycle? = null,
        ): SyncRegistration<RID> =
            SyncRegistration(
                remoteId = remoteId,
                rememberedRemoteId = rememberedRemoteId,
                lifecycle =
                    lifecycle ?: when {
                        remotelyDeletedAt != null -> SyncRegistrationLifecycle.REMOTELY_DELETED
                        remoteId != null -> SyncRegistrationLifecycle.LINKED
                        rememberedRemoteId != null -> SyncRegistrationLifecycle.UNLINKED
                        else -> SyncRegistrationLifecycle.NEVER_LINKED
                    },
                changedAt = changedAt,
                version = version,
            )
    }
}

/** Coarse link state of a local aggregate relative to its remote counterpart. */
enum class SyncRegistrationLifecycle {
    NEVER_LINKED,
    LINKED,
    REMOTELY_DELETED,
    UNLINKED,
}

/** How the framework learned that a remote record is gone. Drives [SyncDecision.Delete]. */
sealed interface RemoteDeleteSignal<out RID : Any> {
    val remoteId: RID

    /** The remote system explicitly reported a delete (e.g. a delete event/endpoint). */
    data class ExplicitDelete<RID : Any>(
        override val remoteId: RID,
        val deletedAt: Instant?,
        val version: VersionStamp?,
    ) : RemoteDeleteSignal<RID>

    /** A tombstone marker observed while streaming changes. */
    data class Tombstone<RID : Any>(
        override val remoteId: RID,
        val observedAt: Instant,
        val version: VersionStamp?,
    ) : RemoteDeleteSignal<RID>

    /** Inferred absence from an authoritative full-list scan (only when the source is authoritative). */
    data class MissingFromAuthoritativeList<RID : Any>(
        override val remoteId: RID,
        val observedAt: Instant,
    ) : RemoteDeleteSignal<RID>
}

/** Why a local record was unlinked from its remote counterpart. */
sealed interface UnlinkReason {
    data object ManualDetach : UnlinkReason

    data object ReplacedByRelink : UnlinkReason

    data class Policy(
        val message: String,
    ) : UnlinkReason
}
