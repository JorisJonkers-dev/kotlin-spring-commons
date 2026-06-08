package com.jorisjonkers.personalstack.common.event

import java.time.Instant

interface DomainEvent {
    val occurredAt: Instant
}
