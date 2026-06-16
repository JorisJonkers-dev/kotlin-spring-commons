package com.jorisjonkers.personalstack.common.web

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.FilteredClassLoader
import org.springframework.boot.test.context.runner.ApplicationContextRunner

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
}
