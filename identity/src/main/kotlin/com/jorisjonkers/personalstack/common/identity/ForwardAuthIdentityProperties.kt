package com.jorisjonkers.personalstack.common.identity

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "extratoast.identity")
data class ForwardAuthIdentityProperties(
    val enabled: Boolean = false,
    val headerName: String = "X-Agents-Verified-Jwt",
    val jwksUri: String? = null,
    val issuer: String? = null,
    val audience: String? = null,
    val acceptAuthorizationBearer: Boolean = true,
    val skipPathPatterns: List<String> =
        listOf(
            "/actuator/**",
            "/api/actuator/**",
            "/api/v1/health/**",
            "/api/v1/api-docs/**",
            "/api/v1/swagger-ui/**",
            "/api/v1/internal/**",
        ),
)
