package com.jorisjonkers.personalstack.common.sync.application.port.`in`

import com.jorisjonkers.personalstack.common.sync.domain.CorrelationId
import com.jorisjonkers.personalstack.common.sync.domain.IdempotencyKey
import com.jorisjonkers.personalstack.common.sync.domain.SyncReport
import com.jorisjonkers.personalstack.common.sync.domain.SyncTriggerSource

data class SyncEntityCommand<RID : Any>(
    val externalId: RID,
    val source: SyncTriggerSource,
    val correlationId: CorrelationId = CorrelationId.new(),
    val idempotencyKey: IdempotencyKey? = null,
    val dryRun: Boolean = false,
)

/** Inbound port: synchronize a single remote entity, identified by its external id. */
interface SyncEntityUseCase<RID : Any> {
    fun sync(command: SyncEntityCommand<RID>): SyncReport
}
