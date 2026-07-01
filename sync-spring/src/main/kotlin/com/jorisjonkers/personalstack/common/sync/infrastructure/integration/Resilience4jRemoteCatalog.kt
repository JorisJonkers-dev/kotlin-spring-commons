package com.jorisjonkers.personalstack.common.sync.infrastructure.integration

import com.jorisjonkers.personalstack.common.sync.domain.RemoteFetch
import com.jorisjonkers.personalstack.common.sync.domain.RemotePage
import com.jorisjonkers.personalstack.common.sync.domain.RemoteRecord
import com.jorisjonkers.personalstack.common.sync.domain.SyncContext
import com.jorisjonkers.personalstack.common.sync.domain.SyncCursor
import com.jorisjonkers.personalstack.common.sync.domain.SyncFailure
import com.jorisjonkers.personalstack.common.sync.domain.SyncFailureKind
import com.jorisjonkers.personalstack.common.sync.domain.port.out.RemoteCatalog
import io.github.resilience4j.circuitbreaker.CallNotPermittedException
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.retry.Retry
import java.util.concurrent.TimeoutException

/**
 * Decorating [RemoteCatalog] that adds resilience4j retry and circuit-breaker
 * behaviour around an arbitrary delegate catalog. This is the ONLY place in the
 * framework where resilience libraries are used; the domain and application
 * layers see nothing but the [RemoteFetch] algebra.
 *
 * Contract:
 *  - Each delegate call is wrapped with the configured [retry] and
 *    [circuitBreaker] (either may be `null` to disable that concern).
 *  - Expected remote failures (timeout, circuit open, rate limited, generic
 *    outage) are converted into [RemoteFetch.Failed] and never thrown to the
 *    application.
 *  - A delegate result of [RemoteFetch.Missing] or [RemoteFetch.Partial] is
 *    passed through unchanged — resilience never reinterprets absence as failure
 *    or vice versa.
 *  - [RemoteFetch.Failed] returned by the delegate is treated as a thrown
 *    transient failure for the purpose of retry/circuit accounting, then
 *    surfaced as [RemoteFetch.Failed]. This keeps the circuit breaker honest
 *    about delegates that report failures as data rather than exceptions.
 */
class Resilience4jRemoteCatalog<R : Any, RID : Any, KEY : Any, SCOPE : Any>(
    private val delegate: RemoteCatalog<R, RID, KEY, SCOPE>,
    private val circuitBreaker: CircuitBreaker?,
    private val retry: Retry?,
) : RemoteCatalog<R, RID, KEY, SCOPE> {
    override fun fetchOne(
        remoteId: RID,
        context: SyncContext<SCOPE>,
    ): RemoteFetch<RemoteRecord<R, RID, KEY>> = guarded { delegate.fetchOne(remoteId, context) }

    override fun fetchForScope(
        scope: SCOPE,
        context: SyncContext<SCOPE>,
    ): RemoteFetch<RemotePage<R, RID, KEY>> = guarded { delegate.fetchForScope(scope, context) }

    override fun fetchPage(
        scope: SCOPE?,
        cursor: SyncCursor?,
        pageSize: Int,
        context: SyncContext<SCOPE>,
    ): RemoteFetch<RemotePage<R, RID, KEY>> = guarded { delegate.fetchPage(scope, cursor, pageSize, context) }

    /**
     * Runs [call] through retry then circuit breaker, translating any thrown
     * exception or delegate-reported [RemoteFetch.Failed] into a
     * [RemoteFetch.Failed]. The thrown [RetryableRemoteException] is used purely
     * so resilience4j records a delegate-reported failure as a failed call.
     *
     * Delegate transports are intentionally opaque, so every non-retry carrier exception
     * is converted into the RemoteFetch failure algebra at this adapter boundary.
     */
    private fun <T : Any> guarded(call: () -> RemoteFetch<T>): RemoteFetch<T> =
        runCatching {
            decorate(call).invoke()
        }.getOrElse { ex ->
            when (ex) {
                is RetryableRemoteException -> ex.fetch
                else -> RemoteFetch.Failed(toFailure(ex))
            }
        }

    private fun <T : Any> decorate(call: () -> RemoteFetch<T>): () -> RemoteFetch<T> {
        val unwrapping: () -> RemoteFetch<T> = {
            val result = call()
            if (result is RemoteFetch.Failed) {
                // Promote to an exception so retry/circuit count it as a failed call,
                // preserving the original failure for re-surfacing.
                throw RetryableRemoteException(result)
            }
            result
        }
        var supplier: () -> RemoteFetch<T> = unwrapping
        retry?.let { r -> supplier = Retry.decorateSupplier(r, supplier)::get }
        circuitBreaker?.let { cb -> supplier = CircuitBreaker.decorateSupplier(cb, supplier)::get }
        return supplier
    }

    private fun toFailure(ex: Throwable): SyncFailure {
        val kind =
            when (ex) {
                is CallNotPermittedException -> SyncFailureKind.CIRCUIT_OPEN
                is TimeoutException -> SyncFailureKind.REMOTE_TIMEOUT
                else ->
                    if (isRateLimited(ex)) {
                        SyncFailureKind.REMOTE_RATE_LIMITED
                    } else {
                        SyncFailureKind.REMOTE_UNAVAILABLE
                    }
            }
        // All resilience-translated failures are transient and safe to retry later.
        return SyncFailure(
            kind = kind,
            message = ex.message ?: ex.javaClass.simpleName,
            retryable = true,
            causeClass = ex.javaClass.name,
        )
    }

    // NOTE: rate-limit detection is best-effort by simple class/message heuristic,
    // since the delegate transport (HTTP client, gRPC, etc.) is unknown here.
    private fun isRateLimited(ex: Throwable): Boolean {
        val name = ex.javaClass.simpleName.lowercase()
        val message = ex.message?.lowercase().orEmpty()
        return "ratelimit" in name ||
            "toomanyrequests" in name ||
            "429" in message ||
            "rate limit" in message
    }

    /** Internal carrier so a delegate-reported failure participates in retry/circuit accounting. */
    private class RetryableRemoteException(
        val fetch: RemoteFetch.Failed,
    ) : RuntimeException(fetch.failure.message)
}
