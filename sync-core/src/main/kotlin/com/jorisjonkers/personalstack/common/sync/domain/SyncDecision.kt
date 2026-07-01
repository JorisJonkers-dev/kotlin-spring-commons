package com.jorisjonkers.personalstack.common.sync.domain

import java.time.Duration

/**
 * Stable vocabulary of sync actions. This is the human/observability-facing name; the typed
 * payload lives in the [SyncDecision] hierarchy.
 */
enum class SyncAction {
    IMPORT,
    UPDATE,
    EQUAL,
    DELETE,
    UNLINK,
    RESTORE,
    RELINK,
    IGNORE,
    CONFLICT,
    RETRY,
}

/**
 * What a decision is about, plus a [stableKey] usable for ordering, audit and de-duplication.
 */
sealed interface SyncSubject<out RID : Any> {
    val stableKey: String

    data class Remote<RID : Any>(
        val externalId: RID,
        override val stableKey: String = "remote:$externalId",
    ) : SyncSubject<RID>

    data class Local(
        val localId: LocalId,
        override val stableKey: String = "local:${localId.value}",
    ) : SyncSubject<Nothing>

    data class Pair<RID : Any>(
        val localId: LocalId?,
        val externalId: RID,
        override val stableKey: String = "pair:${localId?.value ?: "unknown"}:$externalId",
    ) : SyncSubject<RID>

    data class Scope(
        val scopeId: ScopeId,
        override val stableKey: String = "scope:${scopeId.value}",
    ) : SyncSubject<Nothing>

    data object Unknown : SyncSubject<Nothing> {
        override val stableKey: String = "unknown"
    }
}

/** The rationale behind a decision, for audit and to make CONFLICT/RETRY self-describing. */
sealed interface SyncReason {
    data object RemoteOnly : SyncReason

    data object RemoteChanged : SyncReason

    data object AlreadyEqual : SyncReason

    data object RemoteDeleted : SyncReason

    data object LocalOnlyUnlinked : SyncReason

    data object NotImportable : SyncReason

    data object RestoreLinkedRecord : SyncReason

    data object RelinkByNaturalKey : SyncReason

    data class Conflict(
        val conflict: SyncConflict<*, *, *, *>,
    ) : SyncReason

    data class RetryLater(
        val failure: SyncFailure,
    ) : SyncReason

    data class Policy(
        val message: String,
    ) : SyncReason
}

/**
 * The typed outcome of reconciling one local/remote situation. Each variant carries exactly the
 * data the executor needs. [executable] is `false` for terminal/no-op decisions (Equal, Ignore,
 * Conflict, Retry) so the application never tries to apply them.
 *
 * @param A aggregate type, @param R remote record type, @param RID remote id type,
 * @param KEY match key type.
 */
sealed interface SyncDecision<A : Any, R : Any, RID : Any, KEY : Any> {
    val action: SyncAction
    val subject: SyncSubject<RID>
    val reason: SyncReason
    val effects: List<SyncEffect>
    val executable: Boolean
    val localRecord: LocalRecord<A, RID, KEY>?
        get() = null
    val remoteRecord: RemoteRecord<R, RID, KEY>?
        get() = null
    val changes: ChangeSet?
        get() = null
    val deleteSignal: RemoteDeleteSignal<RID>?
        get() = null
    val unlinkReason: UnlinkReason?
        get() = null
    val failure: SyncFailure?
        get() = null

    data class Import<A : Any, R : Any, RID : Any, KEY : Any>(
        val remote: RemoteRecord<R, RID, KEY>,
        override val effects: List<SyncEffect> = emptyList(),
        override val action: SyncAction = SyncAction.IMPORT,
        override val subject: SyncSubject<RID> = SyncSubject.Remote(remote.externalId),
        override val reason: SyncReason = SyncReason.RemoteOnly,
        override val executable: Boolean = true,
    ) : SyncDecision<A, R, RID, KEY> {
        override val remoteRecord: RemoteRecord<R, RID, KEY>
            get() = remote
    }

    data class Update<A : Any, R : Any, RID : Any, KEY : Any>(
        val local: LocalRecord<A, RID, KEY>,
        val remote: RemoteRecord<R, RID, KEY>,
        override val changes: ChangeSet,
        override val effects: List<SyncEffect> = emptyList(),
        override val action: SyncAction = SyncAction.UPDATE,
        override val subject: SyncSubject<RID> = SyncSubject.Pair(local.localId, remote.externalId),
        override val reason: SyncReason = SyncReason.RemoteChanged,
        override val executable: Boolean = true,
    ) : SyncDecision<A, R, RID, KEY> {
        override val localRecord: LocalRecord<A, RID, KEY>
            get() = local
        override val remoteRecord: RemoteRecord<R, RID, KEY>
            get() = remote
    }

