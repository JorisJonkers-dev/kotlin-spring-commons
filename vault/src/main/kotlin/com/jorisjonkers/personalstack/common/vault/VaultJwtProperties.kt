package com.jorisjonkers.personalstack.common.vault

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties("auth.transit")
data class VaultJwtProperties(
    val enabled: Boolean = false,
    val keyName: String = "jwt",
    val jwksRefresh: Duration = Duration.ofMinutes(5),
    val localKeyId: String = "local-dev",
)
