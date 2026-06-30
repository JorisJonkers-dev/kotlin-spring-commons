package com.jorisjonkers.personalstack.common.sync

import com.jorisjonkers.personalstack.common.sync.application.service.SyncUseCaseFactory
import com.jorisjonkers.personalstack.common.sync.config.IdempotencyMode
import com.jorisjonkers.personalstack.common.sync.config.SyncAutoConfiguration
import com.jorisjonkers.personalstack.common.sync.config.SyncProperties
import com.jorisjonkers.personalstack.common.sync.domain.BaselineSnapshot
import com.jorisjonkers.personalstack.common.sync.domain.CorrelationId
import com.jorisjonkers.personalstack.common.sync.domain.CursorCheckpoint
import com.jorisjonkers.personalstack.common.sync.domain.Fingerprint
import com.jorisjonkers.personalstack.common.sync.domain.IdempotencyKey
import com.jorisjonkers.personalstack.common.sync.domain.MissingReason
import com.jorisjonkers.personalstack.common.sync.domain.RemoteChange
import com.jorisjonkers.personalstack.common.sync.domain.RemoteFetch
import com.jorisjonkers.personalstack.common.sync.domain.RemotePage
import com.jorisjonkers.personalstack.common.sync.domain.RemoteRecord
import com.jorisjonkers.personalstack.common.sync.domain.RunId
import com.jorisjonkers.personalstack.common.sync.domain.SyncAction
import com.jorisjonkers.personalstack.common.sync.domain.SyncContext
import com.jorisjonkers.personalstack.common.sync.domain.SyncCursor
import com.jorisjonkers.personalstack.common.sync.domain.SyncEffect
import com.jorisjonkers.personalstack.common.sync.domain.SyncFailure
import com.jorisjonkers.personalstack.common.sync.domain.SyncFailureKind
import com.jorisjonkers.personalstack.common.sync.domain.SyncName
import com.jorisjonkers.personalstack.common.sync.domain.SyncOutcome
import com.jorisjonkers.personalstack.common.sync.domain.SyncReport
import com.jorisjonkers.personalstack.common.sync.domain.SyncReportStatus
import com.jorisjonkers.personalstack.common.sync.domain.SyncSubject
import com.jorisjonkers.personalstack.common.sync.domain.SyncTriggerSource
import com.jorisjonkers.personalstack.common.sync.domain.VersionStamp
import com.jorisjonkers.personalstack.common.sync.domain.port.out.AuditTrail
import com.jorisjonkers.personalstack.common.sync.domain.port.out.IdempotencyClaim
import com.jorisjonkers.personalstack.common.sync.domain.port.out.IdempotencyStore
import com.jorisjonkers.personalstack.common.sync.domain.port.out.RemoteCatalog
import com.jorisjonkers.personalstack.common.sync.domain.port.out.SyncCheckpointStore
import com.jorisjonkers.personalstack.common.sync.domain.port.out.SyncEffectOutbox
import com.jorisjonkers.personalstack.common.sync.domain.port.out.SyncObserver
import com.jorisjonkers.personalstack.common.sync.domain.port.out.SyncUnitOfWork
import com.jorisjonkers.personalstack.common.sync.infrastructure.integration.Resilience4jRemoteCatalog
import com.jorisjonkers.personalstack.common.sync.infrastructure.messaging.AfterCommitSyncEffectRelay
import com.jorisjonkers.personalstack.common.sync.infrastructure.persistence.SpringTransactionSyncUnitOfWork
import io.github.resilience4j.circuitbreaker.CallNotPermittedException
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.retry.Retry
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.AbstractPlatformTransactionManager
import org.springframework.transaction.support.DefaultTransactionStatus
import org.springframework.transaction.support.TransactionOperations
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeoutException
import java.util.function.Supplier

