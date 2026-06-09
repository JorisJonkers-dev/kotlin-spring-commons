package com.jorisjonkers.personalstack.common.vault

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.security.oauth2.core.AuthorizationGrantType
import org.springframework.security.oauth2.core.ClientAuthenticationMethod

class RegisteredClientFactoryTest {
    @Test
    fun `builds data driven registered clients and applies customizers`() {
        val client =
            RegisteredClientFactory.build(
                RegisteredOAuth2Client(
                    clientId = "docs",
                    clientSecret = "{noop}secret",
                    authenticationMethods = setOf(ClientAuthenticationMethod.CLIENT_SECRET_BASIC),
                    authorizationGrantTypes = setOf(
                        AuthorizationGrantType.AUTHORIZATION_CODE,
                        AuthorizationGrantType.REFRESH_TOKEN,
                    ),
                    redirectUris = setOf("https://example.test/callback"),
                    scopes = setOf("openid", "profile"),
                ),
                listOf(RegisteredClientCustomizer { _, builder -> builder.scope("email") }),
            )

        assertThat(client.clientId).isEqualTo("docs")
        assertThat(client.redirectUris).containsExactly("https://example.test/callback")
        assertThat(client.scopes).contains("openid", "profile", "email")
        assertThat(client.clientAuthenticationMethods).contains(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
    }
}
