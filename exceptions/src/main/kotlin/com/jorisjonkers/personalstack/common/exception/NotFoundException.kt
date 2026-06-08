package com.jorisjonkers.personalstack.common.exception

class NotFoundException(
    entity: String,
    id: String,
) : DomainException("$entity not found: $id", "NOT_FOUND")