    data class Equal<A : Any, R : Any, RID : Any, KEY : Any>(
        val local: LocalRecord<A, RID, KEY>,
        val remote: RemoteRecord<R, RID, KEY>,
        override val action: SyncAction = SyncAction.EQUAL,
        override val subject: SyncSubject<RID> = SyncSubject.Pair(local.localId, remote.externalId),
        override val reason: SyncReason = SyncReason.AlreadyEqual,
        override val effects: List<SyncEffect> = emptyList(),
        override val executable: Boolean = false,
    ) : SyncDecision<A, R, RID, KEY> {
        override val localRecord: LocalRecord<A, RID, KEY>
            get() = local
        override val remoteRecord: RemoteRecord<R, RID, KEY>
            get() = remote
    }

    data class Delete<A : Any, R : Any, RID : Any, KEY : Any>(
        val local: LocalRecord<A, RID, KEY>,
        val signal: RemoteDeleteSignal<RID>,
        override val effects: List<SyncEffect> = emptyList(),
        override val action: SyncAction = SyncAction.DELETE,
        override val subject: SyncSubject<RID> = SyncSubject.Pair(local.localId, signal.remoteId),
        override val reason: SyncReason = SyncReason.RemoteDeleted,
        override val executable: Boolean = true,
    ) : SyncDecision<A, R, RID, KEY> {
        override val localRecord: LocalRecord<A, RID, KEY>
            get() = local
        override val deleteSignal: RemoteDeleteSignal<RID>
            get() = signal
    }

    data class Unlink<A : Any, R : Any, RID : Any, KEY : Any>(
        val local: LocalRecord<A, RID, KEY>,
        override val unlinkReason: UnlinkReason,
        override val effects: List<SyncEffect> = emptyList(),
        override val action: SyncAction = SyncAction.UNLINK,
        override val subject: SyncSubject<RID> =
            local.registration.rememberedRemoteId
                ?.let { SyncSubject.Pair(local.localId, it) }
                ?: local.localId?.let { SyncSubject.Local(it) }
                ?: SyncSubject.Unknown,
        override val reason: SyncReason = SyncReason.LocalOnlyUnlinked,
        override val executable: Boolean = true,
    ) : SyncDecision<A, R, RID, KEY> {
        override val localRecord: LocalRecord<A, RID, KEY>
            get() = local
    }

    data class Restore<A : Any, R : Any, RID : Any, KEY : Any>(
        val local: LocalRecord<A, RID, KEY>,
        val remote: RemoteRecord<R, RID, KEY>,
        override val changes: ChangeSet,
        override val effects: List<SyncEffect> = emptyList(),
        override val action: SyncAction = SyncAction.RESTORE,
        override val subject: SyncSubject<RID> = SyncSubject.Pair(local.localId, remote.externalId),
        override val reason: SyncReason = SyncReason.RestoreLinkedRecord,
        override val executable: Boolean = true,
    ) : SyncDecision<A, R, RID, KEY> {
        override val localRecord: LocalRecord<A, RID, KEY>
            get() = local
        override val remoteRecord: RemoteRecord<R, RID, KEY>
            get() = remote
    }

    data class Relink<A : Any, R : Any, RID : Any, KEY : Any>(
        val local: LocalRecord<A, RID, KEY>,
        val remote: RemoteRecord<R, RID, KEY>,
        override val changes: ChangeSet,
        override val effects: List<SyncEffect> = emptyList(),
        override val action: SyncAction = SyncAction.RELINK,
        override val subject: SyncSubject<RID> = SyncSubject.Pair(local.localId, remote.externalId),
        override val reason: SyncReason = SyncReason.RelinkByNaturalKey,
        override val executable: Boolean = true,
    ) : SyncDecision<A, R, RID, KEY> {
        override val localRecord: LocalRecord<A, RID, KEY>
            get() = local
        override val remoteRecord: RemoteRecord<R, RID, KEY>
            get() = remote
    }

    data class Ignore<A : Any, R : Any, RID : Any, KEY : Any>(
        override val subject: SyncSubject<RID>,
        override val reason: SyncReason,
        override val action: SyncAction = SyncAction.IGNORE,
        override val effects: List<SyncEffect> = emptyList(),
        override val executable: Boolean = false,
    ) : SyncDecision<A, R, RID, KEY>

    data class Conflict<A : Any, R : Any, RID : Any, KEY : Any>(
        val conflict: SyncConflict<*, *, RID, *>,
        override val action: SyncAction = SyncAction.CONFLICT,
        override val subject: SyncSubject<RID> = conflict.subject,
        override val reason: SyncReason = SyncReason.Conflict(conflict),
        override val effects: List<SyncEffect> = emptyList(),
        override val executable: Boolean = false,
    ) : SyncDecision<A, R, RID, KEY>

    data class Retry<A : Any, R : Any, RID : Any, KEY : Any>(
        override val subject: SyncSubject<RID>,
        val delay: Duration?,
        override val failure: SyncFailure,
        override val action: SyncAction = SyncAction.RETRY,
        override val reason: SyncReason = SyncReason.RetryLater(failure),
        override val effects: List<SyncEffect> = emptyList(),
        override val executable: Boolean = false,
    ) : SyncDecision<A, R, RID, KEY>
}
