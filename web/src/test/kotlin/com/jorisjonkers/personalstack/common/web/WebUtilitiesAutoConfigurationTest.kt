package com.jorisjonkers.personalstack.common.web

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.FilteredClassLoader
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.security.web.csrf.DefaultCsrfToken
import java.time.Duration

class WebUtilitiesAutoConfigurationTest {
    private val contextRunner =
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(WebUtilitiesAutoConfiguration::class.java))

    @Test
    fun `loads without springdoc on the classpath`() {
        contextRunner
            .withClassLoader(FilteredClassLoader("org.springdoc", "io.swagger"))
            .withPropertyValues("extratoast.web.problem-details.open-api-schemas-enabled=true")
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).doesNotHaveBean("openApiErrorSchemas")
            }
    }

    @Test
    fun `creates open api error schemas when springdoc is present and enabled`() {
        contextRunner
            .withPropertyValues("extratoast.web.problem-details.open-api-schemas-enabled=true")
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).hasBean("openApiErrorSchemas")
                assertThat(context).hasSingleBean(OpenApiErrorSchemas::class.java)
            }
    }

    @Test
    fun `factory methods create configured web utility beans`() {
        val properties =
            WebUtilitiesProperties(
                problemDetails = WebUtilitiesProperties.ProblemDetailsProperties(validationStatus = 422),
                csrf = WebUtilitiesProperties.CsrfProperties(tokenField = "csrfToken"),
                rateLimit =
                    WebUtilitiesProperties.RateLimitProperties(
                        maxBuckets = 1,
                        bucketIdleTtl = Duration.ofMinutes(5),
                        cleanupInterval = 1,
                        trustedProxyCidrs = listOf("127.0.0.1/32"),
                        rules =
                            listOf(
                                WebUtilitiesProperties.RouteRateLimitRule(
                                    pathPattern = "/auth/**",
                                    maxRequests = 1,
                                    window = Duration.ofSeconds(10),
                                ),
                            ),
                    ),
            )
        val configuration = WebUtilitiesAutoConfiguration()

        val advice = configuration.validationProblemDetailsAdvice(properties)
        val csrfController = configuration.csrfTokenController(properties)
        val limiter = configuration.inMemoryRequestRateLimiter(properties)
        val filter = configuration.publicAuthRateLimitFilter(limiter, properties)

        assertThat(advice).isInstanceOf(ValidationProblemDetailsAdvice::class.java)
        assertThat(csrfController.csrf(DefaultCsrfToken("X-XSRF-TOKEN", "_csrf", "raw")))
            .containsEntry("csrfToken", "raw")
        limiter.tryAcquire("first", 1, Duration.ofSeconds(1))
        limiter.tryAcquire("second", 1, Duration.ofSeconds(1))
        assertThat(limiter.trackedBucketCount()).isEqualTo(1)
        val request =
            MockHttpServletRequest("POST", "/auth/login").apply {
                servletPath = "/auth/login"
                remoteAddr = "127.0.0.1"
                addHeader("X-Forwarded-For", "198.51.100.10")
            }
        assertThat(filter.resolveClientIp(request)).isEqualTo("198.51.100.10")
    }

    @Test
    fun `nested open api schema factory creates configured customizer`() {
        val customizer =
            WebUtilitiesAutoConfiguration
                .OpenApiErrorSchemasConfiguration()
                .openApiErrorSchemas(
                    WebUtilitiesProperties(
                        problemDetails =
                            WebUtilitiesProperties.ProblemDetailsProperties(
                                apiErrorSchemaName = "Problem",
                                fieldErrorSchemaName = "ProblemField",
                            ),
                    ),
                )
        val openApi =
            io.swagger.v3.oas.models
                .OpenAPI()

        customizer.customise(openApi)

        assertThat(openApi.components.schemas).containsKeys("Problem", "ProblemField")
    }
}
