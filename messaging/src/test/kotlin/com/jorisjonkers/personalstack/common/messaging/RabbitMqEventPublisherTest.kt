package com.jorisjonkers.personalstack.common.messaging

import com.jorisjonkers.personalstack.common.event.DomainEvent
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.amqp.AmqpException
import org.springframework.amqp.rabbit.core.RabbitTemplate
import java.time.Instant

class RabbitMqEventPublisherTest {
    private val rabbitTemplate = mockk<RabbitTemplate>(relaxed = true)
    private val properties =
        RabbitMqMessagingProperties().apply {
            exchange = "configured.events"
        }
    private val publisher = RabbitMqEventPublisher(rabbitTemplate, properties)

    private data class TestEvent(
        val id: String,
        override val occurredAt: Instant = Instant.now(),
    ) : DomainEvent

    @Test
    fun `publish sends event to configured exchange with routing key`() {
        val event = TestEvent(id = "test-123")

        publisher.publish("test.routing.key", event)

        verify {
            rabbitTemplate.convertAndSend(
                "configured.events",
                "test.routing.key",
                event,
            )
        }
    }

    @Test
    fun `publish propagates amqp exceptions`() {
        val event = TestEvent(id = "fail-test")
        every {
            rabbitTemplate.convertAndSend(any<String>(), any<String>(), any<Any>())
        } throws AmqpException("Connection refused")

        assertThatThrownBy {
            publisher.publish("test.key", event)
        }.isInstanceOf(AmqpException::class.java)
            .hasMessageContaining("Connection refused")
    }
}
