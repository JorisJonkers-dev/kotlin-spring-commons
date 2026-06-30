package com.jorisjonkers.personalstack.common.vault

import org.springframework.security.oauth2.core.AuthorizationGrantType
import org.springframework.security.oauth2.core.ClientAuthenticationMethod
import org.springframework.security.oauth2.server.authorization.client.InMemoryRegisteredClientRepository
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings
import java.time.Duration
import java.util.UUID

private const val DEFAULT_ACCESS_TOKEN_TTL_MINUTES = 15L
private const val DEFAULT_REFRESH_TOKEN_TTL_DAYS = 7L

data class RegisteredOAuth2Client(
    val clientId: String,
    val clientSecret: String? = null,
    val authenticationMethods: Set<ClientAuthenticationMethod> = setOf(ClientAuthenticationMethod.NONE),
    val authorizationGrantTypes: Set<AuthorizationGrantType> = setOf(AuthorizationGrantType.AUTHORIZATION_CODE),
    val redirectUris: Set<String>,
    val postLogoutRedirectUris: Set<String> = emptySet(),
    val scopes: Set<String>,
    val requireProofKey: Boolean = true,
    val requireAuthorizationConsent: Boolean = false,
    val accessTokenTtl: Duration = Duration.ofMinutes(DEFAULT_ACCESS_TOKEN_TTL_MINUTES),
    val refreshTokenTtl: Duration = Duration.ofDays(DEFAULT_REFRESH_TOKEN_TTL_DAYS),
    val reuseRefreshTokens: Boolean = false,
    val id: String = UUID.randomUUID().toString(),
)

fun interface RegisteredClientCustomizer {
    fun customize(
        definition: RegisteredOAuth2Client,
        builder: RegisteredClient.Builder,
    )
}

object RegisteredClientFactory {
    @JvmStatic
    fun build(
        definition: RegisteredOAuth2Client,
        customizers: List<RegisteredClientCustomizer> = emptyList(),
    ): RegisteredClient {
        require(definition.clientId.isNotBlank()) { "clientId must not be blank" }
        require(definition.redirectUris.isNotEmpty()) { "redirectUris must not be empty" }
        require(definition.scopes.isNotEmpty()) { "scopes must not be empty" }

        val builder =
            RegisteredClient
                .withId(definition.id)
                .clientId(definition.clientId)
                .clientSettings(
                    ClientSettings
                        .builder()
                        .requireProofKey(definition.requireProofKey)
                        .requireAuthorizationConsent(definition.requireAuthorizationConsent)
                        .build(),
                ).tokenSettings(
                    TokenSettings
                        .builder()
                        .accessTokenTimeToLive(definition.accessTokenTtl)
                        .refreshTokenTimeToLive(definition.refreshTokenTtl)
                        .reuseRefreshTokens(definition.reuseRefreshTokens)
                        .build(),
                )

        definition.clientSecret?.let(builder::clientSecret)
        definition.authenticationMethods.forEach(builder::clientAuthenticationMethod)
        definition.authorizationGrantTypes.forEach(builder::authorizationGrantType)
        definition.redirectUris.forEach(builder::redirectUri)
        definition.postLogoutRedirectUris.forEach(builder::postLogoutRedirectUri)
        definition.scopes.forEach(builder::scope)
        customizers.forEach { it.customize(definition, builder) }
        return builder.build()
    }

    @JvmStatic
    fun inMemoryRepository(
        definitions: Collection<RegisteredOAuth2Client>,
        customizers: List<RegisteredClientCustomizer> = emptyList(),
    ): RegisteredClientRepository = InMemoryRegisteredClientRepository(definitions.map { build(it, customizers) })
}
