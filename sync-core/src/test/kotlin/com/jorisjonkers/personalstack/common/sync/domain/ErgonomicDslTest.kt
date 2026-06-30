package com.jorisjonkers.personalstack.common.sync.domain

import com.jorisjonkers.personalstack.common.sync.testsupport.RemoteWidget
import com.jorisjonkers.personalstack.common.sync.testsupport.Widget
import com.jorisjonkers.personalstack.common.sync.testsupport.WidgetHarness
import com.jorisjonkers.personalstack.common.sync.testsupport.WidgetId
import com.jorisjonkers.personalstack.common.sync.testsupport.WidgetKey
import com.jorisjonkers.personalstack.common.sync.testsupport.WidgetScope
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Covers the additive ergonomic layer: ports() bag wiring, projection() objects, the declarative
 * matching verbs on MatchingBuilder, and the localRecord/remoteRecord/syncKeys/SyncRegistration.inferred
 * helpers. The raw DSL (pass, localProjector, the constructors) is exercised elsewhere and must keep working.
 */
class ErgonomicDslTest {
    // --- ports() ---------------------------------------------------------------------------------

    @Test
    fun `ports bag wires the same ports as individual setters`() {
        val h = WidgetHarness.build()
        val viaBag =
            syncResource<Widget, RemoteWidget, WidgetId, WidgetKey, WidgetScope>("via-bag") {
                ports(h.definition.ports)
                localProjector { h.definition.localProjector.project(it) }
                remoteProjector { h.definition.remoteProjector.project(it) }
                differ { local, remote -> h.definition.differ.diff(local, remote) }
                mapper(h.definition.mapper)
                matching { remoteId(WidgetKey::Remote) }
                policies { missingRemotePolicy(h.definition.policies.missingRemotePolicy) }
            }

        assertThat(viaBag.ports).isEqualTo(h.definition.ports)
    }

    // --- projection() ----------------------------------------------------------------------------

    @Test
    fun `projection object sets both projectors`() {
        val h = WidgetHarness.build()
        val projection =
            object : SyncProjection<Widget, RemoteWidget, WidgetId, WidgetKey> {
                override fun local(local: Widget): LocalRecord<Widget, WidgetId, WidgetKey> =
                    localRecord(
                        aggregate = local,
                        localId = local.localId,
                        remoteId = local.registration.remoteId,
                        keys = syncKeys<WidgetKey>(WidgetKey.Sku(local.sku)),
                    )

                override fun remote(remote: RemoteWidget): RemoteRecord<RemoteWidget, WidgetId, WidgetKey> =
                    remoteRecord(
                        record = remote,
                        externalId = remote.id,
                        keys = syncKeys<WidgetKey>(WidgetKey.Remote(remote.id), WidgetKey.Sku(remote.sku)),
                    )
            }
        val definition =
            syncResource<Widget, RemoteWidget, WidgetId, WidgetKey, WidgetScope>("proj") {
                ports(h.definition.ports)
                projection(projection)
                differ { local, remote -> h.definition.differ.diff(local, remote) }
                mapper(h.definition.mapper)
                matching { remoteId(WidgetKey::Remote) }
                policies { missingRemotePolicy(h.definition.policies.missingRemotePolicy) }
            }

        val widget = Widget.neverLinked("l1", "SKU1", "Widget 1")
        val remote = RemoteWidget(WidgetId("r1"), "SKU1", "Widget 1")
        assertThat(definition.localProjector.project(widget)).isEqualTo(projection.local(widget))
        assertThat(definition.remoteProjector.project(remote)).isEqualTo(projection.remote(remote))
    }

    // --- matching verbs --------------------------------------------------------------------------

