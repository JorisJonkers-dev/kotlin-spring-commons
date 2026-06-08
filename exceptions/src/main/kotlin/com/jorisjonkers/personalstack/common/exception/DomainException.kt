package com.jorisjonkers.personalstack.common.exception

open class DomainException(
    message: String,
    val code: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