class SyncSpringTest {
    @Nested
    inner class SyncPropertiesTest {
        @Test
        fun `defaults require explicit opt out for unsafe adapters`() {
            val properties = SyncProperties()

            assertThat(properties.enabled).isTrue()
            assertThat(properties.lockTimeout).isEqualTo(Duration.ofSeconds(10))
            assertThat(properties.pageSize).isEqualTo(500)
            assertThat(properties.idempotency).isEqualTo(IdempotencyMode.REQUIRED_FOR_EXTERNAL_TRIGGERS)
            assertThat(properties.resilience.enabled).isTrue()
        }

        @Test
        fun `binds sync properties from spring environment`() {
            runner
                .withPropertyValues(
                    "extratoast.sync.lock-timeout=45s",
                    "extratoast.sync.page-size=123",
                    "extratoast.sync.idempotency=disabled",
                    "extratoast.sync.resilience.enabled=false",
                ).run { context ->
                    assertThat(context).hasNotFailed()

                    val properties = context.getBean(SyncProperties::class.java)
                    assertThat(properties.enabled).isTrue()
                    assertThat(properties.lockTimeout).isEqualTo(Duration.ofSeconds(45))
                    assertThat(properties.pageSize).isEqualTo(123)
                    assertThat(properties.idempotency).isEqualTo(IdempotencyMode.DISABLED)
                    assertThat(properties.resilience.enabled).isFalse()
                }
        }
    }

