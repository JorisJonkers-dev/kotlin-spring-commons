package com.jorisjonkers.personalstack.common.test.openapi

class OpenApiExportException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
