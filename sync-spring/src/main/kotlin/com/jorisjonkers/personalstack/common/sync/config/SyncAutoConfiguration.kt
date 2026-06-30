package com.jorisjonkers.personalstack.common.sync.config

import com.jorisjonkers.personalstack.common.sync.application.service.SyncUseCaseFactory
import com.jorisjonkers.personalstack.common.sync.domain.BaselineSnapshot
import com.jorisjonkers.personalstack.common.sync.domain.CursorCheckpoint
import com.jorisjonkers.personalstack.common.sync.domain.IdempotencyKey
import com.jorisjonkers.personalstack.common.sync.domain.SyncContext
import com.jorisjonkers.personalstack.common.sync.domain.SyncEffect
import com.jorisjonkers.personalstack.common.sync.domain.SyncFailure
import com.jorisjonkers.personalstack.common.sync.domain.SyncName
import com.jorisjonkers.personalstack.common.sync.domain.SyncOutcome
import com.jorisjonkers.personalstack.common.sync.domain.SyncReport
import com.jorisjonkers.personalstack.common.sync.domain.SyncSubject
import com.jorisjonkers.personalstack.common.sync.domain.port.out.AuditTrail
import com.jorisjonkers.personalstack.common.sync.domain.port.out.IdempotencyClaim
import com.jorisjonkers.personalstack.common.sync.domain.port.out.IdempotencyStore
import com.jorisjonkers.personalstack.common.sync.domain.port.out.SyncCheckpointStore
import com.jorisjonkers.personalstack.common.sync.domain.port.out.SyncEffectOutbox
import com.jorisjonkers.personalstack.common.sync.domain.port.out.SyncObserver
import com.jorisjonkers.personalstack.common.sync.domain.port.out.SyncUnitOfWork
import com.jorisjonkers.personalstack.common.sync.infrastructure.persistence.SpringTransactionSyncUnitOfWork
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.transaction.support.TransactionOperations
import java.time.Duration

/**
 * Auto-configuration for the SYNC framework's generic Spring adapters.
 *
 * Stance (from the design spec, section 7):
 *  - The non-generic [SyncUseCaseFactory] is always available so consumers can
 *    build typed use cases from their `SyncDefinition` beans.
 *  - [SpringTransactionSyncUnitOfWork] is created only when a [TransactionOperations]
 *    bean exists. A no-op unit of work is never silently installed.
 *  - A no-op [LockManager] is never auto-installed either — locking is a
 *    correctness concern the consumer must wire deliberately.
 *  - Observer/audit defaults are no-op and harmless because [SyncReport] is still
 *    returned to the caller.
 *  - Idempotency, checkpoint and outbox defaults are explicit and SAFE: the
 *    idempotency no-op is only installed when `extratoast.sync.idempotency=disabled`;
 *    the checkpoint default fails loudly if spawn/backfill mode actually uses it;
 *    the effect-outbox default fails on any non-empty effect list rather than
 *    dropping effects.
 *
 * Typed `SyncDefinition` beans are NOT created here. Consumers declare one
 * `SyncDefinition` bean per resource and pass it to [SyncUseCaseFactory].
 */
