package com.jorisjonkers.personalstack.common.sync.domain

/**
 * The framework's projection of a local aggregate: the aggregate itself plus the sync-relevant
 * facets (local id, link [registration] and the set of match [keys]). Produced by a
 * [LocalProjector]; projection must be pure and must not mutate the aggregate.
 *
 * @param A the consumer's aggregate type.
 * @param RID the consumer's remote id type.
 * @param KEY the consumer's match key type.
 */
data class LocalRecord<A : Any, RID : Any, KEY : Any>(
    val aggregate: A,
    val localId: LocalId?,
    val registration: SyncRegistration<RID>,
    val keys: Set<KEY>,
)