    @Nested
    inner class SyncAutoConfigurationTest {
        @Test
        fun `registers default beans when enabled and transaction operations are present`() {
            runner
                .withBean(TransactionOperations::class.java, Supplier { TransactionOperations.withoutTransaction() })
                .run { context ->
                    assertThat(context).hasNotFailed()
                    assertThat(context).hasSingleBean(SyncProperties::class.java)
                    assertThat(context).hasSingleBean(SyncUseCaseFactory::class.java)
                    assertThat(context).hasSingleBean(SyncUnitOfWork::class.java)
                    assertThat(context.getBean(SyncUnitOfWork::class.java))
                        .isInstanceOf(SpringTransactionSyncUnitOfWork::class.java)
                    assertThat(context).hasSingleBean(AuditTrail::class.java)
                    assertThat(context).hasSingleBean(SyncObserver::class.java)
                    assertThat(context).hasSingleBean(SyncEffectOutbox::class.java)
                    assertThat(context).hasSingleBean(IdempotencyStore::class.java)
                    assertThat(context).hasSingleBean(SyncCheckpointStore::class.java)
                }
        }

        @Test
        fun `does not register sync beans when disabled`() {
            runner
                .withPropertyValues("extratoast.sync.enabled=false")
                .withBean(TransactionOperations::class.java, Supplier { TransactionOperations.withoutTransaction() })
                .run { context ->
                    assertThat(context).hasNotFailed()
                    assertThat(context).doesNotHaveBean(SyncUseCaseFactory::class.java)
                    assertThat(context).doesNotHaveBean(SyncUnitOfWork::class.java)
                    assertThat(context).doesNotHaveBean(AuditTrail::class.java)
                    assertThat(context).doesNotHaveBean(SyncObserver::class.java)
                    assertThat(context).doesNotHaveBean(SyncEffectOutbox::class.java)
                    assertThat(context).doesNotHaveBean(IdempotencyStore::class.java)
                    assertThat(context).doesNotHaveBean(SyncCheckpointStore::class.java)
                }
        }

        @Test
        fun `does not create spring unit of work without transaction operations`() {
            runner.run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).doesNotHaveBean(SyncUnitOfWork::class.java)
            }
        }

        @Test
        fun `backs off when user beans are present`() {
            val useCaseFactory = SyncUseCaseFactory()
            val unitOfWork = mockk<SyncUnitOfWork>()
            val auditTrail = mockk<AuditTrail>()
            val observer = mockk<SyncObserver>()
            val outbox = mockk<SyncEffectOutbox>()
            val idempotencyStore = mockk<IdempotencyStore>()
            val checkpointStore = mockk<SyncCheckpointStore>()

            runner
                .withBean(SyncUseCaseFactory::class.java, Supplier { useCaseFactory })
                .withBean(SyncUnitOfWork::class.java, Supplier { unitOfWork })
                .withBean(AuditTrail::class.java, Supplier { auditTrail })
                .withBean(SyncObserver::class.java, Supplier { observer })
                .withBean(SyncEffectOutbox::class.java, Supplier { outbox })
                .withBean(IdempotencyStore::class.java, Supplier { idempotencyStore })
                .withBean(SyncCheckpointStore::class.java, Supplier { checkpointStore })
                .withBean(TransactionOperations::class.java, Supplier { TransactionOperations.withoutTransaction() })
                .run { context ->
                    assertThat(context).hasNotFailed()
                    assertThat(context.getBean(SyncUseCaseFactory::class.java)).isSameAs(useCaseFactory)
                    assertThat(context.getBean(SyncUnitOfWork::class.java)).isSameAs(unitOfWork)
                    assertThat(context.getBean(AuditTrail::class.java)).isSameAs(auditTrail)
                    assertThat(context.getBean(SyncObserver::class.java)).isSameAs(observer)
                    assertThat(context.getBean(SyncEffectOutbox::class.java)).isSameAs(outbox)
                    assertThat(context.getBean(IdempotencyStore::class.java)).isSameAs(idempotencyStore)
                    assertThat(context.getBean(SyncCheckpointStore::class.java)).isSameAs(checkpointStore)
                }
        }

        @Test
        fun `noop audit trail and observer accept all callbacks`() {
            runner.run { context ->
                val auditTrail = context.getBean(AuditTrail::class.java)
                val observer = context.getBean(SyncObserver::class.java)
                val outcome =
                    SyncOutcome.Succeeded(
                        subject = SyncSubject.Remote("remote-1"),
                        action = SyncAction.IMPORT,
                        duration = Duration.ofMillis(5),
                    )
                val report = report(outcomes = listOf(outcome))

                auditTrail.recordRunStarted(syncContext)
                auditTrail.recordOutcome(syncContext, outcome)
                auditTrail.recordRunCompleted(report)
                observer.onRunStarted(syncContext)
                observer.onOutcome(syncContext, outcome)
                observer.onRunCompleted(report)
            }
        }

        @Test
        fun `default outbox accepts empty effects and rejects non empty effects`() {
            runner.run { context ->
                val outbox = context.getBean(SyncEffectOutbox::class.java)

                outbox.append(syncContext, emptyList())
                outbox.requestRelay(syncContext)

                assertThatThrownBy {
                    outbox.append(
                        syncContext,
                        listOf(
                            SyncEffect.PublishEvent(
                                key = "effect-1",
                                type = "employee.changed",
                                payloadRef = "payload-1",
                            ),
                        ),
                    )
                }.isInstanceOf(IllegalArgumentException::class.java)
                    .hasMessageContaining("No SyncEffectOutbox bean is configured")
                    .hasMessageContaining("employee")
            }
        }

        @Test
        fun `required idempotency store fails on claim but completion callbacks are noops`() {
            runner.run { context ->
                val store = context.getBean(IdempotencyStore::class.java)
                val failure = failure(SyncFailureKind.REMOTE_UNAVAILABLE)
                val report = report()

                assertThatThrownBy {
                    store.claim(IdempotencyKey("idempotency-1"), Duration.ofMinutes(1), syncContext)
                }.isInstanceOf(IllegalStateException::class.java)
                    .hasMessageContaining("Idempotency is REQUIRED_FOR_EXTERNAL_TRIGGERS")

                store.complete(IdempotencyKey("idempotency-1"), report)
                store.fail(IdempotencyKey("idempotency-1"), failure)
            }
        }

        @Test
        fun `disabled idempotency store acquires and ignores terminal callbacks`() {
            runner
                .withPropertyValues("extratoast.sync.idempotency=disabled")
                .run { context ->
                    val store = context.getBean(IdempotencyStore::class.java)
                    val key = IdempotencyKey("idempotency-1")

                    assertThat(store.claim(key, Duration.ofMinutes(1), syncContext))
                        .isEqualTo(IdempotencyClaim.Acquired)

                    store.complete(key, report())
                    store.fail(key, failure(SyncFailureKind.REMOTE_UNAVAILABLE))
                }
        }

        @Test
        fun `default checkpoint store fails loudly for every operation`() {
            runner.run { context ->
                val store = context.getBean(SyncCheckpointStore::class.java)
                val checkpoint =
                    CursorCheckpoint(
                        syncName = SyncName("employee"),
                        scope = "scope-1",
                        cursor = SyncCursor("cursor-1"),
                        highWatermark = SyncCursor("high-watermark-1"),
                        updatedAt = now,
                        runId = syncContext.runId,
                    )
                val baseline =
                    BaselineSnapshot(
                        version = VersionStamp.Number(1),
                        fingerprint = Fingerprint("fingerprint-1"),
                        capturedAt = now,
                    )

                assertUnsupportedCheckpoint { store.loadCursor(SyncName("employee"), "scope-1") }
                assertUnsupportedCheckpoint { store.saveCursorIfCurrent(null, checkpoint) }
                assertUnsupportedCheckpoint { store.loadBaseline(SyncName("employee"), SyncSubject.Remote("remote-1")) }
                assertUnsupportedCheckpoint {
                    store.saveBaseline(SyncName("employee"), SyncSubject.Remote("remote-1"), baseline)
                }
            }
        }
    }

    @Nested
    inner class Resilience4jRemoteCatalogTest {
        @Test
        fun `fetchOne decorates delegate with retry and circuit breaker and passes found through`() {
            val delegate = mockCatalog()
            val remote = RemoteRecord(record = "Ada", externalId = "remote-1", keys = setOf("ada"))
            every { delegate.fetchOne("remote-1", syncContext) } returns RemoteFetch.Found(remote)

            val catalog =
                Resilience4jRemoteCatalog(
                    delegate = delegate,
                    circuitBreaker = CircuitBreaker.ofDefaults("employee"),
                    retry = Retry.ofDefaults("employee"),
                )

            assertThat(catalog.fetchOne("remote-1", syncContext)).isEqualTo(RemoteFetch.Found(remote))
        }

        @Test
        fun `fetchForScope passes missing through unchanged`() {
            val delegate = mockCatalog()
            val missing = RemoteFetch.Missing(MissingReason.NOT_FOUND)
            every { delegate.fetchForScope("scope-1", syncContext) } returns missing

            assertThat(catalog(delegate).fetchForScope("scope-1", syncContext)).isSameAs(missing)
        }

        @Test
        fun `fetchPage passes partial through unchanged`() {
            val delegate = mockCatalog()
            val page =
                RemotePage<String, String, String>(
                    changes = listOf(RemoteChange.Upsert(RemoteRecord("Ada", "remote-1", setOf("ada")))),
                    nextCursor = SyncCursor("cursor-2"),
                    highWatermark = SyncCursor("high-watermark-1"),
                )
            val partial = RemoteFetch.Partial(page, failure(SyncFailureKind.REMOTE_PARTIAL))
            every { delegate.fetchPage("scope-1", SyncCursor("cursor-1"), 10, syncContext) } returns partial

            assertThat(catalog(delegate).fetchPage("scope-1", SyncCursor("cursor-1"), 10, syncContext))
                .isSameAs(partial)
        }

        @Test
        fun `delegate failed result is surfaced as the original failed fetch`() {
            val delegate = mockCatalog()
            val failed = RemoteFetch.Failed(failure(SyncFailureKind.REMOTE_UNAVAILABLE, "delegate failed"))
            every { delegate.fetchOne("remote-1", syncContext) } returns failed

            assertThat(catalog(delegate).fetchOne("remote-1", syncContext)).isSameAs(failed)
        }

        @Test
        fun `call not permitted maps to circuit open failure`() {
            val delegate = mockCatalog()
            val circuitBreaker = CircuitBreaker.ofDefaults("employee")
            every { delegate.fetchOne("remote-1", syncContext) } throws
                CallNotPermittedException.createCallNotPermittedException(circuitBreaker)

            assertFailure(
                fetch = catalog(delegate).fetchOne("remote-1", syncContext),
                kind = SyncFailureKind.CIRCUIT_OPEN,
                causeClass = CallNotPermittedException::class.java.name,
            )
        }

        @Test
        fun `timeout maps to remote timeout failure`() {
            val delegate = mockCatalog()
            every { delegate.fetchOne("remote-1", syncContext) } throws TimeoutException("remote timed out")

            assertFailure(
                fetch = catalog(delegate).fetchOne("remote-1", syncContext),
                kind = SyncFailureKind.REMOTE_TIMEOUT,
                causeClass = TimeoutException::class.java.name,
                message = "remote timed out",
            )
        }

        @Test
        fun `rate limit heuristic checks exception class and message`() {
            assertRateLimited(RateLimitExceededException("from class name"))
            assertRateLimited(TooManyRequestsException("from class name"))
            assertRateLimited(RuntimeException("HTTP 429"))
            assertRateLimited(RuntimeException("rate limit exceeded"))
        }

        @Test
        fun `unknown exception maps to remote unavailable and uses class name when message is absent`() {
            val delegate = mockCatalog()
            every { delegate.fetchOne("remote-1", syncContext) } throws RuntimeException()

            assertFailure(
                fetch = catalog(delegate).fetchOne("remote-1", syncContext),
                kind = SyncFailureKind.REMOTE_UNAVAILABLE,
                causeClass = RuntimeException::class.java.name,
                message = "RuntimeException",
            )
        }
    }

    @Nested
    inner class AfterCommitSyncEffectRelayTest {
        @Test
        fun `requestRelay delegates to the durable outbox`() {
            val outbox = mockk<SyncEffectOutbox>()
            every { outbox.requestRelay(syncContext) } just runs

            AfterCommitSyncEffectRelay(outbox).requestRelay(syncContext)

            verify(exactly = 1) { outbox.requestRelay(syncContext) }
        }
    }

    @Nested
    inner class SpringTransactionSyncUnitOfWorkTest {
        @Test
        fun `transaction returns block value and runs registered action after commit`() {
            val unitOfWork = SpringTransactionSyncUnitOfWork(TransactionTemplate(TestTransactionManager()))
            val events = mutableListOf<String>()

            val result =
                unitOfWork.transaction {
                    afterCommit { events += "after-commit" }
                    events += "inside-transaction"
                    "result"
                }

            assertThat(result).isEqualTo("result")
            assertThat(events).containsExactly("inside-transaction", "after-commit")
        }

        @Test
        fun `afterCommit fails fast when transaction synchronization is not active`() {
            val unitOfWork = SpringTransactionSyncUnitOfWork(TransactionOperations.withoutTransaction())

            assertThatThrownBy {
                unitOfWork.transaction {
                    afterCommit { error("must not run") }
                }
            }.isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("afterCommit requested without an active transaction synchronization")
        }
    }

    private fun assertRateLimited(ex: RuntimeException) {
        val delegate = mockCatalog()
        every { delegate.fetchOne("remote-1", syncContext) } throws ex

        assertFailure(
            fetch = catalog(delegate).fetchOne("remote-1", syncContext),
            kind = SyncFailureKind.REMOTE_RATE_LIMITED,
            causeClass = ex.javaClass.name,
            message = ex.message,
        )
    }

    private fun assertFailure(
        fetch: RemoteFetch<*>,
        kind: SyncFailureKind,
        causeClass: String,
        message: String? = null,
    ) {
        assertThat(fetch).isInstanceOf(RemoteFetch.Failed::class.java)
        val failure = (fetch as RemoteFetch.Failed).failure
        assertThat(failure.kind).isEqualTo(kind)
        assertThat(failure.retryable).isTrue()
        assertThat(failure.causeClass).isEqualTo(causeClass)
        message?.let { assertThat(failure.message).isEqualTo(it) }
    }

    private fun assertUnsupportedCheckpoint(block: () -> Any?) {
        assertThatThrownBy { block() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("No SyncCheckpointStore bean is configured")
    }

    private fun mockCatalog(): RemoteCatalog<String, String, String, String> = mockk()

    private fun catalog(
        delegate: RemoteCatalog<String, String, String, String>,
    ): Resilience4jRemoteCatalog<String, String, String, String> =
        Resilience4jRemoteCatalog(delegate = delegate, circuitBreaker = null, retry = null)

    private fun failure(
        kind: SyncFailureKind,
        message: String = kind.name,
    ): SyncFailure =
        SyncFailure(
            kind = kind,
            message = message,
            retryable = true,
        )

    private fun report(outcomes: List<SyncOutcome<*>> = emptyList()): SyncReport =
        SyncReport(
            context = syncContext,
            status = SyncReportStatus.SUCCEEDED,
            startedAt = now,
            completedAt = now.plusMillis(10),
            outcomes = outcomes,
        )

    private class RateLimitExceededException(
        message: String,
    ) : RuntimeException(message)

    private class TooManyRequestsException(
        message: String,
    ) : RuntimeException(message)

    private class TestTransactionManager : AbstractPlatformTransactionManager() {
        init {
            setTransactionSynchronization(SYNCHRONIZATION_ALWAYS)
        }

        override fun doGetTransaction(): Any = Any()

        override fun doBegin(
            transaction: Any,
            definition: TransactionDefinition,
        ) = Unit

        override fun doCommit(status: DefaultTransactionStatus) = Unit

        override fun doRollback(status: DefaultTransactionStatus) = Unit
    }

    private companion object {
        val now: Instant = Instant.parse("2025-01-01T00:00:00Z")
        val syncContext: SyncContext<String> =
            SyncContext(
                runId = RunId.new(),
                syncName = SyncName("employee"),
                correlationId = CorrelationId("correlation-1"),
                source = SyncTriggerSource.TEST,
                scope = "scope-1",
                startedAt = now,
            )

        val runner: ApplicationContextRunner =
            ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(SyncAutoConfiguration::class.java))
    }
}
