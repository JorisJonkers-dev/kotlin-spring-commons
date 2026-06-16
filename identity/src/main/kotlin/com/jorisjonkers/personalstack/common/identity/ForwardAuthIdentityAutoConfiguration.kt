package com.jorisjonkers.personalstack.common.identity

import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator
import org.springframework.security.oauth2.core.OAuth2Error
import org.springframework.security.oauth2.core.OAuth2TokenValidator
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtValidators
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

@AutoConfiguration
@EnableConfigurationProperties(ForwardAuthIdentityProperties::class)
@ConditionalOnProperty("extratoast.identity.enabled", havingValue = "true")
open class ForwardAuthIdentityAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    open fun forwardAuthJwtDecoder(properties: ForwardAuthIdentityProperties): JwtDecoder {
        val jwksUri =
            requireNotNull(properties.jwksUri?.takeIf { it.isNotBlank() }) {
                "extratoast.identity.jwks-uri must be configured when extratoast.identity.enabled=true"
            }
        val issuer =
            requireNotNull(properties.issuer?.takeIf { it.isNotBlank() }) {
                "extratoast.identity.issuer must be configured when extratoast.identity.enabled=true"
            }
        val validators = mutableListOf<OAuth2TokenValidator<Jwt>>(JwtValidators.createDefaultWithIssuer(issuer))
        properties.audience
            ?.takeIf { it.isNotBlank() }
            ?.let { validators += AudienceValidator(it) }

        return NimbusJwtDecoder
            .withJwkSetUri(jwksUri)
            .build()
            .apply {
                setJwtValidator(DelegatingOAuth2TokenValidator(validators))
            }
    }

    @Bean
    @ConditionalOnMissingBean(ForwardAuthIdentityFilter::class)
    open fun forwardAuthIdentityFilterRegistration(
        properties: ForwardAuthIdentityProperties,
        jwtDecoder: JwtDecoder,
    ): FilterRegistrationBean<ForwardAuthIdentityFilter> =
        FilterRegistrationBean(
            ForwardAuthIdentityFilter(
                properties = properties,
                jwtDecoder = jwtDecoder,
            ),
        )

    @Bean
    @ConditionalOnMissingBean(CurrentPrincipalArgumentResolver::class)
    open fun currentPrincipalArgumentResolver(): CurrentPrincipalArgumentResolver =
        CurrentPrincipalArgumentResolver()

    @Bean
    open fun currentPrincipalWebMvcConfigurer(
        resolver: CurrentPrincipalArgumentResolver,
    ): WebMvcConfigurer =
        object : WebMvcConfigurer {
            override fun addArgumentResolvers(resolvers: MutableList<HandlerMethodArgumentResolver>) {
                resolvers += resolver
            }
        }

    private class AudienceValidator(
        private val audience: String,
    ) : OAuth2TokenValidator<Jwt> {
        override fun validate(token: Jwt): OAuth2TokenValidatorResult =
            if (token.audience.contains(audience)) {
                OAuth2TokenValidatorResult.success()
            } else {
                OAuth2TokenValidatorResult.failure(
                    OAuth2Error(
                        "invalid_token",
                        "The required audience is missing.",
                        null,
                    ),
                )
            }
    }
}
