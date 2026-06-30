package com.jorisjonkers.personalstack.common.sync.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * Configuration for the SYNC framework's Spring adapters.
 *
 * Bound under the [PREFIX] prefix. None of these properties turn on typed sync
 * resources: a consumer always declares one `SyncDefinition` bean per resource.
 * These properties only govern the generic, shared adapters that `sync-spring`
 * auto-configures.
 */
@ConfigurationProperties(prefix = SyncProperties.PREFIX)
class SyncProperties {
    /**
     * Master switch for the auto-configured generic adapters. When `false`,
     * [com.jorisjonkers.personalstack.common.sync.config.SyncAutoConfiguration]
     * contributes nothing and the consumer is expected to wire everything itself.
     */
    var enabled: Boolean = true

    /** Default lock acquisition timeout for sync operations. */
    var lockTimeout: Duration = Duration.ofSeconds(10)

    /** Default page size for paged remote catalog fetches and backfills. */
    var pageSize: Int = 500

    /**
     * Whether idempotency is required. The default requires idempotency for
     * externally triggered syncs (message/scheduler/admin). [IdempotencyMode.DISABLED]
     * must be set explicitly to allow installing a no-op idempotency store.
     */
    var idempotency: IdempotencyMode = IdempotencyMode.REQUIRED_FOR_EXTERNAL_TRIGGERS

    /** Resilience4j wrapping of the remote catalog adapter. */
    var resilience: ResilienceProperties = ResilienceProperties()

    companion object {
        const val PREFIX: String = "extratoast.sync"
    }
}

enum class IdempotencyMode {
    REQUIRED_FOR_EXTERNAL_TRIGGERS,
    DISABLED,
}

class ResilienceProperties {
    /** Whether the resilience4j-wrapped remote catalog adapter is enabled. */
    var enabled: Boolean = true
}
