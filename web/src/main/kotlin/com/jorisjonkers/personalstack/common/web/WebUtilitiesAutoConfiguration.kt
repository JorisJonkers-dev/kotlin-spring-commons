package com.jorisjonkers.personalstack.common.web

import org.springdoc.core.customizers.OpenApiCustomizer
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.security.web.csrf.CsrfToken

@AutoConfiguration
@EnableConfigurationProperties(WebUtilitiesProperties::class)
open class WebUtilitiesAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty("extratoast.web.problem-details.validation-enabled", havingValue = "true")
    open fun validationProblemDetailsAdvice(properties: WebUtilitiesProperties): ValidationProblemDetailsAdvice =
        ValidationProblemDetailsAdvice(properties.problemDetails)

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnClass(OpenApiCustomizer::class)
    @ConditionalOnProperty("extratoast.web.problem-details.open-api-schemas-enabled", havingValue = "true")
    open fun openApiErrorSchemas(properties: WebUtilitiesProperties): OpenApiErrorSchemas =
        OpenApiErrorSchemas(properties.problemDetails)

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnClass(CsrfToken::class)
    @ConditionalOnProperty("extratoast.web.csrf.enabled", havingValue = "true")
    open fun csrfTokenController(properties: WebUtilitiesProperties): CsrfTokenController =
        CsrfTokenController(properties.csrf)

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty("extratoast.web.rate-limit.enabled", havingValue = "true")
    open fun inMemoryRequestRateLimiter(properties: WebUtilitiesProperties): InMemoryRequestRateLimiter =
        InMemoryRequestRateLimiter(
            maxBuckets = properties.rateLimit.maxBuckets,
            bucketIdleTtl = properties.rateLimit.bucketIdleTtl,
            cleanupInterval = properties.rateLimit.cleanupInterval,
        )

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty("extratoast.web.rate-limit.enabled", havingValue = "true")
    open fun publicAuthRateLimitFilter(
        limiter: InMemoryRequestRateLimiter,
        properties: WebUtilitiesProperties,
    ): PublicAuthRateLimitFilter =
        PublicAuthRateLimitFilter(
            limiter = limiter,
            rules = properties.rateLimit.rules,
            trustedProxyCidrs = properties.rateLimit.trustedProxyCidrs,
        )
}
