package com.jorisjonkers.personalstack.common.vault

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.security.oauth2.jwt.JwtClaimsSet
import org.springframework.security.oauth2.jwt.JwtEncoderParameters
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.time.Instant

class VaultTransitJwtEncoderTest {
    private lateinit var privateKey: RSAPrivateKey
    private lateinit var publicKey: RSAPublicKey
    private lateinit var transitClient: VaultTransitClient
    private lateinit var encoder: VaultTransitJwtEncoder

    @BeforeEach
    fun setUp() {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        privateKey = keyPair.private as RSAPrivateKey
        publicKey = keyPair.public as RSAPublicKey
        transitClient = mockk()

        val activeKey =
            VaultTransitKeyVersion(
                version = 3,
                keyId = "auth-api-jwt-v3",
                publicKey = publicKey,
            )
        encoder = VaultTransitJwtEncoder(transitClient, "auth-api-jwt", activeKey)

        every { transitClient.sign(eq("auth-api-jwt"), any(), eq(3)) } answers {
            val signature = Signature.getInstance("SHA256withRSA")
            signature.initSign(privateKey)
            signature.update(secondArg<ByteArray>())
            signature.sign()
        }
    }

    @Test
    fun `encoder produces RS256 JWT signed through transit client`() {
        val now = Instant.now()
        val claims =
            JwtClaimsSet
                .builder()
                .issuer("https://auth.jorisjonkers.dev")
                .subject("user-123")
                .claim("username", "alice")
                .issuedAt(now)
                .expiresAt(now.plusSeconds(900))
                .build()

        val token = encoder.encode(JwtEncoderParameters.from(claims))
        val jwt = NimbusJwtDecoder.withPublicKey(publicKey).build().decode(token.tokenValue)

        assertThat(jwt.subject).isEqualTo("user-123")
        assertThat(jwt.getClaimAsString("username")).isEqualTo("alice")
        assertThat(jwt.headers["kid"]).isEqualTo("auth-api-jwt-v3")
        verify { transitClient.sign(eq("auth-api-jwt"), any(), eq(3)) }
    }
}
