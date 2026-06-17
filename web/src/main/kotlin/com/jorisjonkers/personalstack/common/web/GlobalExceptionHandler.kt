package com.jorisjonkers.personalstack.common.web

import com.jorisjonkers.personalstack.common.exception.DomainException
import com.jorisjonkers.personalstack.common.exception.NotFoundException
import jakarta.validation.ConstraintViolationException
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.EmptyResultDataAccessException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.HttpMediaTypeNotSupportedException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.context.request.WebRequest
import org.springframework.web.method.annotation.HandlerMethodValidationException
import java.net.URI

/**
 * Translates exceptions thrown by controllers / command handlers
 * into RFC 7807 [ProblemDetail] payloads.
 *
 * The mapping intent:
 *
 * * `NotFoundException`, `NoSuchElementException`,
 *   `EmptyResultDataAccessException` → 404 with the missing
 *   resource type + id in `detail`.
 * * `DomainException` → 400 with a `type` URI derived from the
 *   exception's `code` so clients can switch on it.
 * * `IllegalArgumentException` → 400 with the message bubbled
 *   through. Command handlers raise this when an inbound request
 *   is structurally valid but business-invalid.
 * * `IllegalStateException` → 409 — the request was structurally
 *   valid but the system is in a state that can't service it
 *   (e.g. Vault not configured, repository feature disabled).
 * * `MethodArgumentNotValidException` /
 *   `HandlerMethodValidationException` /
 *   `ConstraintViolationException` → 422 with a `violations`
 *   list carrying field-level (path, message, rejectedValue).
 * * `HttpMessageNotReadableException` /
 *   `HttpMediaTypeNotSupportedException` → 400 / 415.
 * * Any other `Exception` → 500 with the exception class name +
 *   first useful line of `message` (truncated to ~500 chars) +
 *   the MDC `traceId` so support can correlate to logs.
 *
 * Subclasses (`@Order` higher) may handle integration-specific
 * exceptions (Fabric8's `KubernetesClientException`, vault errors)
 * before the generic chain runs.
 */
@RestControllerAdvice
@Suppress("TooManyFunctions", "LargeClass")
open class GlobalExceptionHandler {
    private val log = LoggerFactory.getLogger(GlobalExceptionHandler::class.java)

