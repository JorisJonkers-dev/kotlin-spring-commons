package com.jorisjonkers.personalstack.common.sync.application.port.`in`

import com.jorisjonkers.personalstack.common.sync.domain.CorrelationId
import com.jorisjonkers.personalstack.common.sync.domain.IdempotencyKey
import com.jorisjonkers.personalstack.common.sync.domain.SyncCursor
import com.jorisjonkers.personalstack.common.sync.domain.SyncReport
import com.jorisjonkers.personalstack.common.sync.domain.SyncTriggerSource

data class SpawnSyncCommand<SCOPE : Any>(
    val scope: SCOPE?,
    val cursor: SyncCursor? = null,
    val pageSize: Int? = null,
    val fullSync: Boolean = false,
    val source: SyncTriggerSource,
    val correlationId: CorrelationId = CorrelationId.new(),
    val idempotencyKey: IdempotencyKey? = null,
    val dryRun: Boolean = false,
)

/** Inbound port: paged backfill / incremental discovery that fans out per-entity sync work. */
interface SpawnSyncUseCase<SCOPE : Any> {
    fun spawn(command: SpawnSyncCommand<SCOPE>): SyncReport
}
