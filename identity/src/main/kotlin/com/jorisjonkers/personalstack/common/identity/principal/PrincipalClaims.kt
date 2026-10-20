package com.jorisjonkers.personalstack.common.identity.principal

import java.io.IOException
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Instant

private const val MAJOR = "1"
private const val HTTP_OK = 200

internal fun refuse(reason: String): Nothing = throw PrincipalRefusedException(reason)

/** claims' Principal, or why claims state none for config's Client. */
internal fun checked(
    claims: Map<String, Any?>,
    config: PrincipalReaderConfig,
): Principal {
    when {
        major(claims) != MAJOR -> refuse("unknown-major")
        claims["iss"] != config.issuer -> refuse("wrong-issuer")
        audience(claims["aud"]) != listOf(config.clientId) -> refuse("wrong-audience")
        !config.clock.instant().isBefore(instant(claims["exp"])) -> refuse("expired")
    }
    return principalOf(claims)
}

/** An introspection answer's Principal, or why it states none for config's Client. */
internal fun answered(
    claims: Map<String, Any?>,
    config: PrincipalReaderConfig,
): Principal {
    when {
        claims["active"] != true -> refuse("inactive")
        major(claims) != MAJOR -> refuse("unknown-major")
        claims["client_id"] != config.clientId -> refuse("wrong-audience")
        audience(claims["aud"]) != listOf(config.clientId) -> refuse("wrong-audience")
    }
    return principalOf(claims)
}

internal fun principalOf(claims: Map<String, Any?>): Principal =
    Principal(
        subject = (claims["sub"] as? String).orEmpty(),
        kind = Kind.entries.firstOrNull { it.value == claims["kind"] } ?: refuse("unknown-major"),
        email = claims["email"] as? String,
        name = claims["name"] as? String,
        roles = (claims["roles"] as? List<*>)?.map { it.toString() }.orEmpty(),
        via = Via.entries.firstOrNull { it.value == claims["via"] } ?: refuse("unknown-major"),
        actor = (claims["act"] as? Map<*, *>)?.let { Actor(id = it["sub"].toString(), name = it["name"].toString()) },
        twoFactor = claims["two_factor"] == true,
        twoFactorAt = claims["two_factor_at"]?.let { instant(it) },
        sid = claims["sid"] as? String,
        ver = claims["ver"] as String,
    )

private fun major(claims: Map<String, Any?>): String? = (claims["ver"] as? String)?.substringBefore('.')

private fun audience(aud: Any?): List<String>? =
    when (aud) {
        is String -> listOf(aud)
        is List<*> -> aud.map { it.toString() }
        else -> null
    }

private fun instant(value: Any?): Instant = Instant.ofEpochSecond((value as? Number)?.toLong() ?: 0)

/** The body identity answers request with; [IdentityUnavailableException] when it does not answer 200. */
internal fun PrincipalReaderConfig.send(
    request: HttpRequest,
    what: String,
): String {
    val response =
        try {
            httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (e: IOException) {
            throw IdentityUnavailableException("identity's $what did not answer", e)
        }
    if (response.statusCode() != HTTP_OK) {
        throw IdentityUnavailableException("identity's $what answered ${response.statusCode()}")
    }
    return response.body()
}
