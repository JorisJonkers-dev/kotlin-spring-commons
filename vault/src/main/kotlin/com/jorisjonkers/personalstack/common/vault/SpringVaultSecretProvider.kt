package com.jorisjonkers.personalstack.common.vault

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import org.springframework.vault.core.VaultTemplate

/**
 * HashiCorp Vault implementation of [VaultSecretProvider].
 * Only loaded when spring.cloud.vault.enabled=true.
 */
@Component
@ConditionalOnProperty("spring.cloud.vault.enabled", havingValue = "true")
open class SpringVaultSecretProvider(
    private val vaultTemplate: VaultTemplate,
) : VaultSecretProvider {
    override fun getSecret(
        path: String,
        key: String,
    ): String {
        val response =
            vaultTemplate.read(path)
                ?: error("No secret found at Vault path: $path")
        return response.data?.get(key)?.toString()
            ?: error("Key '$key' not found at Vault path: $path")
    }
}
