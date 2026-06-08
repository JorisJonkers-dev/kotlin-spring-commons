package com.jorisjonkers.personalstack.common.vault

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.vault.core.VaultTemplate
import org.springframework.vault.support.VaultResponse

class VaultSecretProviderTest {
    private val vaultTemplate = mockk<VaultTemplate>()
    private val provider = SpringVaultSecretProvider(vaultTemplate)

    @Test
    fun `getSecret returns value from VaultTemplate`() {
        val response = mockk<VaultResponse>()
        every { vaultTemplate.read("secret/data/myapp") } returns response
        every { response.data } returns mapOf("password" to "s3cret")

        val result = provider.getSecret("secret/data/myapp", "password")

        assertThat(result).isEqualTo("s3cret")
        verify { vaultTemplate.read("secret/data/myapp") }
    }

    @Test
    fun `getSecret throws when path not found`() {
        every { vaultTemplate.read("secret/data/missing") } returns null

        assertThatThrownBy {
            provider.getSecret("secret/data/missing", "key")
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("No secret found at Vault path: secret/data/missing")
    }

    @Test
    fun `getSecret throws when key not found in response`() {
        val response = mockk<VaultResponse>()
        every { vaultTemplate.read("secret/data/myapp") } returns response
        every { response.data } returns mapOf("other" to "value")

        assertThatThrownBy {
            provider.getSecret("secret/data/myapp", "password")
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("Key 'password' not found at Vault path: secret/data/myapp")
    }

    @Test
    fun `getSecret throws when data map is null`() {
        val response = mockk<VaultResponse>()
        every { vaultTemplate.read("secret/data/myapp") } returns response
        every { response.data } returns null

        assertThatThrownBy {
            provider.getSecret("secret/data/myapp", "key")
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("Key 'key' not found at Vault path: secret/data/myapp")
    }

    @Test
    fun `getSecret converts non-string values to string`() {
        val response = mockk<VaultResponse>()
        every { vaultTemplate.read("secret/data/myapp") } returns response
        every { response.data } returns mapOf("port" to 5432)

        val result = provider.getSecret("secret/data/myapp", "port")

        assertThat(result).isEqualTo("5432")
    }

    @Test
    fun `provider implements VaultSecretProvider interface`() {
        assertThat(provider).isInstanceOf(VaultSecretProvider::class.java)
    }
}
