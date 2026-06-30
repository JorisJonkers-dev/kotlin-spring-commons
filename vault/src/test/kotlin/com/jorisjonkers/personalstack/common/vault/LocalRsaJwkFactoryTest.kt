package com.jorisjonkers.personalstack.common.vault

import com.nimbusds.jose.jwk.JWKMatcher
import com.nimbusds.jose.jwk.JWKSelector
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.security.oauth2.jwt.JwtClaimsSet
import org.springframework.security.oauth2.jwt.JwtEncoderParameters
import java.time.Instant

class LocalRsaJwkFactoryTest {
    @Test
    fun `local fallback key can encode jwt and publish matching jwk`() {
        val key = LocalRsaJwkFactory.generate("test-local")
        val config = VaultJwtAutoConfiguration()
        val source = config.localJwkSource(key)
        val encoder = config.localJwtEncoder(source)

        val token =
            encoder.encode(
                JwtEncoderParameters.from(
                    JwtClaimsSet
                        .builder()
                        .subject("alice")
                        .issuedAt(Instant.now())
                        .expiresAt(Instant.now().plusSeconds(60))
                        .build(),
                ),
            )

        assertThat(token.tokenValue).isNotBlank()
        assertThat(source.get(JWKSelector(JWKMatcher.Builder().keyID("test-local").build()), null)).hasSize(1)
    }

    @Test
    fun `generate uses local development key id by default`() {
        val key = LocalRsaJwkFactory.generate()

        assertThat(key.keyID).isEqualTo("local-dev")
    }
}
