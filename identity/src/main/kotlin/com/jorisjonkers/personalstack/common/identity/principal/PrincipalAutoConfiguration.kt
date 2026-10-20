package com.jorisjonkers.personalstack.common.identity.principal

import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean

/** Where an app reads its Principals from: identity, and the app's own Client (and secret). */
@ConfigurationProperties(prefix = "jorisjonkers.identity.principal")
data class PrincipalProperties(
    val issuer: String = "",
    val clientId: String = "",
    val clientSecret: String? = null,
)

/**
 * The Principal reader, its authentication filter and its access-denied handler, once an app names
 * its Client. The app adds the filter to its SecurityFilterChain; it is kept out of the servlet
 * chain so it runs only there.
 */
@AutoConfiguration
@EnableConfigurationProperties(PrincipalProperties::class)
@ConditionalOnProperty("jorisjonkers.identity.principal.client-id")
open class PrincipalAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    open fun principalReader(properties: PrincipalProperties): PrincipalReader {
        require(properties.issuer.isNotBlank()) { "jorisjonkers.identity.principal.issuer must name identity" }
        return PrincipalReader(PrincipalReaderConfig(properties.issuer, properties.clientId, properties.clientSecret))
    }

    @Bean
    @ConditionalOnMissingBean
    open fun principalAuthenticationFilter(reader: PrincipalReader): PrincipalAuthenticationFilter =
        PrincipalAuthenticationFilter(reader)

    @Bean
    open fun principalAuthenticationFilterRegistration(
        filter: PrincipalAuthenticationFilter,
    ): FilterRegistrationBean<PrincipalAuthenticationFilter> =
        FilterRegistrationBean(filter).apply { isEnabled = false }

    @Bean
    @ConditionalOnMissingBean
    open fun principalAccessDeniedHandler(): PrincipalAccessDeniedHandler = PrincipalAccessDeniedHandler()
}
