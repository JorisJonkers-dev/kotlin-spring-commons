package com.jorisjonkers.personalstack.common.sync.application.port.`in`

import com.jorisjonkers.personalstack.common.sync.domain.CorrelationId
import com.jorisjonkers.personalstack.common.sync.domain.IdempotencyKey
import com.jorisjonkers.personalstack.common.sync.domain.SyncReport
import com.jorisjonkers.personalstack.common.sync.domain.SyncTriggerSource

data class SyncListCommand<SCOPE : Any>(
    val scope: SCOPE,
    val source: SyncTriggerSource,
    val correlationId: CorrelationId = CorrelationId.new(),
    val idempotencyKey: IdempotencyKey? = null,
    val dryRun: Boolean = false,
)

/** Inbound port: reconcile the authoritative remote list for a scope against local state. */
interface SyncListUseCase<SCOPE : Any> {
    fun sync(command: SyncListCommand<SCOPE>): SyncReport
}
