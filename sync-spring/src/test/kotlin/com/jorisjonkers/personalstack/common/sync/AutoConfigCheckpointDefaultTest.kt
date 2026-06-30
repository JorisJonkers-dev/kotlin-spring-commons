package com.jorisjonkers.personalstack.common.sync

import com.jorisjonkers.personalstack.common.sync.config.SyncAutoConfiguration
import com.jorisjonkers.personalstack.common.sync.domain.BaselineSnapshot
import com.jorisjonkers.personalstack.common.sync.domain.CursorCheckpoint
import com.jorisjonkers.personalstack.common.sync.domain.RunId
import com.jorisjonkers.personalstack.common.sync.domain.SyncCursor
import com.jorisjonkers.personalstack.common.sync.domain.SyncName
import com.jorisjonkers.personalstack.common.sync.domain.SyncSubject
import com.jorisjonkers.personalstack.common.sync.domain.VersionStamp
import com.jorisjonkers.personalstack.common.sync.domain.port.out.SyncCheckpointStore
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.time.Instant

class AutoConfigCheckpointDefaultTest {
    @Test
    fun `default checkpoint store throws for every operation`() {
        runner.run { context ->
            assertThat(context).hasSingleBean(SyncCheckpointStore::class.java)
            val store = context.getBean(SyncCheckpointStore::class.java)
            val syncName = SyncName("checkpoint-default")
            val subject = SyncSubject.Remote("remote-1")
            val checkpoint =
                CursorCheckpoint(
                    syncName = syncName,
                    scope = "scope-1",
                    cursor = SyncCursor("cursor-1"),
                    highWatermark = null,
                    updatedAt = Instant.parse("2026-06-30T00:00:00Z"),
                    runId = RunId.new(),
                )
            val baseline =
                BaselineSnapshot(
                    version = VersionStamp.Token("v1"),
                    fingerprint = null,
                    capturedAt = Instant.parse("2026-06-30T00:00:00Z"),
                )

            assertThatThrownBy { store.loadCursor(syncName, "scope-1") }
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("No SyncCheckpointStore bean is configured")
            assertThatThrownBy { store.saveCursorIfCurrent(null, checkpoint) }
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("No SyncCheckpointStore bean is configured")
            assertThatThrownBy { store.loadBaseline(syncName, subject) }
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("No SyncCheckpointStore bean is configured")
            assertThatThrownBy { store.saveBaseline(syncName, subject, baseline) }
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("No SyncCheckpointStore bean is configured")
        }
    }

    @Test
    fun `default checkpoint store object fails on each operation`() {
        val store = SyncAutoConfiguration.UnsupportedSyncCheckpointStore
        val syncName = SyncName("checkpoint-direct")
        val subject = SyncSubject.Remote("remote-2")
        val checkpoint =
            CursorCheckpoint(
                syncName = syncName,
                scope = "scope-2",
                cursor = SyncCursor("cursor-2"),
                highWatermark = null,
                updatedAt = Instant.parse("2026-06-30T00:00:00Z"),
                runId = RunId.new(),
            )
        val baseline =
            BaselineSnapshot(
                version = VersionStamp.Token("v2"),
                fingerprint = null,
                capturedAt = Instant.parse("2026-06-30T00:00:00Z"),
            )

        assertThatThrownBy { store.loadCursor(syncName, "scope-2") }.isInstanceOf(IllegalStateException::class.java)
        assertThatThrownBy {
            store.saveCursorIfCurrent(
                null,
                checkpoint,
            )
        }.isInstanceOf(IllegalStateException::class.java)
        assertThatThrownBy { store.loadBaseline(syncName, subject) }.isInstanceOf(IllegalStateException::class.java)
        assertThatThrownBy {
            store.saveBaseline(
                syncName,
                subject,
                baseline,
            )
        }.isInstanceOf(IllegalStateException::class.java)
    }

    private companion object {
        val runner: ApplicationContextRunner =
            ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(SyncAutoConfiguration::class.java))
    }
}
