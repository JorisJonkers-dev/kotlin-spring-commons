package com.jorisjonkers.personalstack.common.messaging

import com.jorisjonkers.personalstack.common.event.DomainEvent
import org.slf4j.LoggerFactory
import org.springframework.amqp.AmqpException
import org.springframework.amqp.rabbit.core.RabbitTemplate

class RabbitMqEventPublisher(
    private val rabbitTemplate: RabbitTemplate,
    private val properties: RabbitMqMessagingProperties,
) {
    private val log = LoggerFactory.getLogger(RabbitMqEventPublisher::class.java)

    fun publish(
        routingKey: String,
        event: DomainEvent,
    ) {
        try {
            rabbitTemplate.convertAndSend(properties.exchange, routingKey, event)
            log.debug("Published event {} to routing key {}", event::class.simpleName, routingKey)
        } catch (e: AmqpException) {
            log.error("Failed to publish event {} to RabbitMQ", event::class.simpleName, e)
            throw e
        }
    }
}
