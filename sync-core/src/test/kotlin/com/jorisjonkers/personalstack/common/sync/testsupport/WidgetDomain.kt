package com.jorisjonkers.personalstack.common.sync.testsupport

import com.jorisjonkers.personalstack.common.sync.domain.ChangeSet
import com.jorisjonkers.personalstack.common.sync.domain.FieldChange
import com.jorisjonkers.personalstack.common.sync.domain.FieldPath
import com.jorisjonkers.personalstack.common.sync.domain.Fingerprint
import com.jorisjonkers.personalstack.common.sync.domain.LocalId
import com.jorisjonkers.personalstack.common.sync.domain.LocalProjector
import com.jorisjonkers.personalstack.common.sync.domain.LocalRecord
import com.jorisjonkers.personalstack.common.sync.domain.RemoteDeleteSignal
import com.jorisjonkers.personalstack.common.sync.domain.RemoteProjector
import com.jorisjonkers.personalstack.common.sync.domain.RemoteRecord
import com.jorisjonkers.personalstack.common.sync.domain.RemoteRecordLifecycle
import com.jorisjonkers.personalstack.common.sync.domain.SyncContext
import com.jorisjonkers.personalstack.common.sync.domain.SyncDiffer
import com.jorisjonkers.personalstack.common.sync.domain.SyncMapper
import com.jorisjonkers.personalstack.common.sync.domain.SyncRegistration
import com.jorisjonkers.personalstack.common.sync.domain.SyncRegistrationLifecycle
import com.jorisjonkers.personalstack.common.sync.domain.UnlinkReason
import com.jorisjonkers.personalstack.common.sync.domain.VersionStamp
import java.time.Instant

/*
 * Canonical test domain for the sync harness. A tiny `Widget` aggregate synced from a
 * `RemoteWidget` DTO. The generic parameters used everywhere in the harness are:
 *
 *   A     = [Widget]
 *   R     = [RemoteWidget]
 *   RID   = [WidgetId]
 *   KEY   = [WidgetKey]    (sealed: [WidgetKey.Remote] hard key + [WidgetKey.Sku] natural key)
 *   SCOPE = [WidgetScope]
 */

/** Remote id of a widget (RID). */
@JvmInline
value class WidgetId(
    val value: String,
)

/** Scope partition for list/spawn sync (SCOPE). */
@JvmInline
value class WidgetScope(
    val value: String,
)

/**
 * Sealed match key (KEY). [Remote] is the hard remote-id key; [Sku] is a natural key for soft
 * matching passes (relink by SKU).
 */
sealed interface WidgetKey {
    data class Remote(
        val id: WidgetId,
    ) : WidgetKey

    data class Sku(
        val sku: String,
    ) : WidgetKey
}

/**
 * The consumer aggregate (A). [registration] carries the sync link lifecycle; [deleted] is a
 * soft-delete marker the mapper toggles so the repository can hold soft-deleted rows.
 */
data class Widget(
    val localId: LocalId?,
    val sku: String,
    val name: String,
    val scope: WidgetScope,
    val registration: SyncRegistration<WidgetId>,
    val deleted: Boolean = false,
) {
    companion object {
        /** A brand-new, never-linked widget (e.g. produced by tests before import). */
        fun neverLinked(
            localId: String,
            sku: String,
            name: String,
            scope: WidgetScope = WidgetScope("default"),
        ): Widget =
            Widget(
                localId = LocalId(localId),
                sku = sku,
                name = name,
                scope = scope,
                registration =
                    SyncRegistration(
                        remoteId = null,
                        rememberedRemoteId = null,
                        lifecycle = SyncRegistrationLifecycle.NEVER_LINKED,
                        changedAt = null,
                        version = null,
                    ),
            )

        /** A widget already linked to [remoteId]. */
        fun linked(
            localId: String,
            remoteId: WidgetId,
            sku: String,
            name: String,
            scope: WidgetScope = WidgetScope("default"),
            at: Instant = Instant.EPOCH,
        ): Widget =
            Widget(
                localId = LocalId(localId),
                sku = sku,
                name = name,
                scope = scope,
                registration =
                    SyncRegistration(
                        remoteId = remoteId,
                        rememberedRemoteId = remoteId,
                        lifecycle = SyncRegistrationLifecycle.LINKED,
                        changedAt = at,
                        version = null,
                    ),
            )

        /** A soft-deleted widget that still remembers [remoteId] (so Restore is reachable). */
        fun remotelyDeleted(
            localId: String,
            remoteId: WidgetId,
            sku: String,
            name: String,
            scope: WidgetScope = WidgetScope("default"),
            at: Instant = Instant.EPOCH,
        ): Widget =
            Widget(
                localId = LocalId(localId),
                sku = sku,
                name = name,
                scope = scope,
                deleted = true,
                registration =
                    SyncRegistration(
                        remoteId = null,
                        rememberedRemoteId = remoteId,
                        lifecycle = SyncRegistrationLifecycle.REMOTELY_DELETED,
                        changedAt = at,
                        version = null,
                    ),
            )
    }
}

