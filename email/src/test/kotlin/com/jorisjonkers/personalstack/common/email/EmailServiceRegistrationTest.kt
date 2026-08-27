package com.jorisjonkers.personalstack.common.email

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.mail.autoconfigure.MailSenderAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.Configuration

/**
 * EmailService has to actually exist in a context that component-scans this
 * package alongside Spring Boot's mail auto-configuration.
 *
 * It used to carry @ConditionalOnBean(JavaMailSender::class). Component scanning
 * registers bean definitions before auto-configuration contributes any, so the
 * condition saw no JavaMailSender and EmailService was silently never registered
 * -- which is how auth-api ended up never sending a confirmation or password
 * reset mail, with nothing logged above DEBUG.
 *
 * This test reproduces that arrangement: scan first, auto-configure after.
 */
class EmailServiceRegistrationTest {
    @Configuration
    @ComponentScan(basePackageClasses = [EmailService::class])
    open class ScannedConfig

    private val runner =
        ApplicationContextRunner()
            .withUserConfiguration(ScannedConfig::class.java)
            .withConfiguration(AutoConfigurations.of(MailSenderAutoConfiguration::class.java))

    @Test
    fun `is registered when the application scans this package and configures mail`() {
        runner
            .withPropertyValues("spring.mail.host=localhost", "spring.mail.port=587")
            .run { context ->
                assertThat(context).hasSingleBean(EmailService::class.java)
            }
    }

    @Test
    fun `fails loudly rather than silently when no mail sender is configured`() {
        // No spring.mail.host, so MailSenderAutoConfiguration contributes no
        // JavaMailSender. The context must fail to start rather than quietly
        // come up without the ability to send email.
        runner.run { context ->
            assertThat(context).hasFailed()
        }
    }
}
