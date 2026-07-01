package com.jorisjonkers.personalstack.common.vault

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

private const val DEFAULT_JWKS_REFRESH_MINUTES = 5L

@ConfigurationProperties("auth.transit")
data class VaultJwtProperties(
    val enabled: Boolean = false,
    val keyName: String = "jwt",
    val jwksRefresh: Duration = Duration.ofMinutes(DEFAULT_JWKS_REFRESH_MINUTES),
    val localKeyId: String = "local-dev",
)