/** The remote DTO (R). */
data class RemoteWidget(
    val id: WidgetId,
    val sku: String,
    val name: String,
    val lifecycle: RemoteRecordLifecycle = RemoteRecordLifecycle.ACTIVE,
    val importable: Boolean = true,
    val version: VersionStamp? = null,
    val observedAt: Instant? = null,
)

/** Pure projector: A -> LocalRecord. Match keys are the remembered/active remote id plus the SKU. */
object WidgetLocalProjector : LocalProjector<Widget, WidgetId, WidgetKey> {
    override fun project(local: Widget): LocalRecord<Widget, WidgetId, WidgetKey> {
        val rid = local.registration.remoteId ?: local.registration.rememberedRemoteId
        val keys =
            buildSet<WidgetKey> {
                rid?.let { add(WidgetKey.Remote(it)) }
                add(WidgetKey.Sku(local.sku))
            }
        return LocalRecord(
            aggregate = local,
            localId = local.localId,
            registration = local.registration,
            keys = keys,
        )
    }
}

/** Pure projector: R -> RemoteRecord. Match keys are the remote id plus the SKU. */
object WidgetRemoteProjector : RemoteProjector<RemoteWidget, WidgetId, WidgetKey> {
    override fun project(remote: RemoteWidget): RemoteRecord<RemoteWidget, WidgetId, WidgetKey> =
        RemoteRecord(
            record = remote,
            externalId = remote.id,
            keys = setOf(WidgetKey.Remote(remote.id), WidgetKey.Sku(remote.sku)),
            lifecycle = remote.lifecycle,
            importable = remote.importable,
            version = remote.version,
            observedAt = remote.observedAt,
        )
}

/** Pure differ: reports `name` changed when the two sides disagree, else an empty change set. */
object WidgetDiffer : SyncDiffer<Widget, RemoteWidget> {
    override fun diff(
        local: Widget,
        remote: RemoteWidget,
    ): ChangeSet =
        if (local.name == remote.name) {
            ChangeSet()
        } else {
            ChangeSet(
                setOf(
                    FieldChange(
                        path = FieldPath("name"),
                        local = Fingerprint(local.name),
                        remote = Fingerprint(remote.name),
                    ),
                ),
            )
        }
}

/** Anti-corruption mapper producing new [Widget] states for each executable action. */
object WidgetMapper : SyncMapper<Widget, RemoteWidget, WidgetScope> {
    override fun create(
        remote: RemoteWidget,
        context: SyncContext<WidgetScope>,
    ): Widget =
        Widget(
            localId = LocalId("local-${remote.id.value}"),
            sku = remote.sku,
            name = remote.name,
            scope = context.scope ?: WidgetScope("default"),
            registration =
                SyncRegistration(
                    remoteId = remote.id,
                    rememberedRemoteId = remote.id,
                    lifecycle = SyncRegistrationLifecycle.LINKED,
                    changedAt = context.startedAt,
                    version = remote.version,
                ),
        )

    override fun update(
        local: Widget,
        remote: RemoteWidget,
        changes: ChangeSet,
        context: SyncContext<WidgetScope>,
    ): Widget =
        local.copy(
            name = remote.name,
            sku = remote.sku,
            registration = local.registration.link(remote.id, remote.version, context.startedAt),
        )

    override fun restore(
        local: Widget,
        remote: RemoteWidget,
        changes: ChangeSet,
        context: SyncContext<WidgetScope>,
    ): Widget =
        local.copy(
            name = remote.name,
            sku = remote.sku,
            deleted = false,
            registration = local.registration.restore(remote.id, remote.version, context.startedAt),
        )

    override fun relink(
        local: Widget,
        remote: RemoteWidget,
        changes: ChangeSet,
        context: SyncContext<WidgetScope>,
    ): Widget =
        local.copy(
            name = remote.name,
            registration = local.registration.relink(remote.id, remote.version, context.startedAt),
        )

    override fun delete(
        local: Widget,
        signal: RemoteDeleteSignal<*>,
        context: SyncContext<WidgetScope>,
    ): Widget {
        @Suppress("UNCHECKED_CAST")
        val typed = signal as RemoteDeleteSignal<WidgetId>
        return local.copy(
            deleted = true,
            registration = local.registration.markRemoteDeleted(typed, context.startedAt),
        )
    }

    override fun unlink(
        local: Widget,
        reason: UnlinkReason,
        context: SyncContext<WidgetScope>,
    ): Widget = local.copy(registration = local.registration.unlink(reason, context.startedAt))
}
