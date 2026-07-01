package com.jorisjonkers.personalstack.common.identity

import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import java.time.Instant
import java.util.function.Supplier

class ForwardAuthIdentityAutoConfigurationTest {
    private val runner =
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ForwardAuthIdentityAutoConfiguration::class.java))

    @Test
    fun `registers identity beans when enabled`() {
        runner
            .withPropertyValues(
                "extratoast.identity.enabled=true",
                "extratoast.identity.jwks-uri=https://issuer.example/.well-known/jwks.json",
                "extratoast.identity.issuer=https://issuer.example",
                "extratoast.identity.audience=personal-stack",
            ).run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).hasSingleBean(ForwardAuthIdentityProperties::class.java)
                assertThat(context).hasSingleBean(JwtDecoder::class.java)
                assertThat(context).hasSingleBean(FilterRegistrationBean::class.java)
                assertThat(context).hasSingleBean(CurrentPrincipalArgumentResolver::class.java)
                assertThat(context).hasSingleBean(WebMvcConfigurer::class.java)

                val registration = context.getBean(FilterRegistrationBean::class.java)
                assertThat(registration.filter).isInstanceOf(ForwardAuthIdentityFilter::class.java)

                val resolver = context.getBean(CurrentPrincipalArgumentResolver::class.java)
                val configurer = context.getBean(WebMvcConfigurer::class.java)
                val argumentResolvers = mutableListOf<HandlerMethodArgumentResolver>()
                configurer.addArgumentResolvers(argumentResolvers)
                assertThat(argumentResolvers).containsExactly(resolver)
            }
    }

    @Test
    fun `does not register identity beans when enabled property is absent`() {
        runner.run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context).doesNotHaveBean(JwtDecoder::class.java)
            assertThat(context).doesNotHaveBean(FilterRegistrationBean::class.java)
            assertThat(context).doesNotHaveBean(CurrentPrincipalArgumentResolver::class.java)
            assertThat(context).doesNotHaveBean(WebMvcConfigurer::class.java)
        }
    }

    @Test
    fun `does not register identity beans when disabled`() {
        runner
            .withPropertyValues("extratoast.identity.enabled=false")
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).doesNotHaveBean(JwtDecoder::class.java)
                assertThat(context).doesNotHaveBean(FilterRegistrationBean::class.java)
                assertThat(context).doesNotHaveBean(CurrentPrincipalArgumentResolver::class.java)
                assertThat(context).doesNotHaveBean(WebMvcConfigurer::class.java)
            }
    }

    @Test
    fun `uses a user provided jwt decoder`() {
        val jwtDecoder = mockk<JwtDecoder>()

        runner
            .withPropertyValues("extratoast.identity.enabled=true")
            .withBean(JwtDecoder::class.java, Supplier { jwtDecoder })
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).hasSingleBean(JwtDecoder::class.java)
                assertThat(context.getBean(JwtDecoder::class.java)).isSameAs(jwtDecoder)
                assertThat(context).hasSingleBean(FilterRegistrationBean::class.java)
            }
    }

    @Test
    fun `jwt decoder requires a jwks uri when auto configured`() {
        val configuration = ForwardAuthIdentityAutoConfiguration()

        assertThatThrownBy {
            configuration.forwardAuthJwtDecoder(
                ForwardAuthIdentityProperties(
                    issuer = "https://issuer.example",
                ),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("extratoast.identity.jwks-uri must be configured when extratoast.identity.enabled=true")
    }

    @Test
    fun `jwt decoder requires an issuer when auto configured`() {
        val configuration = ForwardAuthIdentityAutoConfiguration()

        assertThatThrownBy {
            configuration.forwardAuthJwtDecoder(
                ForwardAuthIdentityProperties(
                    jwksUri = "https://issuer.example/.well-known/jwks.json",
                    issuer = " ",
                ),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("extratoast.identity.issuer must be configured when extratoast.identity.enabled=true")
    }

    @Test
    fun `audience validator accepts tokens with the configured audience`() {
        val result =
            audienceValidationResult(
                "personal-stack",
                jwt(audience = listOf("personal-stack", "other-api")),
            )

        assertThat(result.hasErrors()).isFalse()
    }

    @Test
    fun `audience validator rejects tokens without the configured audience`() {
        val result = audienceValidationResult("personal-stack", jwt(audience = listOf("other-api")))

        assertThat(result.hasErrors()).isTrue()
        val error = result.errors.single()
        assertThat(error.errorCode).isEqualTo("invalid_token")
        assertThat(error.description).isEqualTo("The required audience is missing.")
    }

    private fun audienceValidationResult(
        audience: String,
        jwt: Jwt,
    ): OAuth2TokenValidatorResult {
        val validatorClass =
            ForwardAuthIdentityAutoConfiguration::class.java.declaredClasses
                .single { it.simpleName == "AudienceValidator" }
        val constructor = validatorClass.getDeclaredConstructor(String::class.java).apply { isAccessible = true }
        val validator = constructor.newInstance(audience)
        val validate =
            validatorClass.methods.single { method ->
                method.name == "validate" &&
                    method.parameterCount == 1 &&
                    method.parameterTypes.single() == Jwt::class.java &&
                    method.returnType == OAuth2TokenValidatorResult::class.java
            }
        return validate.invoke(validator, jwt) as OAuth2TokenValidatorResult
    }

    private fun jwt(audience: List<String>): Jwt =
        Jwt
            .withTokenValue("token")
            .header("alg", "RS256")
            .subject("742780c2-6f21-4e2f-971a-29a6e5a3b8bb")
            .audience(audience)
            .issuedAt(Instant.parse("2026-06-16T00:00:00Z"))
            .expiresAt(Instant.parse("2026-06-16T00:05:00Z"))
            .build()
}
