package com.jorisjonkers.personalstack.common.web

import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.ConstraintViolation
import jakarta.validation.ConstraintViolationException
import org.slf4j.MDC
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.net.URI

@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
class ValidationProblemDetailsAdvice(
    private val properties: WebUtilitiesProperties.ProblemDetailsProperties =
        WebUtilitiesProperties
            .ProblemDetailsProperties(),
) {
    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleMethodArgumentNotValid(
        ex: MethodArgumentNotValidException,
        request: HttpServletRequest,
    ): org.springframework.http.ProblemDetail {
        val errors =
            ex.bindingResult.fieldErrors.map {
                validationError(
                    objectName = it.objectName,
                    field = it.field,
                    message = it.defaultMessage,
                    code = it.code,
                    rejectedValue = it.rejectedValue,
                )
            } +
                ex.bindingResult.globalErrors.map {
                    validationError(
                        objectName = it.objectName,
                        field = null,
                        message = it.defaultMessage,
                        code = it.code,
                        rejectedValue = null,
                    )
                }
        return problem(request, errors)
    }

    @ExceptionHandler(ConstraintViolationException::class)
    fun handleConstraintViolation(
        ex: ConstraintViolationException,
        request: HttpServletRequest,
    ): org.springframework.http.ProblemDetail {
        val errors = ex.constraintViolations.map(::constraintViolationError)
        return problem(request, errors)
    }

    private fun problem(
        request: HttpServletRequest,
        errors: List<Map<String, Any?>>,
    ): org.springframework.http.ProblemDetail {
        val status = HttpStatus.valueOf(properties.validationStatus)
        val detail =
            org.springframework.http.ProblemDetail.forStatusAndDetail(
                status,
                properties.validationDetail,
            )
        detail.type = URI.create("about:blank")
        detail.title = status.reasonPhrase
        detail.instance = URI.create(request.requestURI)
        detail.setProperty("errors", errors)
        MDC.get("traceId")?.let { detail.setProperty("traceId", it) }
        return detail
    }

    private fun constraintViolationError(violation: ConstraintViolation<*>): Map<String, Any?> =
        validationError(
            objectName = violation.rootBeanClass.simpleName,
            field = violation.propertyPath.toString(),
            message = violation.message,
            code = violation.constraintDescriptor.annotation.annotationClass.simpleName,
            rejectedValue = violation.invalidValue,
        )

    private fun validationError(
        objectName: String?,
        field: String?,
        message: String?,
        code: String?,
        rejectedValue: Any?,
    ): Map<String, Any?> =
        linkedMapOf(
            "objectName" to objectName,
            "field" to field,
            "message" to message,
            "code" to code,
            "rejectedValue" to rejectedValue,
        )
}
