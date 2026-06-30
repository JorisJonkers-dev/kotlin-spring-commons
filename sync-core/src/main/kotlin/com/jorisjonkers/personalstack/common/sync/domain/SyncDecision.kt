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
 * @param A aggregate type, @param R remote record type, @param RID remote id type.
 */
sealed interface SyncDecision<out A : Any, out R : Any, RID : Any> {
    val action: SyncAction
    val subject: SyncSubject<RID>
    val reason: SyncReason
    val effects: List<SyncEffect>
    val executable: Boolean

    data class Import<R : Any, RID : Any, KEY : Any>(
        val remote: RemoteRecord<R, RID, KEY>,
        override val effects: List<SyncEffect> = emptyList(),
        override val action: SyncAction = SyncAction.IMPORT,
        override val subject: SyncSubject<RID> = SyncSubject.Remote(remote.externalId),
        override val reason: SyncReason = SyncReason.RemoteOnly,
        override val executable: Boolean = true,
    ) : SyncDecision<Nothing, R, RID>

    data class Update<A : Any, R : Any, RID : Any, KEY : Any>(
        val local: LocalRecord<A, RID, KEY>,
        val remote: RemoteRecord<R, RID, KEY>,
        val changes: ChangeSet,
        override val effects: List<SyncEffect> = emptyList(),
        override val action: SyncAction = SyncAction.UPDATE,
        override val subject: SyncSubject<RID> = SyncSubject.Pair(local.localId, remote.externalId),
        override val reason: SyncReason = SyncReason.RemoteChanged,
        override val executable: Boolean = true,
    ) : SyncDecision<A, R, RID>

    data class Equal<A : Any, R : Any, RID : Any, KEY : Any>(
        val local: LocalRecord<A, RID, KEY>,
        val remote: RemoteRecord<R, RID, KEY>,
        override val action: SyncAction = SyncAction.EQUAL,
        override val subject: SyncSubject<RID> = SyncSubject.Pair(local.localId, remote.externalId),
        override val reason: SyncReason = SyncReason.AlreadyEqual,
        override val effects: List<SyncEffect> = emptyList(),
        override val executable: Boolean = false,
    ) : SyncDecision<A, R, RID>

    data class Delete<A : Any, RID : Any, KEY : Any>(
        val local: LocalRecord<A, RID, KEY>,
        val signal: RemoteDeleteSignal<RID>,
        override val effects: List<SyncEffect> = emptyList(),
        override val action: SyncAction = SyncAction.DELETE,
        override val subject: SyncSubject<RID> = SyncSubject.Pair(local.localId, signal.remoteId),
        override val reason: SyncReason = SyncReason.RemoteDeleted,
        override val executable: Boolean = true,
    ) : SyncDecision<A, Nothing, RID>

    data class Unlink<A : Any, RID : Any, KEY : Any>(
        val local: LocalRecord<A, RID, KEY>,
        val unlinkReason: UnlinkReason,
        override val effects: List<SyncEffect> = emptyList(),
        override val action: SyncAction = SyncAction.UNLINK,
        override val subject: SyncSubject<RID> =
            local.registration.rememberedRemoteId
                ?.let { SyncSubject.Pair(local.localId, it) }
                ?: local.localId?.let { SyncSubject.Local(it) }
                ?: SyncSubject.Unknown,
        override val reason: SyncReason = SyncReason.LocalOnlyUnlinked,
        override val executable: Boolean = true,
    ) : SyncDecision<A, Nothing, RID>

    data class Restore<A : Any, R : Any, RID : Any, KEY : Any>(
        val local: LocalRecord<A, RID, KEY>,
        val remote: RemoteRecord<R, RID, KEY>,
        val changes: ChangeSet,
        override val effects: List<SyncEffect> = emptyList(),
        override val action: SyncAction = SyncAction.RESTORE,
        override val subject: SyncSubject<RID> = SyncSubject.Pair(local.localId, remote.externalId),
        override val reason: SyncReason = SyncReason.RestoreLinkedRecord,
        override val executable: Boolean = true,
    ) : SyncDecision<A, R, RID>

    data class Relink<A : Any, R : Any, RID : Any, KEY : Any>(
        val local: LocalRecord<A, RID, KEY>,
        val remote: RemoteRecord<R, RID, KEY>,
        val changes: ChangeSet,
        override val effects: List<SyncEffect> = emptyList(),
        override val action: SyncAction = SyncAction.RELINK,
        override val subject: SyncSubject<RID> = SyncSubject.Pair(local.localId, remote.externalId),
        override val reason: SyncReason = SyncReason.RelinkByNaturalKey,
        override val executable: Boolean = true,
    ) : SyncDecision<A, R, RID>

    data class Ignore<RID : Any>(
        override val subject: SyncSubject<RID>,
        override val reason: SyncReason,
        override val action: SyncAction = SyncAction.IGNORE,
        override val effects: List<SyncEffect> = emptyList(),
        override val executable: Boolean = false,
    ) : SyncDecision<Nothing, Nothing, RID>

    data class Conflict<RID : Any>(
        val conflict: SyncConflict<*, *, RID, *>,
        override val action: SyncAction = SyncAction.CONFLICT,
        override val subject: SyncSubject<RID> = conflict.subject,
        override val reason: SyncReason = SyncReason.Conflict(conflict),
        override val effects: List<SyncEffect> = emptyList(),
        override val executable: Boolean = false,
    ) : SyncDecision<Nothing, Nothing, RID>

    data class Retry<RID : Any>(
        override val subject: SyncSubject<RID>,
        val delay: Duration?,
        val failure: SyncFailure,
        override val action: SyncAction = SyncAction.RETRY,
        override val reason: SyncReason = SyncReason.RetryLater(failure),
        override val effects: List<SyncEffect> = emptyList(),
        override val executable: Boolean = false,
    ) : SyncDecision<Nothing, Nothing, RID>
}
