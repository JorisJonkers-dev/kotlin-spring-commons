package com.jorisjonkers.personalstack.common.web

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

private const val DEFAULT_RATE_LIMIT_MAX_BUCKETS = 10_000
private const val DEFAULT_RATE_LIMIT_BUCKET_IDLE_TTL_MINUTES = 30L
private const val DEFAULT_RATE_LIMIT_CLEANUP_INTERVAL = 128

private const val STATUS_BAD_REQUEST = 400
private const val STATUS_UNAUTHORIZED = 401
private const val STATUS_FORBIDDEN = 403
private const val STATUS_UNSUPPORTED_MEDIA_TYPE = 415
private const val STATUS_UNPROCESSABLE_ENTITY = 422
private const val STATUS_INTERNAL_SERVER_ERROR = 500

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
        val openApiSchemasEnabled: Boolean = true,
        val apiErrorSchemaName: String = "ProblemDetail",
        val fieldErrorSchemaName: String = "FieldError",
        /**
         * Statuses attached to every operation. These are the failures any
         * request can meet regardless of shape: rejected or insufficient
         * credentials, and the catch-all server error. Security-derived
         * statuses are included by default because every service in the
         * estate sits behind authentication; a service that genuinely
         * exposes anonymous endpoints narrows this list rather than
         * annotating each operation.
         */
        val openApiUniversalErrorStatuses: List<Int> =
            listOf(STATUS_UNAUTHORIZED, STATUS_FORBIDDEN, STATUS_INTERNAL_SERVER_ERROR),
        /**
         * Statuses attached only to operations that accept a request body,
         * where a malformed, unsupported or invalid payload is reachable.
         * All three are emitted by this module's own [GlobalExceptionHandler],
         * so leaving them undeclared would keep the estate's own failures
         * undocumented.
         *
         * Declaring these on a bodyless GET would over-promise, which costs
         * more than it buys: it makes conformance checking unable to catch a
         * missing handler. Domain-specific codes a service returns beyond
         * this set still need their own `@ApiResponse`.
         */
        val openApiRequestBodyErrorStatuses: List<Int> =
            listOf(STATUS_BAD_REQUEST, STATUS_UNSUPPORTED_MEDIA_TYPE, STATUS_UNPROCESSABLE_ENTITY),
    )

    data class CsrfProperties(
        val enabled: Boolean = false,
        val path: String = "/csrf",
        val tokenField: String = "token",
    )

    data class RateLimitProperties(
        val enabled: Boolean = false,
        val maxBuckets: Int = DEFAULT_RATE_LIMIT_MAX_BUCKETS,
        val bucketIdleTtl: Duration = Duration.ofMinutes(DEFAULT_RATE_LIMIT_BUCKET_IDLE_TTL_MINUTES),
        val cleanupInterval: Int = DEFAULT_RATE_LIMIT_CLEANUP_INTERVAL,
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
