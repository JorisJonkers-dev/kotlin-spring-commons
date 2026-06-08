package com.jorisjonkers.personalstack.common.timing

import org.assertj.core.api.Assertions.assertThat
import org.jooq.impl.DefaultExecuteListenerProvider
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.FilteredClassLoader
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class TimingAutoConfigurationTest {
    private val runner =
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(TimingAutoConfiguration::class.java))

    @Test
    fun `context loads without jOOQ on the classpath and skips the jooq listener`() {
        // The agent-gateway consumes kotlin-common but has no jOOQ
        // (compileOnly). Spring reflectively enumerates the config's
        // @Bean methods at startup; hosting the DefaultExecuteListenerProvider
        // bean on the top-level class made that enumeration resolve the
        // jOOQ return type and throw NoClassDefFoundError before the
        // @ConditionalOnClass guard was ever read, crashing the context.
        runner
            .withClassLoader(FilteredClassLoader(DefaultExecuteListenerProvider::class.java))
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).hasSingleBean(RequestTimingFilter::class.java)
                assertThat(context).doesNotHaveBean("jooqTimingExecuteListenerProvider")
            }
    }

    @Test
    fun `registers the jooq listener provider when jOOQ is present`() {
        runner.run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context).hasSingleBean(DefaultExecuteListenerProvider::class.java)
            assertThat(context).hasSingleBean(RequestTimingFilter::class.java)
        }
    }

    @Test
    fun `registers nothing when timing is disabled`() {
        runner
            .withPropertyValues("personal-stack.timing.enabled=false")
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).doesNotHaveBean(RequestTimingFilter::class.java)
                assertThat(context).doesNotHaveBean(DefaultExecuteListenerProvider::class.java)
            }
    }
}