@AutoConfiguration
@EnableConfigurationProperties(SyncProperties::class)
@ConditionalOnProperty(prefix = SyncProperties.PREFIX, name = ["enabled"], havingValue = "true", matchIfMissing = true)
class SyncAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    fun syncUseCaseFactory(): SyncUseCaseFactory = SyncUseCaseFactory()

    @Bean
    @ConditionalOnBean(TransactionOperations::class)
    @ConditionalOnMissingBean(SyncUnitOfWork::class)
    fun springTransactionSyncUnitOfWork(
        transactionOperations: TransactionOperations,
    ): SpringTransactionSyncUnitOfWork = SpringTransactionSyncUnitOfWork(transactionOperations)

    @Bean
    @ConditionalOnMissingBean(AuditTrail::class)
    fun noopAuditTrail(): AuditTrail = NoopAuditTrail

    @Bean
    @ConditionalOnMissingBean(SyncObserver::class)
    fun noopSyncObserver(): SyncObserver = NoopSyncObserver

    @Bean
    @ConditionalOnMissingBean(SyncEffectOutbox::class)
    fun rejectingSyncEffectOutbox(): SyncEffectOutbox = RejectingSyncEffectOutbox

    @Bean
    @ConditionalOnMissingBean(IdempotencyStore::class)
    fun idempotencyStore(properties: SyncProperties): IdempotencyStore =
        when (properties.idempotency) {
            IdempotencyMode.DISABLED -> NoopIdempotencyStore
            // NOTE: when idempotency is required but no real store is provided, we install
            // a store that fails on claim so the misconfiguration surfaces immediately
            // instead of silently allowing duplicate external-triggered syncs.
            IdempotencyMode.REQUIRED_FOR_EXTERNAL_TRIGGERS -> RequiredButUnconfiguredIdempotencyStore
        }

    @Bean
    @ConditionalOnMissingBean(SyncCheckpointStore::class)
    fun checkpointStore(): SyncCheckpointStore = UnsupportedSyncCheckpointStore

    // --- default adapter implementations -----------------------------------

    private object NoopAuditTrail : AuditTrail {
        override fun recordRunStarted(context: SyncContext<*>) = Unit

        override fun recordOutcome(
            context: SyncContext<*>,
            outcome: SyncOutcome<*>,
        ) = Unit

        override fun recordRunCompleted(report: SyncReport) = Unit
    }

    private object NoopSyncObserver : SyncObserver {
        override fun onRunStarted(context: SyncContext<*>) = Unit

        override fun onOutcome(
            context: SyncContext<*>,
            outcome: SyncOutcome<*>,
        ) = Unit

        override fun onRunCompleted(report: SyncReport) = Unit
    }

    /**
     * Accepts empty effect lists (a sync that emits no effects is valid) but fails
     * loudly on non-empty effects, so effects are never silently dropped when no
     * real outbox is configured.
     */
    private object RejectingSyncEffectOutbox : SyncEffectOutbox {
        override fun append(
            context: SyncContext<*>,
            effects: List<SyncEffect>,
        ) {
            require(effects.isEmpty()) {
                "No SyncEffectOutbox bean is configured but ${effects.size} effect(s) were emitted " +
                    "for sync '${context.syncName.value}'. Provide a real SyncEffectOutbox bean to " +
                    "persist and relay effects."
            }
        }

        override fun requestRelay(context: SyncContext<*>) = Unit
    }

    /** Explicit, opt-in no-op store used only when idempotency is disabled by property. */
    private object NoopIdempotencyStore : IdempotencyStore {
        override fun claim(
            key: IdempotencyKey,
            ttl: Duration,
            context: SyncContext<*>,
        ): IdempotencyClaim = IdempotencyClaim.Acquired

        override fun complete(
            key: IdempotencyKey,
            report: SyncReport,
        ) = Unit

        override fun fail(
            key: IdempotencyKey,
            failure: SyncFailure,
        ) = Unit
    }

    /** Fails on claim so a required-but-unconfigured idempotency setup cannot pass silently. */
    private object RequiredButUnconfiguredIdempotencyStore : IdempotencyStore {
        override fun claim(
            key: IdempotencyKey,
            ttl: Duration,
            context: SyncContext<*>,
        ): IdempotencyClaim =
            throw IllegalStateException(
                "Idempotency is REQUIRED_FOR_EXTERNAL_TRIGGERS but no IdempotencyStore bean is configured. " +
                    "Provide a real IdempotencyStore, or set ${SyncProperties.PREFIX}.idempotency=disabled.",
            )

        override fun complete(
            key: IdempotencyKey,
            report: SyncReport,
        ) = Unit

        override fun fail(
            key: IdempotencyKey,
            failure: SyncFailure,
        ) = Unit
    }

    /**
     * Default checkpoint store that fails when actually used. Entity sync that
     * never touches checkpoints works fine; spawn/backfill and MULTI_WRITER modes
     * require a real store and will fail loudly here.
     *
     * Internal so its fail-loudly methods are directly unit-testable; the bean is
     * only installed when no real SyncCheckpointStore is provided.
     */
    internal object UnsupportedSyncCheckpointStore : SyncCheckpointStore {
        override fun loadCursor(
            syncName: SyncName,
            scope: Any?,
        ): CursorCheckpoint = unsupported()

        override fun saveCursorIfCurrent(
            previous: CursorCheckpoint?,
            next: CursorCheckpoint,
        ): Boolean = unsupported()

        override fun loadBaseline(
            syncName: SyncName,
            subject: SyncSubject<*>,
        ): BaselineSnapshot = unsupported()

        override fun saveBaseline(
            syncName: SyncName,
            subject: SyncSubject<*>,
            baseline: BaselineSnapshot,
        ) = unsupported()

        private fun unsupported(): Nothing =
            throw IllegalStateException(
                "No SyncCheckpointStore bean is configured. Spawn/backfill and MULTI_WRITER sync modes " +
                    "require a real SyncCheckpointStore.",
            )
    }
}
