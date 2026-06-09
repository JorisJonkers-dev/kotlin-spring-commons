@file:Suppress("DEPRECATION")

package com.jorisjonkers.personalstack.common.messaging

import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.amqp.core.Binding
import org.springframework.amqp.core.Declarables
import org.springframework.amqp.core.DirectExchange
import org.springframework.amqp.core.Queue
import org.springframework.amqp.rabbit.core.RabbitTemplate
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter
import org.springframework.amqp.support.converter.MessageConverter
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.util.function.Supplier

class RabbitMqMessagingAutoConfigurationTest {
    private val runner =
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(RabbitMqMessagingAutoConfiguration::class.java))
            .withBean(RabbitTemplate::class.java, Supplier { mockk(relaxed = true) })

    @Test
    fun `registers default generic topology from properties`() {
        runner.run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context).hasSingleBean(RabbitMqMessagingProperties::class.java)
            assertThat(context).hasSingleBean(Declarables::class.java)
            assertThat(context).hasSingleBean(RabbitMqEventPublisher::class.java)
            assertThat(context).hasSingleBean(MessageConverter::class.java)
            assertThat(context.getBean(MessageConverter::class.java))
                .isInstanceOf(Jackson2JsonMessageConverter::class.java)

            val eventsExchange = context.getBean("rabbitMqEventsExchange", DirectExchange::class.java)
            val deadLetterExchange = context.getBean("rabbitMqDeadLetterExchange", DirectExchange::class.java)
            assertThat(eventsExchange.name).isEqualTo("application.events")
            assertThat(deadLetterExchange.name).isEqualTo("application.events.dlx")

            val topology = context.getBean(Declarables::class.java)
            val queues = topology.getDeclarablesByType(Queue::class.java).associateBy { it.name }
            assertThat(queues).containsOnlyKeys("auth.user-registered", "auth.user-registered.dlq")
            assertThat(queues.getValue("auth.user-registered").arguments)
                .containsEntry("x-dead-letter-exchange", "application.events.dlx")
                .containsEntry("x-dead-letter-routing-key", "auth.user-registered.dlq")

            val binding = topology.getDeclarablesByType(Binding::class.java).single()
            assertThat(binding.exchange).isEqualTo("application.events")
            assertThat(binding.destination).isEqualTo("auth.user-registered")
            assertThat(binding.routingKey).isEqualTo("auth.user.registered")
        }
    }

    @Test
    fun `registers overridden topology from configuration properties`() {
        runner
            .withPropertyValues(
                "extratoast.messaging.exchange=service.events",
                "extratoast.messaging.dead-letter-exchange=service.events.dlx",
                "extratoast.messaging.bindings.user-registered.queue=service.user-registered",
                "extratoast.messaging.bindings.user-registered.routing-key=service.user.registered",
                "extratoast.messaging.bindings.user-registered.dead-letter-queue=service.user-registered.dlq",
            )
            .run { context ->
                assertThat(context).hasNotFailed()

                val eventsExchange = context.getBean("rabbitMqEventsExchange", DirectExchange::class.java)
                val deadLetterExchange = context.getBean("rabbitMqDeadLetterExchange", DirectExchange::class.java)
                assertThat(eventsExchange.name).isEqualTo("service.events")
                assertThat(deadLetterExchange.name).isEqualTo("service.events.dlx")

                val topology = context.getBean(Declarables::class.java)
                val queues = topology.getDeclarablesByType(Queue::class.java).associateBy { it.name }
                assertThat(queues).containsOnlyKeys("service.user-registered", "service.user-registered.dlq")

                val binding = topology.getDeclarablesByType(Binding::class.java).single()
                assertThat(binding.exchange).isEqualTo("service.events")
                assertThat(binding.destination).isEqualTo("service.user-registered")
                assertThat(binding.routingKey).isEqualTo("service.user.registered")
            }
    }

    @Test
    fun `skips messaging beans when disabled`() {
        runner
            .withPropertyValues("extratoast.messaging.enabled=false")
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).doesNotHaveBean(Declarables::class.java)
                assertThat(context).doesNotHaveBean(RabbitMqEventPublisher::class.java)
                assertThat(context).doesNotHaveBean("rabbitMqEventsExchange")
            }
    }
}
