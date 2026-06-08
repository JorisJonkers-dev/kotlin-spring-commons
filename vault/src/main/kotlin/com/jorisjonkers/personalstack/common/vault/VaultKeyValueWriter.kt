package com.jorisjonkers.personalstack.common.vault

/**
 * Write-side companion to [VaultSecretProvider]. Stored at a KV-v2
 * path, opaque map of string keys to string values. The same `path`
 * conventions apply: callers pass the full KV-v2 data path
 * (e.g. `secret/data/agents/projects/<id>/repos/<id>`) and the
 * adapter is responsible for any backend-specific wrapping.
 */
interface VaultKeyValueWriter {
    fun writeSecret(
        path: String,
        data: Map<String, String>,
    )

    fun deleteSecret(path: String)
}
