package com.jorisjonkers.personalstack.common.vault

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.jwk.JWK
import com.nimbusds.jose.jwk.JWKSelector
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.KeyUse
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.source.JWKSource
import com.nimbusds.jose.proc.SecurityContext
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

/**
 * JWKS source backed by Vault Transit public keys.
 *
 * Refresh failures keep the previous set so a transient Vault outage does not
 * break the authorization server's JWKS endpoint after at least one successful
 * refresh.
 */
class VaultTransitJwkSource(
    private val transitClient: VaultTransitClient,
    private val keyName: String,
    private val refreshInterval: Duration = Duration.ofMinutes(5),
    private val clock: Clock = Clock.systemUTC(),
) : JWKSource<SecurityContext> {
    private val current = AtomicReference(JWKSet(emptyList<RSAKey>()))
    private val lastRefresh = AtomicReference<Instant?>(null)

    override fun get(
        selector: JWKSelector,
        context: SecurityContext?,
    ): List<JWK> {
        refreshIfStale()
        return selector.select(current.get())
    }

    fun refresh(): JWKSet {
        val keys =
            transitClient
                .readKeyVersions(keyName)
                .map { transitKey ->
                    RSAKey
                        .Builder(transitKey.publicKey)
                        .keyID(transitKey.keyId)
                        .algorithm(JWSAlgorithm.RS256)
                        .keyUse(KeyUse.SIGNATURE)
                        .build()
                }
        require(keys.isNotEmpty()) { "Vault returned no public keys for transit key '$keyName'" }
        val jwkSet = JWKSet(keys)
        current.set(jwkSet)
        lastRefresh.set(clock.instant())
        return jwkSet
    }

    fun refreshIfStale(): JWKSet {
        val refreshedAt = lastRefresh.get()
        if (refreshedAt != null && clock.instant().isBefore(refreshedAt.plus(refreshInterval))) {
            return current.get()
        }
        return runCatching { refresh() }
            .onFailure { log.warn("JWKS refresh from Vault failed; keeping cached key set", it) }
            .getOrElse { current.get() }
    }

    companion object {
        private val log = LoggerFactory.getLogger(VaultTransitJwkSource::class.java)
    }
}
