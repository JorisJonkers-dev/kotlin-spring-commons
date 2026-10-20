package com.jorisjonkers.personalstack.common.identity.principal

import com.nimbusds.jose.util.JSONObjectUtils
import java.net.URLEncoder
import java.net.http.HttpRequest
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.ParseException
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/** Asks identity about opaque tokens with the app's Client secret, keeping each answer at most 30 seconds. */
internal class Introspector(
    private val config: PrincipalReaderConfig,
) {
    private class Answer(
        val result: Result<Principal>,
        val until: Instant,
    )

    private val answers = ConcurrentHashMap<String, Answer>()

    fun introspected(token: String): Principal {
        val secret = config.clientSecret ?: refuse("inactive")
        val key = sha256(token)
        val now = config.clock.instant()
        val kept = answers[key]?.takeIf { now.isBefore(it.until) }
        val result = kept?.result ?: runCatching { answered(ask(token, secret), config) }
        result.exceptionOrNull()?.takeIf { it !is PrincipalRefusedException }?.let { throw it }
        if (kept == null) {
            if (answers.size >= MAX_ANSWERS) answers.clear()
            answers[key] = Answer(result, now.plus(ANSWER_LIFETIME))
        }
        return result.getOrThrow()
    }

    private fun ask(
        token: String,
        secret: String,
    ): Map<String, Any?> {
        val credentials = "${encoded(config.clientId)}:${encoded(secret)}"
        val request =
            HttpRequest
                .newBuilder(config.introspectUri)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Authorization", "Basic " + Base64.getEncoder().encodeToString(credentials.toByteArray()))
                .POST(HttpRequest.BodyPublishers.ofString("token=" + encoded(token)))
                .build()
        val body = config.send(request, "introspection")
        return try {
            JSONObjectUtils.parse(body)
        } catch (e: ParseException) {
            throw IdentityUnavailableException("identity's introspection answer is no JSON", e)
        }
    }

    private fun encoded(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

    private fun sha256(value: String): String =
        Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(value.toByteArray()))

    private companion object {
        const val MAX_ANSWERS = 10_000
        val ANSWER_LIFETIME: Duration = Duration.ofSeconds(30)
    }
}
