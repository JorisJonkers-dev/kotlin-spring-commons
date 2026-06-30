package com.jorisjonkers.personalstack.common.vault

import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.vault.core.VaultTemplate
import org.springframework.vault.support.VaultResponse
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.util.Base64

class VaultTransitClientTest {
    private val vaultTemplate = mockk<VaultTemplate>()
    private val transitClient = SpringVaultTransitClient(vaultTemplate)

    @Test
    fun `readKeyVersions parses RSA public keys from transit response`() {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val publicKey = keyPair.public as RSAPublicKey
        val pem = publicKey.toPem()

        val response = mockk<VaultResponse>()
        every { vaultTemplate.read("transit/keys/auth-api-jwt") } returns response
        every { response.data } returns
            mapOf(
                "keys" to
                    mapOf(
                        "1" to mapOf("public_key" to pem),
                        "2" to mapOf("public_key" to pem),
                    ),
            )

        val keys = transitClient.readKeyVersions("auth-api-jwt")

        assertThat(keys).hasSize(2)
        assertThat(keys.map { it.keyId }).containsExactly("auth-api-jwt:v1", "auth-api-jwt:v2")
    }

    @Test
    fun `readKeyVersions throws when transit key is missing`() {
        every { vaultTemplate.read("transit/keys/auth-api-jwt") } returns null

        assertThatThrownBy {
            transitClient.readKeyVersions("auth-api-jwt")
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("No transit key found at transit/keys/auth-api-jwt")
    }

    @Test
    fun `readKeyVersions throws when key versions are missing`() {
        val response = mockk<VaultResponse>()
        every { vaultTemplate.read("transit/keys/auth-api-jwt") } returns response
        every { response.data } returns emptyMap()

        assertThatThrownBy {
            transitClient.readKeyVersions("auth-api-jwt")
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("Transit key 'auth-api-jwt' does not expose key versions")
    }

    @Test
    fun `readKeyVersions throws when response data is missing`() {
        val response = mockk<VaultResponse>()
        every { vaultTemplate.read("transit/keys/auth-api-jwt") } returns response
        every { response.data } returns null

        assertThatThrownBy {
            transitClient.readKeyVersions("auth-api-jwt")
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("Transit key 'auth-api-jwt' does not expose key versions")
    }

    @Test
    fun `readKeyVersions throws when key versions have unexpected shape`() {
        val response = mockk<VaultResponse>()
        every { vaultTemplate.read("transit/keys/auth-api-jwt") } returns response
        every { response.data } returns
            mapOf(
                "keys" to
                    mapOf(
                        "1" to "not-a-key-map",
                        "2" to mapOf("public_key" to null),
                    ),
            )

        assertThatThrownBy {
            transitClient.readKeyVersions("auth-api-jwt")
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("Transit key 'auth-api-jwt' does not expose RSA public keys")
    }

    @Test
    fun `readKeyVersions throws when no RSA public keys are present`() {
        val response = mockk<VaultResponse>()
        every { vaultTemplate.read("transit/keys/auth-api-jwt") } returns response
        every { response.data } returns mapOf("keys" to mapOf("1" to emptyMap<String, String>()))

        assertThatThrownBy {
            transitClient.readKeyVersions("auth-api-jwt")
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("Transit key 'auth-api-jwt' does not expose RSA public keys")
    }

    @Test
    fun `sign decodes Vault signature payload`() {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val privateKey = keyPair.private as RSAPrivateKey
        val input = "header.payload".toByteArray()
        val rawSignature = sign(privateKey, input)
        val vaultSignature = "vault:v4:${Base64.getEncoder().encodeToString(rawSignature)}"

        val response = mockk<VaultResponse>()
        every {
            vaultTemplate.write(
                "transit/sign/auth-api-jwt",
                match<Map<String, Any>> {
                    it["key_version"] == 4 &&
                        it["signature_algorithm"] == "pkcs1v15"
                },
            )
        } returns response
        every { response.data } returns mapOf("signature" to vaultSignature)

        val result = transitClient.sign("auth-api-jwt", input, 4)

        assertThat(result).containsExactly(*rawSignature)
    }

    @Test
    fun `sign throws when Vault returns no response`() {
        every {
            vaultTemplate.write("transit/sign/auth-api-jwt", any<Map<String, Any>>())
        } returns null

        assertThatThrownBy {
            transitClient.sign("auth-api-jwt", "header.payload".toByteArray(), 4)
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("Transit signing request returned no response for key 'auth-api-jwt'")
    }

    @Test
    fun `sign throws when Vault omits signature`() {
        val response = mockk<VaultResponse>()
        every {
            vaultTemplate.write("transit/sign/auth-api-jwt", any<Map<String, Any>>())
        } returns response
        every { response.data } returns emptyMap()

        assertThatThrownBy {
            transitClient.sign("auth-api-jwt", "header.payload".toByteArray(), 4)
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("Transit signing response did not include a signature for key 'auth-api-jwt'")
    }

    @Test
    fun `sign throws when Vault signature data is missing`() {
        val response = mockk<VaultResponse>()
        every {
            vaultTemplate.write("transit/sign/auth-api-jwt", any<Map<String, Any>>())
        } returns response
        every { response.data } returns null

        assertThatThrownBy {
            transitClient.sign("auth-api-jwt", "header.payload".toByteArray(), 4)
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("Transit signing response did not include a signature for key 'auth-api-jwt'")
    }

    @Test
    fun `sign throws when Vault signature format is unexpected`() {
        val response = mockk<VaultResponse>()
        every {
            vaultTemplate.write("transit/sign/auth-api-jwt", any<Map<String, Any>>())
        } returns response
        every { response.data } returns mapOf("signature" to "vault:v4")

        assertThatThrownBy {
            transitClient.sign("auth-api-jwt", "header.payload".toByteArray(), 4)
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("Unexpected transit signature format for key 'auth-api-jwt'")
    }

    private fun sign(
        privateKey: RSAPrivateKey,
        input: ByteArray,
    ): ByteArray =
        Signature.getInstance("SHA256withRSA").run {
            initSign(privateKey)
            update(input)
            sign()
        }

    private fun RSAPublicKey.toPem(): String {
        val encoded = Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(this.encoded)
        return "-----BEGIN PUBLIC KEY-----\n$encoded\n-----END PUBLIC KEY-----"
    }
}
