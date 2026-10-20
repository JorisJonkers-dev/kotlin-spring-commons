package com.jorisjonkers.personalstack.common.identity.principal

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSObject
import com.nimbusds.jose.crypto.RSASSAVerifier
import java.net.URI
import java.net.http.HttpClient
import java.time.Clock

/** Where the Principals an app reads come from, and which Client the app is. */
data class PrincipalReaderConfig(
    /** identity's URL, as each Principal's iss states it. */
    val issuer: String,
    /** The app's own Client, which each Principal must be for. */
    val clientId: String,
    /** The app's Client secret, which it introspects opaque tokens with; without one they are refused. */
    val clientSecret: String? = null,
    val keysUri: URI = URI.create(issuer.trimEnd('/') + "/oidc/keys"),
    val introspectUri: URI = URI.create(issuer.trimEnd('/') + "/oidc/introspect"),
    val httpClient: HttpClient = HttpClient.newHttpClient(),
    val clock: Clock = Clock.systemUTC(),
)

/** Why a request carries no Principal: one of the contract's refusals. */
class PrincipalRefusedException(
    val reason: String,
) : RuntimeException("the request's Principal is refused: $reason")

/** identity could not be asked: its keys or its introspection did not answer. */
class IdentityUnavailableException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * Reads who is calling from identity's Principal contract: the Gate's signed header when a request
 * has one, or else its bearer token, a JWT access token verified here or an opaque token
 * introspected. The plain X-Auth-Request-* headers are never read.
 */
class PrincipalReader(
    private val config: PrincipalReaderConfig,
) {
    private val keys = IssuerKeys(config)
    private val introspector = Introspector(config)

    /** The Principal a request carries, given its headers by name; [PrincipalRefusedException] when none. */
    fun read(header: (String) -> String?): Principal {
        val signed = header(HEADER)?.takeIf { it.isNotEmpty() }
        return when {
            signed != null -> checked(verified(signed, TYPE), config)
            else -> bearer(bearerToken(header("Authorization")) ?: refuse("missing"))
        }
    }

    private fun bearer(token: String): Principal {
        if (token.count { it == '.' } != 2) return introspector.introspected(token)
        val claims = verified(token, ACCESS_TOKEN_TYPE)
        if ((claims["client_id"] as? String).isNullOrEmpty()) refuse("not-an-access-token")
        return checked(claims, config)
    }

    private fun bearerToken(authorization: String?): String? {
        val parts = authorization?.split(" ", limit = 2).orEmpty()
        val isBearer = parts.size == 2 && parts[0].equals("Bearer", ignoreCase = true)
        return parts.takeIf { isBearer }?.get(1)?.takeIf { it.isNotEmpty() }
    }

    private fun verified(
        token: String,
        typ: String,
    ): Map<String, Any?> {
        val jws = runCatching { JWSObject.parse(token) }.getOrNull() ?: refuse("bad-signature")
        if (jws.header.algorithm != JWSAlgorithm.RS256) refuse("bad-signature")
        if (jws.header.type?.type != typ) refuse("wrong-typ")
        val key = keys.key(jws.header.keyID) ?: refuse("unknown-key")
        if (!jws.verify(RSASSAVerifier(key))) refuse("bad-signature")
        return jws.payload.toJSONObject() ?: refuse("bad-signature")
    }

    companion object {
        /** The Gate's signed Principal. */
        const val HEADER = "X-Identity-Principal"

        /** The signed Principal's JWT typ, which no access token carries. */
        const val TYPE = "identity-principal+jwt"

        /** zitadel/oidc signs access and ID tokens alike as JWT; an access token alone names a client_id. */
        private const val ACCESS_TOKEN_TYPE = "JWT"
    }
}