    @Test
    fun `matching verbs derive the expected passes and compose with raw pass`() {
        val plan =
            MatchingBuilder<Widget, RemoteWidget, WidgetId, WidgetKey>().apply {
                remoteId(WidgetKey::Remote)
                rememberedRemoteId(WidgetKey::Remote)
                natural(WidgetKey.Sku::class.java, "sku")
                natural(WidgetKey.Sku::class.java, "sku-by-class")
                pass(
                    name = "raw",
                    localKeys = { emptySet() },
                    remoteKeys = { emptySet() },
                )
            }.build()

        assertThat(plan.passes.map { it.name })
            .containsExactly("remote-id", "remembered-remote-id", "sku", "sku-by-class", "raw")
        assertThat(plan.passes.map { it.confidence }).containsExactly(
            MatchConfidence.HARD,
            MatchConfidence.REMEMBERED_REMOTE_ID,
            MatchConfidence.NATURAL_KEY,
            MatchConfidence.NATURAL_KEY,
            MatchConfidence.HARD,
        )

        val local =
            localRecord(
                aggregate = Widget.neverLinked("l1", "SKU1", "n"),
                localId = LocalId("l1"),
                remoteId = WidgetId("active"),
                rememberedRemoteId = WidgetId("remembered"),
                keys = syncKeys<WidgetKey>(WidgetKey.Sku("SKU1")),
            )
        val remote =
            remoteRecord(
                record = RemoteWidget(WidgetId("active"), "SKU1", "n"),
                externalId = WidgetId("active"),
                keys = syncKeys<WidgetKey>(WidgetKey.Remote(WidgetId("active")), WidgetKey.Sku("SKU1")),
            )

        val (remoteIdPass, rememberedPass, skuPass) = plan.passes
        assertThat(remoteIdPass.localKeys(local)).containsExactly(WidgetKey.Remote(WidgetId("active")))
        assertThat(remoteIdPass.remoteKeys(remote)).containsExactly(WidgetKey.Remote(WidgetId("active")))
        assertThat(rememberedPass.localKeys(local)).containsExactly(WidgetKey.Remote(WidgetId("remembered")))
        assertThat(rememberedPass.remoteKeys(remote)).containsExactly(WidgetKey.Remote(WidgetId("active")))
        assertThat(skuPass.localKeys(local)).containsExactly(WidgetKey.Sku("SKU1"))
        assertThat(skuPass.remoteKeys(remote)).containsExactly(WidgetKey.Sku("SKU1"))
    }

    @Test
    fun `remote and remembered id passes yield no keys when the registration ids are absent`() {
        val plan =
            MatchingBuilder<Widget, RemoteWidget, WidgetId, WidgetKey>().apply {
                remoteId(WidgetKey::Remote)
                rememberedRemoteId(WidgetKey::Remote)
            }.build()
        val unlinked =
            localRecord<Widget, WidgetId, WidgetKey>(
                aggregate = Widget.neverLinked("l1", "SKU1", "n"),
                localId = LocalId("l1"),
                remoteId = null,
            )

        assertThat(plan.passes[0].localKeys(unlinked)).isEmpty()
        assertThat(plan.passes[1].localKeys(unlinked)).isEmpty()
    }

    @Test
    fun `natural verb defaults the pass name to the key class simple name`() {
        val plan =
            MatchingBuilder<Widget, RemoteWidget, WidgetId, WidgetKey>().apply {
                natural(WidgetKey.Sku::class.java)
            }.build()

        assertThat(plan.passes.single().name).isEqualTo("Sku")
    }

    // --- syncKeys --------------------------------------------------------------------------------

    @Test
    fun `syncKeys drops nulls and collapses duplicates`() {
        val keys =
            syncKeys<WidgetKey>(null, WidgetKey.Sku("a"), WidgetKey.Sku("a"), WidgetKey.Remote(WidgetId("r")), null)
        assertThat(keys).containsExactlyInAnyOrder(WidgetKey.Sku("a"), WidgetKey.Remote(WidgetId("r")))
        assertThat(syncKeys<WidgetKey>()).isEmpty()
    }

    // --- SyncRegistration.inferred ---------------------------------------------------------------

