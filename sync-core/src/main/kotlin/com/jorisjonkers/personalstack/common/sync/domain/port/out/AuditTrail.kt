package com.jorisjonkers.personalstack.common.sync.domain.port.out

import com.jorisjonkers.personalstack.common.sync.domain.SyncContext
import com.jorisjonkers.personalstack.common.sync.domain.SyncOutcome
import com.jorisjonkers.personalstack.common.sync.domain.SyncReport

/**
 * OPTIONAL-WITH-DEFAULT outbound port. The default is a safe no-op because the [SyncReport] is still
 * returned to the caller. Production scheduled/message triggers should provide a real implementation.
 *
 * Durable, structured record of a sync run. [recordOutcome] is invoked inside the per-record/per-plan
 * transaction so that the audit row commits atomically with the aggregate change it describes.
 */
interface AuditTrail {
    fun recordRunStarted(context: SyncContext<*>)

    fun recordOutcome(context: SyncContext<*>, outcome: SyncOutcome<*>)

    fun recordRunCompleted(report: SyncReport)
}
