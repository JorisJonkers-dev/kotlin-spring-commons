package com.jorisjonkers.personalstack.common.messaging

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class RabbitMqMessagingPropertiesTest {
    @Test
    fun `defaults use generic topology names`() {
        val properties = RabbitMqMessagingProperties()

        assertThat(properties.enabled).isTrue()
        assertThat(properties.exchange).isEqualTo("application.events")
        assertThat(properties.deadLetterExchange).isEqualTo("application.events.dlx")
        assertThat(properties.bindings).containsOnlyKeys("user-registered")

        val binding = properties.bindings.getValue("user-registered")
        assertThat(binding.queue).isEqualTo("auth.user-registered")
        assertThat(binding.routingKey).isEqualTo("auth.user.registered")
        assertThat(binding.deadLetterQueue).isEqualTo("auth.user-registered.dlq")
    }

    @Test
    fun `bindings can be replaced with application-specific topology`() {
        val properties =
            RabbitMqMessagingProperties().apply {
                exchange = "service.events"
                deadLetterExchange = "service.events.dlx"
                bindings =
                    linkedMapOf(
                        "audit" to
                            RabbitMqBindingProperties(
                                queue = "audit.events",
                                routingKey = "audit.created",
                                deadLetterQueue = "audit.events.dlq",
                            ),
                    )
            }

        assertThat(properties.exchange).isEqualTo("service.events")
        assertThat(properties.deadLetterExchange).isEqualTo("service.events.dlx")
        assertThat(properties.bindings).containsOnlyKeys("audit")
        assertThat(properties.bindings.getValue("audit").routingKey).isEqualTo("audit.created")
    }
}
