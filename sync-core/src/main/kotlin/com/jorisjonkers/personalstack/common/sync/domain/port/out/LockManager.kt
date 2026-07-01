package com.jorisjonkers.personalstack.common.sync.domain.port.out

import com.jorisjonkers.personalstack.common.sync.domain.SyncLockKey
import java.time.Duration

/**
 * MANDATORY outbound port for production use. A no-op lock is allowed only in tests or explicit
 * single-threaded / direct-call deployments; unique constraints on the local repository remain the
 * correctness backstop. The framework never silently installs a production no-op lock.
 *
 * Runs [block] while holding the named lock. The lock MUST be released even when [block] throws.
 */
interface LockManager {
    fun <T> withLock(
        key: SyncLockKey,
        timeout: Duration,
        block: () -> T,
    ): LockResult<T>
}

sealed interface LockResult<out T> {
    data class Acquired<T>(
        val value: T,
    ) : LockResult<T>

    data object NotAcquired : LockResult<Nothing>
}
