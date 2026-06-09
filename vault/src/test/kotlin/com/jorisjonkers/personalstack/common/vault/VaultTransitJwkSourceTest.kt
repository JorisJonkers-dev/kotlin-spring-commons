package com.jorisjonkers.personalstack.common.vault

import com.nimbusds.jose.jwk.JWKMatcher
import com.nimbusds.jose.jwk.JWKSelector
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.time.Duration

class VaultTransitJwkSourceTest {
    private val transitClient = mockk<VaultTransitClient>()
    private val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val publicKey = keyPair.public as RSAPublicKey

    @Test
    fun `publishes Vault Transit public keys with jwt metadata`() {
        every { transitClient.readKeyVersions("api-jwt") } returns
            listOf(VaultTransitKeyVersion(2, "api-jwt:v2", publicKey))

        val source = VaultTransitJwkSource(transitClient, "api-jwt", Duration.ofMinutes(5))
        val selected = source.get(JWKSelector(JWKMatcher.Builder().keyID("api-jwt:v2").build()), null)

        assertThat(selected).hasSize(1)
        assertThat(selected.first().keyID).isEqualTo("api-jwt:v2")
        assertThat(selected.first().algorithm?.name).isEqualTo("RS256")
        assertThat(selected.first().keyUse?.value).isEqualTo("sig")
    }

    @Test
    fun `keeps cached jwks when refresh fails`() {
        every { transitClient.readKeyVersions("api-jwt") } returns
            listOf(VaultTransitKeyVersion(1, "api-jwt:v1", publicKey)) andThenThrows
            IllegalStateException("vault unavailable")

        val source = VaultTransitJwkSource(transitClient, "api-jwt", Duration.ZERO)
        source.refresh()

        val selected = source.get(JWKSelector(JWKMatcher.Builder().keyID("api-jwt:v1").build()), null)

        assertThat(selected).hasSize(1)
        assertThat(selected.first().keyID).isEqualTo("api-jwt:v1")
    }
}