    @Test
    fun `inferred registration covers every lifecycle branch`() {
        val deletedAt = Instant.parse("2026-06-30T00:00:00Z")
        assertThat(SyncRegistration.inferred(remoteId = null, lifecycle = SyncRegistrationLifecycle.LINKED).lifecycle)
            .isEqualTo(SyncRegistrationLifecycle.LINKED)
        assertThat(SyncRegistration.inferred(remoteId = WidgetId("r"), remotelyDeletedAt = deletedAt).lifecycle)
            .isEqualTo(SyncRegistrationLifecycle.REMOTELY_DELETED)
        assertThat(SyncRegistration.inferred(remoteId = WidgetId("r")).lifecycle)
            .isEqualTo(SyncRegistrationLifecycle.LINKED)
        assertThat(SyncRegistration.inferred(remoteId = null, rememberedRemoteId = WidgetId("r")).lifecycle)
            .isEqualTo(SyncRegistrationLifecycle.UNLINKED)
        assertThat(SyncRegistration.inferred<WidgetId>(remoteId = null).lifecycle)
            .isEqualTo(SyncRegistrationLifecycle.NEVER_LINKED)
    }

    @Test
    fun `inferred registration does not fabricate remembered id and derives changedAt`() {
        val deletedAt = Instant.parse("2026-06-30T00:00:00Z")
        val explicit = Instant.parse("2026-06-29T00:00:00Z")
        val version = VersionStamp.Number(7)

        val inferred = SyncRegistration.inferred(remoteId = WidgetId("r"))
        assertThat(inferred.rememberedRemoteId).isNull()
        assertThat(inferred.changedAt).isNull()

        assertThat(SyncRegistration.inferred(remoteId = null, remotelyDeletedAt = deletedAt).changedAt).isEqualTo(deletedAt)
        val withExplicit = SyncRegistration.inferred(remoteId = null, changedAt = explicit, version = version)
        assertThat(withExplicit.changedAt).isEqualTo(explicit)
        assertThat(withExplicit.version).isEqualTo(version)
    }

    // --- localRecord -----------------------------------------------------------------------------

    @Test
    fun `localRecord builds an inferred registration with conservative defaults`() {
        val widget = Widget.neverLinked("l1", "SKU1", "n")
        val record =
            localRecord(
                aggregate = widget,
                localId = LocalId("l1"),
                remoteId = WidgetId("r1"),
                keys = syncKeys<WidgetKey>(WidgetKey.Sku("SKU1")),
            )
        assertThat(record.aggregate).isSameAs(widget)
        assertThat(record.localId).isEqualTo(LocalId("l1"))
        assertThat(record.registration.lifecycle).isEqualTo(SyncRegistrationLifecycle.LINKED)
        assertThat(record.registration.rememberedRemoteId).isNull()
        assertThat(record.keys).containsExactly(WidgetKey.Sku("SKU1"))

        val empty = localRecord<Widget, WidgetId, WidgetKey>(aggregate = widget, localId = null, remoteId = null)
        assertThat(empty.localId).isNull()
        assertThat(empty.keys).isEmpty()
        assertThat(empty.registration.lifecycle).isEqualTo(SyncRegistrationLifecycle.NEVER_LINKED)
    }

    // --- remoteRecord ----------------------------------------------------------------------------

    @Test
    fun `remoteRecord maps deleted to lifecycle without touching importability`() {
        val dto = RemoteWidget(WidgetId("r1"), "SKU1", "n")

        val active = remoteRecord<RemoteWidget, WidgetId, WidgetKey>(record = dto, externalId = WidgetId("r1"))
        assertThat(active.lifecycle).isEqualTo(RemoteRecordLifecycle.ACTIVE)
        assertThat(active.importable).isTrue()
        assertThat(active.observedAt).isNull()
        assertThat(active.version).isNull()
        assertThat(active.keys).isEmpty()

        val observedAt = Instant.parse("2026-06-30T00:00:00Z")
        val deleted =
            remoteRecord(
                record = dto,
                externalId = WidgetId("r1"),
                keys = syncKeys<WidgetKey>(WidgetKey.Remote(WidgetId("r1"))),
                deleted = true,
                importable = false,
                version = VersionStamp.Number(3),
                observedAt = observedAt,
            )
        assertThat(deleted.lifecycle).isEqualTo(RemoteRecordLifecycle.DELETED)
        assertThat(deleted.importable).isFalse()
        assertThat(deleted.observedAt).isEqualTo(observedAt)
        assertThat(deleted.version).isEqualTo(VersionStamp.Number(3))
        assertThat(deleted.keys).containsExactly(WidgetKey.Remote(WidgetId("r1")))
    }
}
