package com.jorisjonkers.personalstack.common.identity.principal

import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import java.net.http.HttpRequest
import java.text.ParseException
import java.time.Duration
import java.time.Instant

/** identity's published keys, fetched once and again for an id they do not know, at most once a minute. */
internal class IssuerKeys(
    private val config: PrincipalReaderConfig,
) {
    private var keys = JWKSet()
    private var fetched: Instant? = null

    @Synchronized
    fun key(kid: String?): RSAKey? {
        val now = config.clock.instant()
        val mayFetch = fetched?.let { Duration.between(it, now) >= REFETCH_AFTER } ?: true
        if (keys.getKeyByKeyId(kid) == null && mayFetch) {
            keys = fetch()
            fetched = now
        }
        return keys.getKeyByKeyId(kid)?.toRSAKey()
    }

    private fun fetch(): JWKSet {
        val body = config.send(HttpRequest.newBuilder(config.keysUri).GET().build(), "keys")
        return try {
            JWKSet.parse(body)
        } catch (e: ParseException) {
            throw IdentityUnavailableException("identity's keys are no JWKS", e)
        }
    }

    private companion object {
        val REFETCH_AFTER: Duration = Duration.ofMinutes(1)
    }
}
