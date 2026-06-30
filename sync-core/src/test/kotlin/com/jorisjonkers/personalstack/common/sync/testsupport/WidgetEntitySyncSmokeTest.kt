package com.jorisjonkers.personalstack.common.sync.testsupport

import com.jorisjonkers.personalstack.common.sync.application.port.`in`.SyncEntityCommand
import com.jorisjonkers.personalstack.common.sync.application.service.SyncUseCaseFactory
import com.jorisjonkers.personalstack.common.sync.domain.RemoteRecord
import com.jorisjonkers.personalstack.common.sync.domain.SyncAction
import com.jorisjonkers.personalstack.common.sync.domain.SyncOutcome
import com.jorisjonkers.personalstack.common.sync.domain.SyncReportStatus
import com.jorisjonkers.personalstack.common.sync.domain.SyncRegistrationLifecycle
import com.jorisjonkers.personalstack.common.sync.domain.SyncTriggerSource
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * End-to-end smoke test for the harness: an IMPORT of a brand-new remote widget through
 * [SyncUseCaseFactory] + [SyncEntityService]. Proves the harness compiles and runs against the real
 * application services and that all nine in-memory ports cooperate.
 */
class WidgetEntitySyncSmokeTest {
    @Test
    fun `imports a new remote widget end-to-end`() {
        val harness = WidgetHarness.build()
        val factory = SyncUseCaseFactory(harness.clock)
        val useCase = factory.entity(harness.definition)

        val remoteId = WidgetId("w-1")
        harness.remoteCatalog.foundOne(
            remoteId,
            RemoteRecord(
                record = RemoteWidget(id = remoteId, sku = "SKU-1", name = "Sprocket"),
                externalId = remoteId,
                keys = setOf(WidgetKey.Remote(remoteId), WidgetKey.Sku("SKU-1")),
            ),
        )

        val report =
            useCase.sync(
                SyncEntityCommand(
                    externalId = remoteId,
                    source = SyncTriggerSource.TEST,
                ),
            )

        // Report shape.
        assertThat(report.status).isEqualTo(SyncReportStatus.SUCCEEDED)
        assertThat(report.outcomes).hasSize(1)
        val outcome = report.outcomes.single()
        assertThat(outcome).isInstanceOf(SyncOutcome.Succeeded::class.java)
        assertThat(outcome.action).isEqualTo(SyncAction.IMPORT)

        // Repository now holds the imported widget, linked to the remote id.
        assertThat(harness.repository.rows).hasSize(1)
        val stored = harness.repository.rows.single()
        assertThat(stored.sku).isEqualTo("SKU-1")
        assertThat(stored.name).isEqualTo("Sprocket")
        assertThat(stored.registration.remoteId).isEqualTo(remoteId)
        assertThat(stored.registration.lifecycle).isEqualTo(SyncRegistrationLifecycle.LINKED)

        // Ports were exercised.
        assertThat(harness.unitOfWork.transactionCount).isEqualTo(1)
        assertThat(harness.remoteCatalog.fetchOneCalls).containsExactly(remoteId)
        assertThat(harness.lockManager.requested).hasSize(1)
        assertThat(harness.observer.runStarted).hasSize(1)
        assertThat(harness.observer.runCompleted).hasSize(1)
        assertThat(harness.auditTrail.runCompleted).hasSize(1)
        // Import saves a baseline (it carries a remote record).
        assertThat(harness.checkpointStore.savedBaselines).hasSize(1)
    }
}
