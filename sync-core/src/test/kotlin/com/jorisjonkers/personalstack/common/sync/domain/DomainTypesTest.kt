package com.jorisjonkers.personalstack.common.sync.domain

import com.jorisjonkers.personalstack.common.sync.testsupport.RemoteWidget
import com.jorisjonkers.personalstack.common.sync.testsupport.WidgetId
import com.jorisjonkers.personalstack.common.sync.testsupport.WidgetKey
import com.jorisjonkers.personalstack.common.sync.testsupport.WidgetScope
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class DomainTypesTest {
    private val instant: Instant = Instant.parse("2026-06-30T12:34:56Z")
    private val runId: RunId = RunId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
    private val correlationId: CorrelationId = CorrelationId("corr-1")
    private val syncName: SyncName = SyncName("widget")
    private val widgetId: WidgetId = WidgetId("w-1")
    private val version: VersionStamp = VersionStamp.Token("v1")

    private val neverLinked =
        SyncRegistration<WidgetId>(
            remoteId = null,
            rememberedRemoteId = null,
            lifecycle = SyncRegistrationLifecycle.NEVER_LINKED,
            changedAt = null,
            version = null,
        )

    private val linked =
        SyncRegistration(
            remoteId = widgetId,
            rememberedRemoteId = widgetId,
            lifecycle = SyncRegistrationLifecycle.LINKED,
            changedAt = Instant.EPOCH,
            version = VersionStamp.Token("old"),
        )

    @Test
    fun `a new run id and correlation id each carry a fresh value`() {
        assertThat(RunId.new().value).isNotEqualTo(RunId.new().value)
        assertThat(CorrelationId.new().value).isNotBlank().isNotEqualTo(CorrelationId.new().value)
    }

    @Test
    fun `the cursor at the beginning is empty and the cursor at the end is its own marker`() {
        assertThat(SyncCursor.Beginning).isEqualTo(SyncCursor(""))
        assertThat(SyncCursor.End).isEqualTo(SyncCursor("\u0000:end"))
    }

    @Test
    fun `a sync is not a dry run unless asked`() {
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
    }

    @Test
    fun `linking a registration makes it linked to the remote id it remembers`() {
        val got = neverLinked.link(widgetId, version, instant)

        assertThat(got.remoteId).isEqualTo(widgetId)
        assertThat(got.rememberedRemoteId).isEqualTo(widgetId)
        assertThat(got.lifecycle).isEqualTo(SyncRegistrationLifecycle.LINKED)
        assertThat(got.changedAt).isEqualTo(instant)
        assertThat(got.version).isEqualTo(version)
    }

    @Test
    fun `restoring a registration links it as linking would`() {
        assertThat(
            neverLinked.restore(widgetId, version, instant),
        ).isEqualTo(neverLinked.link(widgetId, version, instant))
    }

    @Test
    fun `relinking a registration takes the new version`() {
        assertThat(
            neverLinked.relink(widgetId, VersionStamp.Number(2), instant).version,
        ).isEqualTo(VersionStamp.Number(2))
    }

    @Test
    fun `an explicit delete forgets the remote id but remembers it with the delete's version`() {
        val signal =
            RemoteDeleteSignal.ExplicitDelete(
                remoteId = widgetId,
                deletedAt = instant,
                version = VersionStamp.Token("deleted"),
            )

        val got = linked.markRemoteDeleted(signal, instant)

        assertThat(got.remoteId).isNull()
        assertThat(got.rememberedRemoteId).isEqualTo(widgetId)
        assertThat(got.lifecycle).isEqualTo(SyncRegistrationLifecycle.REMOTELY_DELETED)
        assertThat(got.version).isEqualTo(VersionStamp.Token("deleted"))
    }

    @Test
    fun `a tombstone remembers the tombstoned id and its version`() {
        val stamp = VersionStamp.Timestamp(instant.plusSeconds(1))
        val signal =
            RemoteDeleteSignal.Tombstone(
                remoteId = WidgetId("w-2"),
                observedAt = instant.plusSeconds(1),
                version = stamp,
            )

        val got = linked.markRemoteDeleted(signal, instant.plusSeconds(1))

        assertThat(got.rememberedRemoteId).isEqualTo(WidgetId("w-2"))
        assertThat(got.version).isEqualTo(stamp)
    }

    @Test
    fun `a record missing from an authoritative list keeps the version it had`() {
        val signal =
            RemoteDeleteSignal.MissingFromAuthoritativeList(
                remoteId = WidgetId("w-3"),
                observedAt = instant.plusSeconds(2),
            )

        val got = linked.markRemoteDeleted(signal, instant.plusSeconds(2))

        assertThat(got.rememberedRemoteId).isEqualTo(WidgetId("w-3"))
        assertThat(got.version).isEqualTo(VersionStamp.Token("old"))
    }

    @Test
    fun `unlinking remembers the remote id it was linked to`() {
        val got = linked.copy(rememberedRemoteId = null).unlink(instant)

        assertThat(got.rememberedRemoteId).isEqualTo(widgetId)
        assertThat(got.lifecycle).isEqualTo(SyncRegistrationLifecycle.UNLINKED)
    }

    @Test
    fun `unlinking keeps a remote id already remembered`() {
        val remembered = linked.copy(remoteId = null, rememberedRemoteId = WidgetId("remembered"))

        assertThat(remembered.unlink(instant).rememberedRemoteId).isEqualTo(WidgetId("remembered"))
    }

    @Test
    fun `a field change is redacted unless marked plain`() {
        val field = FieldChange(path = FieldPath("name"), local = Fingerprint("old"), remote = Fingerprint("new"))

        assertThat(field.redaction).isEqualTo(Redaction.REDACTED)
    }

    @Test
    fun `a change set is empty only without field changes`() {
        val field = FieldChange(path = FieldPath("name"), local = Fingerprint("old"), remote = Fingerprint("new"))

        assertThat(ChangeSet(setOf(field)).isEmpty).isFalse()
        assertThat(ChangeSet().isEmpty).isTrue()
    }

    @Test
    fun `an upsert names its record's id and version`() {
        val remote = remoteRecord()
        val upsert = RemoteChange.Upsert(remote)

        assertThat(upsert.externalId).isEqualTo(widgetId)
        assertThat(upsert.version).isEqualTo(remote.version)
    }

    @Test
    fun `a delete names the id its signal deleted`() {
        val delete = RemoteChange.Delete(RemoteDeleteSignal.MissingFromAuthoritativeList(widgetId, instant), version)

        assertThat(delete.externalId).isEqualTo(widgetId)
    }

    private fun remoteRecord(): RemoteRecord<RemoteWidget, WidgetId, WidgetKey> =
        RemoteRecord(
            record = RemoteWidget(id = widgetId, sku = "SKU-1", name = "Widget", version = version),
            externalId = widgetId,
            keys = setOf(WidgetKey.Remote(widgetId), WidgetKey.Sku("SKU-1")),
            version = version,
        )
}
