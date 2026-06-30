package com.jorisjonkers.personalstack.common.vault

import com.nimbusds.jose.jwk.JWKMatcher
import com.nimbusds.jose.jwk.JWKSelector
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.security.oauth2.jwt.JwtClaimsSet
import org.springframework.security.oauth2.jwt.JwtEncoderParameters
import org.springframework.vault.core.VaultTemplate
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.time.Duration

class VaultJwtAutoConfigurationTest {
    private val config = VaultJwtAutoConfiguration()
    private val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val publicKey = keyPair.public as RSAPublicKey

    @Test
    fun `properties expose documented defaults`() {
        val properties = VaultJwtProperties()

        assertThat(properties.enabled).isFalse
        assertThat(properties.keyName).isEqualTo("jwt")
        assertThat(properties.jwksRefresh).isEqualTo(Duration.ofMinutes(5))
        assertThat(properties.localKeyId).isEqualTo("local-dev")
    }

    @Test
    fun `springVaultTransitClient creates Vault-backed transit client`() {
        val vaultTemplate = mockk<VaultTemplate>()

        val transitClient = config.springVaultTransitClient(vaultTemplate)

        assertThat(transitClient).isInstanceOf(SpringVaultTransitClient::class.java)
    }

    @Test
    fun `vaultTransitJwkSource refreshes during creation`() {
        val transitClient = mockk<VaultTransitClient>()
        every { transitClient.readKeyVersions("api-jwt") } returns
            listOf(VaultTransitKeyVersion(1, "api-jwt:v1", publicKey))

        val source =
            config.vaultTransitJwkSource(
                transitClient,
                VaultJwtProperties(enabled = true, keyName = "api-jwt"),
            )

        assertThat(source.get(JWKSelector(JWKMatcher.Builder().keyID("api-jwt:v1").build()), null)).hasSize(1)
    }

    @Test
    fun `vaultTransitJwtEncoder signs with latest transit key version`() {
        val transitClient = mockk<VaultTransitClient>()
        every { transitClient.readKeyVersions("api-jwt") } returns
            listOf(
                VaultTransitKeyVersion(1, "api-jwt:v1", publicKey),
                VaultTransitKeyVersion(2, "api-jwt:v2", publicKey),
            )
        every { transitClient.sign(eq("api-jwt"), any(), eq(2)) } returns byteArrayOf(1, 2, 3)
        val encoder =
            config.vaultTransitJwtEncoder(
                transitClient,
                VaultJwtProperties(enabled = true, keyName = "api-jwt"),
            )

        val token =
            encoder.encode(
                JwtEncoderParameters.from(
                    JwtClaimsSet
                        .builder()
                        .subject("alice")
                        .build(),
                ),
            )

        assertThat(token.tokenValue).isNotBlank
        verify { transitClient.sign(eq("api-jwt"), any(), eq(2)) }
    }

    @Test
    fun `vaultTransitJwtEncoder rejects transit keys without public versions`() {
        val transitClient = mockk<VaultTransitClient>()
        every { transitClient.readKeyVersions("api-jwt") } returns emptyList()
        val encoder =
            config.vaultTransitJwtEncoder(
                transitClient,
                VaultJwtProperties(enabled = true, keyName = "api-jwt"),
            )

        assertThatThrownBy {
            encoder.encode(
                JwtEncoderParameters.from(
                    JwtClaimsSet
                        .builder()
                        .subject("alice")
                        .build(),
                ),
            )
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("Transit key 'api-jwt' does not expose RSA public keys")
    }

    @Test
    fun `localRsaJwk uses configured local key id`() {
        val key = config.localRsaJwk(VaultJwtProperties(localKeyId = "local-test"))

        assertThat(key.keyID).isEqualTo("local-test")
    }
}
