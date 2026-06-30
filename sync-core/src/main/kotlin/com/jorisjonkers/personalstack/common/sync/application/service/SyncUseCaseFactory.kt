package com.jorisjonkers.personalstack.common.sync.application.service

import com.jorisjonkers.personalstack.common.sync.application.port.`in`.SpawnSyncUseCase
import com.jorisjonkers.personalstack.common.sync.application.port.`in`.SyncEntityUseCase
import com.jorisjonkers.personalstack.common.sync.application.port.`in`.SyncListUseCase
import com.jorisjonkers.personalstack.common.sync.domain.SyncDefinition
import com.jorisjonkers.personalstack.common.sync.domain.SyncOutcome
import com.jorisjonkers.personalstack.common.sync.domain.SyncReportStatus
import java.time.Clock

/**
 * Non-generic factory that assembles typed use-case instances from a [SyncDefinition]. A single
 * factory bean serves every resource; the consumer supplies the per-resource definition.
 *
 * Pure application layer: depends only on domain types and the application services. Constructor
 * injection only.
 */
class SyncUseCaseFactory(
    private val clock: Clock = Clock.systemUTC(),
) {
    fun <A : Any, R : Any, RID : Any, KEY : Any, SCOPE : Any> entity(
        definition: SyncDefinition<A, R, RID, KEY, SCOPE>,
    ): SyncEntityUseCase<RID> = SyncEntityService(definition, clock)

    fun <A : Any, R : Any, RID : Any, KEY : Any, SCOPE : Any> list(
        definition: SyncDefinition<A, R, RID, KEY, SCOPE>,
    ): SyncListUseCase<SCOPE> = SyncListService(definition, clock)

    fun <A : Any, R : Any, RID : Any, KEY : Any, SCOPE : Any> spawn(
        definition: SyncDefinition<A, R, RID, KEY, SCOPE>,
    ): SpawnSyncUseCase<SCOPE> = SpawnSyncService(definition, clock)
}

/**
 * Internal helper deriving a [SyncReportStatus] from a run's outcomes. Shared by the application
 * services. Not part of the public API.
 */
internal object SyncReportStatuses {
    fun of(outcomes: List<SyncOutcome<*>>): SyncReportStatus {
        if (outcomes.isEmpty()) return SyncReportStatus.SKIPPED

        val anySucceeded = outcomes.any { it is SyncOutcome.Succeeded }
        val anyFailed = outcomes.any { it is SyncOutcome.Failed }
        val anyConflict =
            outcomes.any { it is SyncOutcome.Skipped && it.action == com.jorisjonkers.personalstack.common.sync.domain.SyncAction.CONFLICT }

        return when {
            anyFailed && anySucceeded -> SyncReportStatus.PARTIAL
            anyFailed -> SyncReportStatus.FAILED
            anyConflict && !anySucceeded -> SyncReportStatus.CONFLICTED
            anySucceeded -> SyncReportStatus.SUCCEEDED
            else -> SyncReportStatus.SKIPPED
        }
    }
}
