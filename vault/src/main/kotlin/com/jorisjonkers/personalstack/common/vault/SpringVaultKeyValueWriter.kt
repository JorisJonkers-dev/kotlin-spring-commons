package com.jorisjonkers.personalstack.common.vault

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import org.springframework.vault.core.VaultTemplate

/**
 * HashiCorp Vault KV-v2 implementation. Mirrors the read side in
 * [SpringVaultSecretProvider]: only loaded when
 * `spring.cloud.vault.enabled=true`.
 *
 * Path convention matches the existing read side — callers pass a
 * full `secret/data/...` KV-v2 path. KV-v2 requires the payload to
 * be wrapped under a `data` key, which VaultTemplate.write does
 * implicitly when the path begins with the `data/` segment.
 */
@Component
@ConditionalOnProperty("spring.cloud.vault.enabled", havingValue = "true")
open class SpringVaultKeyValueWriter(
    private val vaultTemplate: VaultTemplate,
) : VaultKeyValueWriter {
    override fun writeSecret(
        path: String,
        data: Map<String, String>,
    ) {
        vaultTemplate.write(path, mapOf("data" to data))
    }

    override fun deleteSecret(path: String) {
        vaultTemplate.delete(path)
    }
}
