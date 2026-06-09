package com.jorisjonkers.personalstack.common.messaging

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = RabbitMqMessagingProperties.PREFIX)
class RabbitMqMessagingProperties {
    var enabled: Boolean = true
    var exchange: String = "application.events"
    var deadLetterExchange: String = "application.events.dlx"
    var bindings: MutableMap<String, RabbitMqBindingProperties> =
        linkedMapOf(
            "user-registered" to
                RabbitMqBindingProperties(
                    queue = "auth.user-registered",
                    routingKey = "auth.user.registered",
                    deadLetterQueue = "auth.user-registered.dlq",
                ),
        )

    companion object {
        const val PREFIX = "extratoast.messaging"
    }
}

class RabbitMqBindingProperties(
    var queue: String = "",
    var routingKey: String = "",
    var deadLetterQueue: String? = null,
)
