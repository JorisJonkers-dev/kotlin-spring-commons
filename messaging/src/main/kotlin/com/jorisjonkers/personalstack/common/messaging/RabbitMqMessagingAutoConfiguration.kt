package com.jorisjonkers.personalstack.common.messaging

import org.springframework.amqp.core.BindingBuilder
import org.springframework.amqp.core.Declarable
import org.springframework.amqp.core.Declarables
import org.springframework.amqp.core.DirectExchange
import org.springframework.amqp.core.QueueBuilder
import org.springframework.amqp.rabbit.core.RabbitTemplate
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter
import org.springframework.amqp.support.converter.MessageConverter
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean

@AutoConfiguration
@ConditionalOnClass(RabbitTemplate::class, DirectExchange::class)
@ConditionalOnProperty(
    prefix = RabbitMqMessagingProperties.PREFIX,
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
@EnableConfigurationProperties(RabbitMqMessagingProperties::class)
class RabbitMqMessagingAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(name = ["rabbitMqEventsExchange"])
    fun rabbitMqEventsExchange(properties: RabbitMqMessagingProperties): DirectExchange =
        DirectExchange(properties.exchange, true, false)

    @Bean
    @ConditionalOnMissingBean(name = ["rabbitMqDeadLetterExchange"])
    fun rabbitMqDeadLetterExchange(properties: RabbitMqMessagingProperties): DirectExchange =
        DirectExchange(properties.deadLetterExchange, true, false)

    @Bean
    @ConditionalOnMissingBean(name = ["rabbitMqTopology"])
    fun rabbitMqTopology(
        properties: RabbitMqMessagingProperties,
        rabbitMqEventsExchange: DirectExchange,
    ): Declarables = Declarables(properties.toDeclarables(rabbitMqEventsExchange))

    @Bean
    @ConditionalOnMissingBean(MessageConverter::class)
    fun rabbitMqMessageConverter(): MessageConverter = JacksonJsonMessageConverter()

    @Bean
    @ConditionalOnBean(RabbitTemplate::class)
    @ConditionalOnMissingBean(RabbitMqEventPublisher::class)
    fun rabbitMqEventPublisher(
        rabbitTemplate: RabbitTemplate,
        properties: RabbitMqMessagingProperties,
    ): RabbitMqEventPublisher = RabbitMqEventPublisher(rabbitTemplate, properties)

    private fun RabbitMqMessagingProperties.toDeclarables(eventsExchange: DirectExchange): List<Declarable> {
        val declarables = mutableListOf<Declarable>()

        bindings.forEach { (name, binding) ->
            require(binding.queue.isNotBlank()) {
                "extratoast.messaging.bindings.$name.queue must not be blank"
            }
            require(binding.routingKey.isNotBlank()) {
                "extratoast.messaging.bindings.$name.routing-key must not be blank"
            }

            val queueBuilder = QueueBuilder.durable(binding.queue)
            val deadLetterQueue = binding.deadLetterQueue?.takeIf { it.isNotBlank() }
            if (deadLetterQueue != null) {
                queueBuilder
                    .withArgument("x-dead-letter-exchange", deadLetterExchange)
                    .withArgument("x-dead-letter-routing-key", deadLetterQueue)
                declarables += QueueBuilder.durable(deadLetterQueue).build()
            }

            val queue = queueBuilder.build()
            declarables += queue
            declarables += BindingBuilder.bind(queue).to(eventsExchange).with(binding.routingKey)
        }

        return declarables
    }
}