    @ExceptionHandler(NotFoundException::class)
    fun handleNotFound(
        ex: NotFoundException,
        request: WebRequest?,
    ): ResponseEntity<ProblemDetail> {
        logClientError(ex, request, HttpStatus.NOT_FOUND)
        val body =
            problem(
                type = ProblemTypes.named("not-found"),
                title = "Resource Not Found",
                status = HttpStatus.NOT_FOUND,
                detail = ex.message,
                request = request,
            )
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body)
    }

    @ExceptionHandler(NoSuchElementException::class)
    fun handleNoSuchElement(
        ex: NoSuchElementException,
        request: WebRequest?,
    ): ResponseEntity<ProblemDetail> {
        logClientError(ex, request, HttpStatus.NOT_FOUND)
        val body =
            problem(
                type = ProblemTypes.named("not-found"),
                title = "Resource Not Found",
                status = HttpStatus.NOT_FOUND,
                detail = ex.message ?: "The referenced resource does not exist",
                request = request,
            )
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body)
    }

    @ExceptionHandler(EmptyResultDataAccessException::class)
    fun handleEmptyResultDataAccess(
        ex: EmptyResultDataAccessException,
        request: WebRequest?,
    ): ResponseEntity<ProblemDetail> {
        logClientError(ex, request, HttpStatus.NOT_FOUND)
        val body =
            problem(
                type = ProblemTypes.named("not-found"),
                title = "Resource Not Found",
                status = HttpStatus.NOT_FOUND,
                detail = ex.message ?: "No matching record",
                request = request,
            )
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body)
    }

    @ExceptionHandler(DomainException::class)
    fun handleDomain(
        ex: DomainException,
        request: WebRequest?,
    ): ResponseEntity<ProblemDetail> {
        logClientError(ex, request, HttpStatus.BAD_REQUEST)
        val body =
            problem(
                type =
                    ProblemTypes.named(ex.code),
                title =
                    ex.code
                        .replace('_', ' ')
                        .lowercase()
                        .replaceFirstChar { it.uppercaseChar() },
                status = HttpStatus.BAD_REQUEST,
                detail = ex.message,
                request = request,
            )
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body)
    }

    @ExceptionHandler(IllegalArgumentException::class)
    fun handleIllegalArgument(
        ex: IllegalArgumentException,
        request: WebRequest?,
    ): ResponseEntity<ProblemDetail> {
        logClientError(ex, request, HttpStatus.BAD_REQUEST)
        val body =
            problem(
                type = ProblemTypes.named("bad-request"),
                title = "Bad Request",
                status = HttpStatus.BAD_REQUEST,
                detail = ex.message ?: "Invalid request",
                request = request,
            )
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body)
    }

    @ExceptionHandler(IllegalStateException::class)
    fun handleIllegalState(
        ex: IllegalStateException,
        request: WebRequest?,
    ): ResponseEntity<ProblemDetail> {
        logClientError(ex, request, HttpStatus.CONFLICT)
        val body =
            problem(
                type = ProblemTypes.named("conflict"),
                title = "Conflict",
                status = HttpStatus.CONFLICT,
                detail = ex.message ?: "Request conflicts with current state",
                request = request,
            )
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body)
    }

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidation(
        ex: MethodArgumentNotValidException,
        request: WebRequest?,
    ): ResponseEntity<ProblemDetail> {
        val fieldErrors =
            ex.bindingResult.fieldErrors.map { error ->
                FieldError(
                    field = error.field,
                    message = error.defaultMessage ?: "Invalid value",
                    rejectedValue = error.rejectedValue,
                )
            }
        logClientError(ex, request, HttpStatus.UNPROCESSABLE_ENTITY)
        val body =
            problem(
                type = ProblemTypes.named("validation-error"),
                title = "Validation Error",
                status = HttpStatus.UNPROCESSABLE_ENTITY,
                detail = "One or more fields failed validation",
                request = request,
                errors = fieldErrors,
            )
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(body)
    }

    @ExceptionHandler(HandlerMethodValidationException::class)
    fun handleHandlerMethodValidation(
        ex: HandlerMethodValidationException,
        request: WebRequest?,
    ): ResponseEntity<ProblemDetail> {
        // Field-level detail is carried by handleValidation(MethodArgumentNotValidException)
        // for the common @Valid @RequestBody path. HandlerMethodValidationException is the
        // Spring-method-validation fallback; the per-parameter result API varies across
        // Spring versions, so this handler guarantees the 422 contract without depending on it.
        val fieldErrors = emptyList<FieldError>()
        logClientError(ex, request, HttpStatus.UNPROCESSABLE_ENTITY)
        val body =
            problem(
                type = ProblemTypes.named("validation-error"),
                title = "Validation Error",
                status = HttpStatus.UNPROCESSABLE_ENTITY,
                detail = "One or more fields failed validation",
                request = request,
                errors = fieldErrors,
            )
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(body)
    }

    @ExceptionHandler(ConstraintViolationException::class)
    fun handleConstraintViolation(
        ex: ConstraintViolationException,
        request: WebRequest?,
    ): ResponseEntity<ProblemDetail> {
        val fieldErrors =
            ex.constraintViolations.map { v ->
                FieldError(
                    field = v.propertyPath.toString(),
                    message = v.message ?: "Invalid value",
                    rejectedValue = v.invalidValue,
                )
            }
        logClientError(ex, request, HttpStatus.UNPROCESSABLE_ENTITY)
        val body =
            problem(
                type = ProblemTypes.named("validation-error"),
                title = "Validation Error",
                status = HttpStatus.UNPROCESSABLE_ENTITY,
                detail = "One or more parameters failed validation",
                request = request,
                errors = fieldErrors,
            )
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(body)
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException::class)
    fun handleMediaTypeNotSupported(
        ex: HttpMediaTypeNotSupportedException,
        request: WebRequest?,
    ): ResponseEntity<ProblemDetail> {
        logClientError(ex, request, HttpStatus.UNSUPPORTED_MEDIA_TYPE)
        val body =
            problem(
                type = ProblemTypes.named("unsupported-media-type"),
                title = "Unsupported Media Type",
                status = HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                detail = ex.message,
                request = request,
            )
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE).body(body)
    }

    @ExceptionHandler(DataIntegrityViolationException::class)
    @Suppress("LongMethod")
    fun handleDataIntegrity(
        ex: DataIntegrityViolationException,
        request: WebRequest?,
    ): ResponseEntity<ProblemDetail> {
        val info = PostgresConstraintParser.parse(ex)
        val detail =
            info.detail
                ?: "Database constraint violation: ${ex.mostSpecificCause.message ?: "no message"}"
        logClientError(ex, request, HttpStatus.UNPROCESSABLE_ENTITY)
        val body =
            problem(
                type = ProblemTypes.named("constraint-violation"),
                title = "Constraint violation",
                status = HttpStatus.UNPROCESSABLE_ENTITY,
                detail = detail,
                request = request,
                errors = info.column?.let { listOf(FieldError(field = it, message = info.shortMessage())) }.orEmpty(),
                constraint = info.constraint,
                column = info.column,
                referencedTable = info.referencedTable,
            )
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(body)
    }

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleMessageNotReadable(
        ex: HttpMessageNotReadableException,
        request: WebRequest?,
    ): ResponseEntity<ProblemDetail> {
        log.debug("Unreadable request body: {}", ex.message)
        val body =
            problem(
                type = ProblemTypes.named("bad-request"),
                title = "Bad Request",
                status = HttpStatus.BAD_REQUEST,
                detail = "Malformed or unreadable request body",
                request = request,
            )
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body)
    }

    @ExceptionHandler(Exception::class)
    fun handleUnexpected(
        ex: Exception,
        request: WebRequest?,
    ): ResponseEntity<ProblemDetail> {
        val traceId = currentTraceId()
        val summary = exceptionSummary(ex)
        log.error(
            "Unhandled exception traceId={} path={} exception={} message={}",
            traceId,
            requestPath(request),
            ex.javaClass.name,
            summary,
            ex,
        )
        val body =
            problem(
                type = ProblemTypes.named("internal-error"),
                title = "Internal Server Error",
                status = HttpStatus.INTERNAL_SERVER_ERROR,
                detail = "${ex.javaClass.simpleName}: $summary",
                request = request,
                traceId = traceId,
                exception = ex.javaClass.name,
            )
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body)
    }

    /**
     * Shared field-population helper. Keeping it `protected` so the
     * fabric8 / vault-specific subclasses in downstream services can
     * lean on the same envelope shape (instance URI + traceId
     * extraction) without duplicating the boilerplate.
     */
    @Suppress("LongParameterList")
    protected fun problem(
        type: URI,
        title: String,
        status: HttpStatus,
        detail: String?,
        request: WebRequest?,
        errors: List<FieldError> = emptyList(),
        traceId: String? = null,
        exception: String? = null,
        kubernetesCode: Int? = null,
        kubernetesReason: String? = null,
        constraint: String? = null,
        column: String? = null,
        referencedTable: String? = null,
    ): ProblemDetail =
        ProblemDetail(
            type = type,
            title = title,
            status = status.value(),
            detail = detail,
            instance = requestPath(request)?.let(URI::create),
            errors = errors,
            traceId = traceId,
            exception = exception,
            kubernetesCode = kubernetesCode,
            kubernetesReason = kubernetesReason,
            constraint = constraint,
            column = column,
            referencedTable = referencedTable,
        )

    protected fun currentTraceId(): String? = MDC.get("traceId") ?: MDC.get("trace_id")

    protected fun requestPath(request: WebRequest?): String? =
        when (request) {
            null -> null
            else ->
                runCatching {
                    request
                        .getDescription(false)
                        .removePrefix("uri=")
                        .takeIf { it.isNotBlank() }
                }.getOrNull()
        }

    private fun logClientError(
        ex: Exception,
        request: WebRequest?,
        status: HttpStatus,
    ) {
        if (log.isDebugEnabled) {
            log.debug(
                "client error traceId={} path={} status={} exception={} message={}",
                currentTraceId(),
                requestPath(request),
                status.value(),
                ex.javaClass.simpleName,
                ex.message,
            )
        }
    }

    private fun exceptionSummary(ex: Exception): String {
        val raw = ex.message ?: ex.cause?.message ?: "no message"
        // First non-blank line, with a generous 500 char cap so log
        // hash searches still match production payloads.
        val firstLine = raw.lineSequence().firstOrNull { it.isNotBlank() } ?: raw
        return if (firstLine.length > MAX_DETAIL_LENGTH) {
            firstLine.take(MAX_DETAIL_LENGTH) + "…"
        } else {
            firstLine
        }
    }

    companion object {
        private const val MAX_DETAIL_LENGTH = 500
    }
}
