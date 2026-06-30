package com.jorisjonkers.personalstack.common.vault

import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.vault.core.VaultTemplate

class SpringVaultKeyValueWriterTest {
    private val vaultTemplate = mockk<VaultTemplate>()
    private val writer = SpringVaultKeyValueWriter(vaultTemplate)

    @Test
    fun `writeSecret wraps data for kv v2 writes`() {
        every { vaultTemplate.write("secret/data/myapp", mapOf("data" to mapOf("password" to "s3cret"))) } returns null

        writer.writeSecret("secret/data/myapp", mapOf("password" to "s3cret"))

        verify { vaultTemplate.write("secret/data/myapp", mapOf("data" to mapOf("password" to "s3cret"))) }
    }

    @Test
    fun `deleteSecret delegates to VaultTemplate`() {
        every { vaultTemplate.delete("secret/data/myapp") } just Runs

        writer.deleteSecret("secret/data/myapp")

        verify { vaultTemplate.delete("secret/data/myapp") }
    }
}
