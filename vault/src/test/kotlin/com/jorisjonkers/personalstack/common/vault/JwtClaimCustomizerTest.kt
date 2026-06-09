package com.jorisjonkers.personalstack.common.vault

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.security.authentication.TestingAuthenticationToken
import org.springframework.security.oauth2.core.AuthorizationGrantType
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm
import org.springframework.security.oauth2.jwt.JwsHeader
import org.springframework.security.oauth2.jwt.JwtClaimsSet
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext

class JwtClaimCustomizerTest {
    @Test
    fun `customizer receives generic context and writes claims`() {
        val customizer =
            CompositeJwtClaimCustomizer(
                listOf(
                    JwtClaimCustomizer {
                        it.claims["tenant"] = "test"
                        it.claims["client"] = it.clientId
                    },
                ),
            )
        val registeredClient =
            RegisteredClientFactory.build(
                RegisteredOAuth2Client(
                    id = "id",
                    clientId = "client-a",
                    redirectUris = setOf("https://example.test/callback"),
                    scopes = setOf("openid"),
                ),
            )
        val context =
            JwtEncodingContext
                .with(
                    JwsHeader.with(SignatureAlgorithm.RS256),
                    JwtClaimsSet.builder().subject("alice"),
                )
                .registeredClient(registeredClient)
                .principal(TestingAuthenticationToken("alice", "n/a"))
                .tokenType(OAuth2TokenType.ACCESS_TOKEN)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizedScopes(setOf("openid"))
                .build()

        customizer.customize(context)
        val claims = context.claims.build().claims

        assertThat(claims["tenant"]).isEqualTo("test")
        assertThat(claims["client"]).isEqualTo("client-a")
    }
}
