package com.jorisjonkers.personalstack.common.sync.domain

import com.jorisjonkers.personalstack.common.sync.testsupport.RemoteWidget
import com.jorisjonkers.personalstack.common.sync.testsupport.Widget
import com.jorisjonkers.personalstack.common.sync.testsupport.WidgetId
import com.jorisjonkers.personalstack.common.sync.testsupport.WidgetKey
import com.jorisjonkers.personalstack.common.sync.testsupport.WidgetScope
import java.time.Duration
import java.time.Instant
import java.util.UUID
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DomainTypesTest {
    private val instant: Instant = Instant.parse("2026-06-30T12:34:56Z")
    private val runId: RunId = RunId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
    private val correlationId: CorrelationId = CorrelationId("corr-1")
    private val syncName: SyncName = SyncName("widget")
    private val widgetId: WidgetId = WidgetId("w-1")
    private val version: VersionStamp = VersionStamp.Token("v1")

    @Test
    fun `value types expose values companions and generated object members`() {
        assertThat(SyncName("employees").value).isEqualTo("employees")
        assertThat(ExternalId("remote-1").value).isEqualTo("remote-1")
        assertThat(LocalId("local-1").value).isEqualTo("local-1")
        assertThat(ScopeId("scope-1").value).isEqualTo("scope-1")
        assertThat(NaturalKey("sku-1").value).isEqualTo("sku-1")
        assertThat(IdempotencyKey("idem-1").value).isEqualTo("idem-1")
        assertThat(SyncLockKey("sync:widget:w-1").value).isEqualTo("sync:widget:w-1")
        assertThat(FieldPath("name").value).isEqualTo("name")

        assertThat(SyncCursor.Beginning).isEqualTo(SyncCursor(""))
        assertThat(SyncCursor.End).isEqualTo(SyncCursor("\u0000:end"))
        assertThat(SyncCursor.End.toString()).contains("end")

        val generatedRunId = RunId.new()
        val generatedCorrelationId = CorrelationId.new()

        assertThat(generatedRunId.value).isNotNull()
        assertThat(generatedCorrelationId.value).isNotBlank()
        assertThat(RunId(generatedRunId.value)).isEqualTo(generatedRunId)
        assertThat(CorrelationId(generatedCorrelationId.value)).hasSameHashCodeAs(generatedCorrelationId)
    }

    @Test
    fun `sync context covers defaults components copy equality hash and toString`() {
        val context =
            SyncContext(
                runId = runId,
                syncName = syncName,
                correlationId = correlationId,
                source = SyncTriggerSource.TEST,
                scope = WidgetScope("north"),
                startedAt = instant,
            )

        assertThat(context.dryRun).isFalse()
        assertThat(context.component1()).isEqualTo(runId)
        assertThat(context.component2()).isEqualTo(syncName)
        assertThat(context.component3()).isEqualTo(correlationId)
        assertThat(context.component4()).isEqualTo(SyncTriggerSource.TEST)
        assertThat(context.component5()).isEqualTo(WidgetScope("north"))
        assertThat(context.component6()).isEqualTo(instant)
        assertThat(context.component7()).isFalse()

        val dryRun = context.copy(dryRun = true, source = SyncTriggerSource.ADMIN)

        assertThat(dryRun.dryRun).isTrue()
        assertThat(dryRun.source).isEqualTo(SyncTriggerSource.ADMIN)
        assertThat(context).isEqualTo(context.copy())
        assertThat(context).isNotEqualTo(dryRun)
        assertThat(context.hashCode()).isEqualTo(context.copy().hashCode())
        assertThat(context.toString()).contains("widget", "TEST", "dryRun=false")
        assertThat(SyncTriggerSource.entries).containsExactly(
            SyncTriggerSource.ADMIN,
            SyncTriggerSource.MESSAGE,
            SyncTriggerSource.SCHEDULE,
            SyncTriggerSource.TEST,
            SyncTriggerSource.DIRECT,
        )
    }

    @Test
    fun `sync registration lifecycle helpers update active remembered lifecycle time and versions`() {
        val original =
            SyncRegistration<WidgetId>(
                remoteId = null,
                rememberedRemoteId = null,
                lifecycle = SyncRegistrationLifecycle.NEVER_LINKED,
                changedAt = null,
                version = null,
            )
        val secondVersion = VersionStamp.Number(2)

        val linked = original.link(widgetId, version, instant)
        val restored = original.restore(widgetId, version, instant)
        val relinked = original.relink(widgetId, secondVersion, instant)

        assertThat(linked.remoteId).isEqualTo(widgetId)
        assertThat(linked.rememberedRemoteId).isEqualTo(widgetId)
        assertThat(linked.lifecycle).isEqualTo(SyncRegistrationLifecycle.LINKED)
        assertThat(linked.changedAt).isEqualTo(instant)
        assertThat(linked.version).isEqualTo(version)
        assertThat(restored).isEqualTo(linked)
        assertThat(relinked.version).isEqualTo(secondVersion)

        assertThat(original.component1()).isNull()
        assertThat(original.component2()).isNull()
        assertThat(original.component3()).isEqualTo(SyncRegistrationLifecycle.NEVER_LINKED)
        assertThat(original.component4()).isNull()
        assertThat(original.component5()).isNull()
        assertThat(original.copy(lifecycle = SyncRegistrationLifecycle.UNLINKED).lifecycle)
            .isEqualTo(SyncRegistrationLifecycle.UNLINKED)
        assertThat(original.toString()).contains("NEVER_LINKED")
        assertThat(original.hashCode()).isEqualTo(original.copy().hashCode())
        assertThat(SyncRegistrationLifecycle.entries).containsExactly(
            SyncRegistrationLifecycle.NEVER_LINKED,
            SyncRegistrationLifecycle.LINKED,
            SyncRegistrationLifecycle.REMOTELY_DELETED,
            SyncRegistrationLifecycle.UNLINKED,
        )
    }

    @Test
    fun `mark remote deleted handles explicit tombstone and inferred delete versions`() {
        val base =
            SyncRegistration(
                remoteId = widgetId,
                rememberedRemoteId = widgetId,
                lifecycle = SyncRegistrationLifecycle.LINKED,
                changedAt = Instant.EPOCH,
                version = VersionStamp.Token("old"),
            )
        val explicit =
            RemoteDeleteSignal.ExplicitDelete(
                remoteId = widgetId,
                deletedAt = instant,
                version = VersionStamp.Token("deleted"),
            )
        val tombstone =
            RemoteDeleteSignal.Tombstone(
                remoteId = WidgetId("w-2"),
                observedAt = instant.plusSeconds(1),
                version = VersionStamp.Timestamp(instant.plusSeconds(1)),
            )
        val missing =
            RemoteDeleteSignal.MissingFromAuthoritativeList(
                remoteId = WidgetId("w-3"),
                observedAt = instant.plusSeconds(2),
            )

        val explicitRegistration = base.markRemoteDeleted(explicit, instant)
        val tombstoneRegistration = base.markRemoteDeleted(tombstone, instant.plusSeconds(1))
        val missingRegistration = base.markRemoteDeleted(missing, instant.plusSeconds(2))

        assertThat(explicitRegistration.remoteId).isNull()
        assertThat(explicitRegistration.rememberedRemoteId).isEqualTo(widgetId)
        assertThat(explicitRegistration.lifecycle).isEqualTo(SyncRegistrationLifecycle.REMOTELY_DELETED)
        assertThat(explicitRegistration.version).isEqualTo(VersionStamp.Token("deleted"))
        assertThat(tombstoneRegistration.rememberedRemoteId).isEqualTo(WidgetId("w-2"))
        assertThat(tombstoneRegistration.version).isEqualTo(VersionStamp.Timestamp(instant.plusSeconds(1)))
        assertThat(missingRegistration.rememberedRemoteId).isEqualTo(WidgetId("w-3"))
        assertThat(missingRegistration.version).isEqualTo(VersionStamp.Token("old"))

        assertThat(explicit.component1()).isEqualTo(widgetId)
        assertThat(explicit.component2()).isEqualTo(instant)
        assertThat(explicit.component3()).isEqualTo(VersionStamp.Token("deleted"))
        assertThat(tombstone.component1()).isEqualTo(WidgetId("w-2"))
        assertThat(tombstone.component2()).isEqualTo(instant.plusSeconds(1))
        assertThat(tombstone.component3()).isEqualTo(VersionStamp.Timestamp(instant.plusSeconds(1)))
        assertThat(missing.component1()).isEqualTo(WidgetId("w-3"))
        assertThat(missing.component2()).isEqualTo(instant.plusSeconds(2))
        assertThat(explicit.copy()).isEqualTo(explicit)
        assertThat(tombstone.copy()).hasSameHashCodeAs(tombstone)
        assertThat(missing.copy().toString()).contains("MissingFromAuthoritativeList", "w-3")
    }

    @Test
    fun `unlink remembers ids and covers all unlink reason variants`() {
        val linked =
            SyncRegistration(
                remoteId = widgetId,
                rememberedRemoteId = null,
                lifecycle = SyncRegistrationLifecycle.LINKED,
                changedAt = Instant.EPOCH,
                version = version,
            )
        val alreadyRemembered = linked.copy(remoteId = null, rememberedRemoteId = WidgetId("remembered"))

        assertThat(linked.unlink(UnlinkReason.ManualDetach, instant).rememberedRemoteId).isEqualTo(widgetId)
        assertThat(alreadyRemembered.unlink(UnlinkReason.ReplacedByRelink, instant).rememberedRemoteId)
            .isEqualTo(WidgetId("remembered"))
        assertThat(linked.unlink(UnlinkReason.Policy("not in scope"), instant).lifecycle)
            .isEqualTo(SyncRegistrationLifecycle.UNLINKED)

        val policy = UnlinkReason.Policy("not in scope")
        assertThat(policy.component1()).isEqualTo("not in scope")
        assertThat(policy.copy()).isEqualTo(policy)
        assertThat(policy.hashCode()).isEqualTo(policy.copy().hashCode())
        assertThat(policy.toString()).contains("not in scope")
        assertThat(UnlinkReason.ManualDetach).isSameAs(UnlinkReason.ManualDetach)
        assertThat(UnlinkReason.ReplacedByRelink).isSameAs(UnlinkReason.ReplacedByRelink)
    }

    @Test
    fun `change set types cover version stamps defaults components and enums`() {
        val token = VersionStamp.Token("abc")
        val timestamp = VersionStamp.Timestamp(instant)
        val number = VersionStamp.Number(42)
        val fingerprint = Fingerprint("digest")
        val field =
            FieldChange(
                path = FieldPath("name"),
                local = Fingerprint("old"),
                remote = Fingerprint("new"),
            )
        val plain = field.copy(redaction = Redaction.PLAIN)
        val changes = ChangeSet(setOf(field))
        val emptyChanges = ChangeSet()

        assertThat(token.component1()).isEqualTo("abc")
        assertThat(timestamp.component1()).isEqualTo(instant)
        assertThat(number.component1()).isEqualTo(42L)
        assertThat(token.copy()).isEqualTo(token)
        assertThat(timestamp.copy()).hasSameHashCodeAs(timestamp)
        assertThat(number.copy().toString()).contains("42")
        assertThat(VersionStamp.Unknown).isSameAs(VersionStamp.Unknown)

        assertThat(fingerprint.component1()).isEqualTo("digest")
        assertThat(fingerprint.copy()).isEqualTo(fingerprint)
        assertThat(fingerprint.toString()).contains("digest")
        assertThat(field.redaction).isEqualTo(Redaction.REDACTED)
        assertThat(field.component1()).isEqualTo(FieldPath("name"))
        assertThat(field.component2()).isEqualTo(Fingerprint("old"))
        assertThat(field.component3()).isEqualTo(Fingerprint("new"))
        assertThat(field.component4()).isEqualTo(Redaction.REDACTED)
        assertThat(plain.redaction).isEqualTo(Redaction.PLAIN)
        assertThat(field).isNotEqualTo(plain)
        assertThat(field.hashCode()).isEqualTo(field.copy().hashCode())
        assertThat(field.toString()).contains("name", "REDACTED")

        assertThat(changes.isEmpty).isFalse()
        assertThat(emptyChanges.isEmpty).isTrue()
        assertThat(changes.component1()).containsExactly(field)
        assertThat(changes.copy()).isEqualTo(changes)
        assertThat(changes.toString()).contains("FieldChange")
        assertThat(Redaction.entries).containsExactly(Redaction.PLAIN, Redaction.REDACTED)
    }

    @Test
    fun `checkpoint and baseline snapshots expose all data class members`() {
        val checkpoint =
            CursorCheckpoint(
                syncName = syncName,
                scope = WidgetScope("north"),
                cursor = SyncCursor("cursor-1"),
                highWatermark = SyncCursor("hw-1"),
                updatedAt = instant,
                runId = runId,
            )
        val baseline =
            BaselineSnapshot(
                version = version,
                fingerprint = Fingerprint("digest"),
                capturedAt = instant,
            )

        assertThat(checkpoint.component1()).isEqualTo(syncName)
        assertThat(checkpoint.component2()).isEqualTo(WidgetScope("north"))
        assertThat(checkpoint.component3()).isEqualTo(SyncCursor("cursor-1"))
        assertThat(checkpoint.component4()).isEqualTo(SyncCursor("hw-1"))
        assertThat(checkpoint.component5()).isEqualTo(instant)
        assertThat(checkpoint.component6()).isEqualTo(runId)
        assertThat(checkpoint.copy(cursor = SyncCursor.End).cursor).isEqualTo(SyncCursor.End)
        assertThat(checkpoint.hashCode()).isEqualTo(checkpoint.copy().hashCode())
        assertThat(checkpoint.toString()).contains("cursor-1", "hw-1")

        assertThat(baseline.component1()).isEqualTo(version)
        assertThat(baseline.component2()).isEqualTo(Fingerprint("digest"))
        assertThat(baseline.component3()).isEqualTo(instant)
        assertThat(baseline.copy(fingerprint = null).fingerprint).isNull()
        assertThat(baseline).isEqualTo(baseline.copy())
        assertThat(baseline.toString()).contains("digest")
    }

    @Test
    fun `local and remote records cover defaults components copy equality hash and toString`() {
        val widget = Widget.linked("local-1", widgetId, "SKU-1", "Widget")
        val registration = widget.registration
        val local =
            LocalRecord(
                aggregate = widget,
                localId = LocalId("local-1"),
                registration = registration,
                keys = setOf(WidgetKey.Remote(widgetId), WidgetKey.Sku("SKU-1")),
            )
        val remoteWidget = RemoteWidget(id = widgetId, sku = "SKU-1", name = "Widget")
        val remote =
            RemoteRecord(
                record = remoteWidget,
                externalId = widgetId,
                keys = setOf(WidgetKey.Remote(widgetId), WidgetKey.Sku("SKU-1")),
            )
        val deleted =
            remote.copy(
                lifecycle = RemoteRecordLifecycle.DELETED,
                importable = false,
                version = version,
                observedAt = instant,
            )

        assertThat(local.component1()).isEqualTo(widget)
        assertThat(local.component2()).isEqualTo(LocalId("local-1"))
        assertThat(local.component3()).isEqualTo(registration)
        assertThat(local.component4()).containsExactlyInAnyOrder(WidgetKey.Remote(widgetId), WidgetKey.Sku("SKU-1"))
        assertThat(local.copy()).isEqualTo(local)
        assertThat(local.hashCode()).isEqualTo(local.copy().hashCode())
        assertThat(local.toString()).contains("local-1", "SKU-1")

        assertThat(remote.lifecycle).isEqualTo(RemoteRecordLifecycle.ACTIVE)
        assertThat(remote.importable).isTrue()
        assertThat(remote.version).isNull()
        assertThat(remote.observedAt).isNull()
        assertThat(remote.component1()).isEqualTo(remoteWidget)
        assertThat(remote.component2()).isEqualTo(widgetId)
        assertThat(remote.component3()).containsExactlyInAnyOrder(WidgetKey.Remote(widgetId), WidgetKey.Sku("SKU-1"))
        assertThat(remote.component4()).isEqualTo(RemoteRecordLifecycle.ACTIVE)
        assertThat(remote.component5()).isTrue()
        assertThat(remote.component6()).isNull()
        assertThat(remote.component7()).isNull()
        assertThat(deleted.lifecycle).isEqualTo(RemoteRecordLifecycle.DELETED)
        assertThat(deleted.importable).isFalse()
        assertThat(remote).isNotEqualTo(deleted)
        assertThat(remote.hashCode()).isEqualTo(remote.copy().hashCode())
        assertThat(remote.toString()).contains("ACTIVE", "w-1")
        assertThat(RemoteRecordLifecycle.entries).containsExactly(
            RemoteRecordLifecycle.ACTIVE,
            RemoteRecordLifecycle.DELETED,
        )
    }

    @Test
    fun `remote changes pages and fetch variants expose defaults and generated members`() {
        val remote = remoteRecord()
        val upsert = RemoteChange.Upsert(remote)
        val deleteSignal = RemoteDeleteSignal.MissingFromAuthoritativeList(widgetId, instant)
        val delete = RemoteChange.Delete(deleteSignal, version)
        val page =
            RemotePage(
                changes = listOf(upsert, delete),
                nextCursor = SyncCursor("next"),
                highWatermark = SyncCursor("high"),
            )
        val retryingPage = page.copy(retryAfter = Duration.ofSeconds(3))
        val failure = SyncFailure(SyncFailureKind.REMOTE_TIMEOUT, "timeout", retryable = true)
        val found = RemoteFetch.Found(page)
        val foundRecord = RemoteFetch.Found(remote)
        val missing = RemoteFetch.Missing(MissingReason.GONE)
        val failed = RemoteFetch.Failed(failure)
        val partial = RemoteFetch.Partial(page, failure)

        assertThat(upsert.externalId).isEqualTo(widgetId)
        assertThat(upsert.version).isEqualTo(remote.version)
        assertThat(upsert.component1()).isEqualTo(remote)
        assertThat(upsert.component2()).isEqualTo(widgetId)
        assertThat(upsert.component3()).isEqualTo(remote.version)
        assertThat(upsert.copy()).isEqualTo(upsert)
        assertThat(upsert.hashCode()).isEqualTo(upsert.copy().hashCode())
        assertThat(upsert.toString()).contains("Upsert", "w-1")

        assertThat(delete.externalId).isEqualTo(widgetId)
        assertThat(delete.component1()).isEqualTo(deleteSignal)
        assertThat(delete.component2()).isEqualTo(version)
        assertThat(delete.component3()).isEqualTo(widgetId)
        assertThat(delete.copy()).isEqualTo(delete)
        assertThat(delete.toString()).contains("Delete", "w-1")

        assertThat(page.retryAfter).isNull()
        assertThat(page.component1()).containsExactly(upsert, delete)
        assertThat(page.component2()).isEqualTo(SyncCursor("next"))
        assertThat(page.component3()).isEqualTo(SyncCursor("high"))
        assertThat(page.component4()).isNull()
        assertThat(retryingPage.retryAfter).isEqualTo(Duration.ofSeconds(3))
        assertThat(page).isNotEqualTo(retryingPage)
        assertThat(page.hashCode()).isEqualTo(page.copy().hashCode())
        assertThat(page.toString()).contains("next", "high")

        assertThat(found.component1()).isEqualTo(page)
        assertThat(found.copy()).isEqualTo(found)
        assertThat(found.toString()).contains("Found")
        assertThat(foundRecord.component1()).isEqualTo(remote)
        assertThat(missing.component1()).isEqualTo(MissingReason.GONE)
        assertThat(missing.copy()).hasSameHashCodeAs(missing)
        assertThat(missing.toString()).contains("GONE")
        assertThat(failed.component1()).isEqualTo(failure)
        assertThat(failed.copy()).isEqualTo(failed)
        assertThat(failed.toString()).contains("Failed")
        assertThat(partial.component1()).isEqualTo(page)
        assertThat(partial.component2()).isEqualTo(failure)
        assertThat(partial.copy()).isEqualTo(partial)
        assertThat(partial.toString()).contains("Partial")
        assertThat(MissingReason.entries).containsExactly(
            MissingReason.NOT_FOUND,
            MissingReason.GONE,
            MissingReason.FILTERED_OUT,
            MissingReason.UNAUTHORIZED_AS_MISSING,
        )
    }

    @Test
    fun `sync effects cover all variants defaults and generated members`() {
        val enqueue =
            SyncEffect.EnqueueEntitySync(
                key = "enqueue-widget-w-1",
                syncName = syncName,
                externalId = widgetId,
            )
        val delayed = enqueue.copy(notBefore = instant)
        val publish =
            SyncEffect.PublishEvent(
                key = "publish-1",
                type = "widget.changed",
                payloadRef = "payloads/1",
            )
        val recalculate =
            SyncEffect.Recalculate(
                key = "recalculate-1",
                subject = SyncSubject.Pair(LocalId("local-1"), widgetId),
                reason = "name changed",
            )

        assertThat(enqueue.notBefore).isNull()
        assertThat(enqueue.component1()).isEqualTo("enqueue-widget-w-1")
        assertThat(enqueue.component2()).isEqualTo(syncName)
        assertThat(enqueue.component3()).isEqualTo(widgetId)
        assertThat(enqueue.component4()).isNull()
        assertThat(delayed.notBefore).isEqualTo(instant)
        assertThat(enqueue).isNotEqualTo(delayed)
        assertThat(enqueue.hashCode()).isEqualTo(enqueue.copy().hashCode())
        assertThat(enqueue.toString()).contains("enqueue-widget-w-1")

        assertThat(publish.component1()).isEqualTo("publish-1")
        assertThat(publish.component2()).isEqualTo("widget.changed")
        assertThat(publish.component3()).isEqualTo("payloads/1")
        assertThat(publish.copy()).isEqualTo(publish)
        assertThat(publish.toString()).contains("widget.changed")

        assertThat(recalculate.component1()).isEqualTo("recalculate-1")
        assertThat(recalculate.component2()).isEqualTo(SyncSubject.Pair(LocalId("local-1"), widgetId))
        assertThat(recalculate.component3()).isEqualTo("name changed")
        assertThat(recalculate.copy()).hasSameHashCodeAs(recalculate)
        assertThat(recalculate.toString()).contains("name changed")
    }

    @Test
    fun `sync failure covers defaults components copy equality hash toString and every kind`() {
        val rateLimited =
            SyncFailure(
                kind = SyncFailureKind.REMOTE_RATE_LIMITED,
                message = "slow down",
                retryable = true,
                retryAfter = Duration.ofSeconds(30),
                causeClass = "RemoteRateLimitException",
            )
        val unknown =
            SyncFailure(
                kind = SyncFailureKind.UNKNOWN,
                message = "unexpected",
                retryable = false,
            )

        assertThat(rateLimited.component1()).isEqualTo(SyncFailureKind.REMOTE_RATE_LIMITED)
        assertThat(rateLimited.component2()).isEqualTo("slow down")
        assertThat(rateLimited.component3()).isTrue()
        assertThat(rateLimited.component4()).isEqualTo(Duration.ofSeconds(30))
        assertThat(rateLimited.component5()).isEqualTo("RemoteRateLimitException")
        assertThat(rateLimited.copy(message = "still slow").message).isEqualTo("still slow")
        assertThat(rateLimited).isEqualTo(rateLimited.copy())
        assertThat(rateLimited.hashCode()).isEqualTo(rateLimited.copy().hashCode())
        assertThat(rateLimited.toString()).contains("REMOTE_RATE_LIMITED", "slow down")
        assertThat(unknown.retryAfter).isNull()
        assertThat(unknown.causeClass).isNull()
        assertThat(unknown).isNotEqualTo(rateLimited)
        assertThat(SyncFailureKind.entries).containsExactly(
            SyncFailureKind.REMOTE_UNAVAILABLE,
            SyncFailureKind.REMOTE_TIMEOUT,
            SyncFailureKind.REMOTE_RATE_LIMITED,
            SyncFailureKind.REMOTE_PARTIAL,
            SyncFailureKind.CIRCUIT_OPEN,
            SyncFailureKind.LOCK_UNAVAILABLE,
            SyncFailureKind.DUPLICATE_LOCAL_REMOTE_ID,
            SyncFailureKind.DUPLICATE_REMOTE_ID,
            SyncFailureKind.AMBIGUOUS_MATCH,
            SyncFailureKind.LINK_MISMATCH,
            SyncFailureKind.VERSION_CONFLICT,
            SyncFailureKind.MAPPING_REJECTED,
            SyncFailureKind.LOCAL_VALIDATION_FAILED,
            SyncFailureKind.CHECKPOINT_CONFLICT,
            SyncFailureKind.IDEMPOTENCY_IN_PROGRESS,
            SyncFailureKind.UNKNOWN,
        )
    }

    private fun remoteRecord(
        id: WidgetId = widgetId,
        recordVersion: VersionStamp? = version,
    ): RemoteRecord<RemoteWidget, WidgetId, WidgetKey> =
        RemoteRecord(
            record = RemoteWidget(id = id, sku = "SKU-1", name = "Widget", version = recordVersion),
            externalId = id,
            keys = setOf(WidgetKey.Remote(id), WidgetKey.Sku("SKU-1")),
            version = recordVersion,
        )
}
