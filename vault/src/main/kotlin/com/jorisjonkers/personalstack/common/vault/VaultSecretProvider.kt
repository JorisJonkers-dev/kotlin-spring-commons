package com.jorisjonkers.personalstack.common.vault

interface VaultSecretProvider {
    fun getSecret(
        path: String,
        key: String,
    ): String
}
