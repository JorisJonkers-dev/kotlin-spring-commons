package com.jorisjonkers.personalstack.common.vault

import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
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
        assertThat(keys.map { it.keyId }).containsExactly("auth-api-jwt-v1", "auth-api-jwt-v2")
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
