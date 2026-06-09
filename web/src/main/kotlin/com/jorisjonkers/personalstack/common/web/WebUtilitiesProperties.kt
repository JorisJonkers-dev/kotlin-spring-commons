package com.jorisjonkers.personalstack.common.web

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties("extratoast.web")
data class WebUtilitiesProperties(
    val problemDetails: ProblemDetailsProperties = ProblemDetailsProperties(),
    val csrf: CsrfProperties = CsrfProperties(),
    val rateLimit: RateLimitProperties = RateLimitProperties(),
) {
    data class ProblemDetailsProperties(
        val validationEnabled: Boolean = false,
        val validationStatus: Int = 400,
        val validationDetail: String = "Validation failed for request.",
        val openApiSchemasEnabled: Boolean = false,
        val apiErrorSchemaName: String = "ApiError",
        val fieldErrorSchemaName: String = "FieldValidationError",
    )

    data class CsrfProperties(
        val enabled: Boolean = false,
        val path: String = "/csrf",
        val tokenField: String = "token",
    )

    data class RateLimitProperties(
        val enabled: Boolean = false,
        val maxBuckets: Int = 10_000,
        val bucketIdleTtl: Duration = Duration.ofMinutes(30),
        val cleanupInterval: Int = 128,
        val trustedProxyCidrs: List<String> =
            listOf("127.0.0.1/32", "::1/128", "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "fc00::/7"),
        val rules: List<RouteRateLimitRule> = emptyList(),
    )

    data class RouteRateLimitRule(
        val method: String = "POST",
        val pathPattern: String,
        val maxRequests: Int,
        val window: Duration,
    )
}
