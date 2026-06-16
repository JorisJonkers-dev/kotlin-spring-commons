package com.jorisjonkers.personalstack.common.identity

import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
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
            )
            .run { context ->
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
}
