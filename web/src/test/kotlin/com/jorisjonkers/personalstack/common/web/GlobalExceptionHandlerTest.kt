package com.jorisjonkers.personalstack.common.web

import com.jorisjonkers.personalstack.common.exception.DomainException
import com.jorisjonkers.personalstack.common.exception.NotFoundException
import io.mockk.every
import io.mockk.mockk
import jakarta.validation.ConstraintViolation
import jakarta.validation.ConstraintViolationException
import jakarta.validation.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.slf4j.MDC
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.EmptyResultDataAccessException
import org.springframework.http.HttpStatus
import org.springframework.validation.BindingResult
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.context.request.WebRequest
import org.springframework.web.method.annotation.HandlerMethodValidationException
import java.net.URI
import org.springframework.validation.FieldError as SpringFieldError

class GlobalExceptionHandlerTest {
    private val handler = GlobalExceptionHandler()

    private fun webRequest(path: String = "/api/v1/test"): WebRequest =
        mockk<WebRequest>().also {
            every { it.getDescription(false) } returns "uri=$path"
        }

    @Test
    fun `handleNotFound returns 404 with correct ProblemDetail`() {
        val ex = NotFoundException("User", "123")

        val response = handler.handleNotFound(ex, webRequest("/api/v1/users/123"))

        assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
        val body = response.body!!
        assertThat(body.status).isEqualTo(404)
        assertThat(body.title).isEqualTo("Resource Not Found")
        assertThat(body.detail).isEqualTo("User not found: 123")
        assertThat(body.type).isEqualTo(URI.create("urn:problem-type:not-found"))
        assertThat(body.instance).isEqualTo(URI.create("/api/v1/users/123"))
    }

    @Test
    fun `handleNoSuchElement returns 404`() {
        val ex = NoSuchElementException("repository not found: 7f9c…")

        val response = handler.handleNoSuchElement(ex, webRequest())

        assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
        val body = response.body!!
        assertThat(body.status).isEqualTo(404)
        assertThat(body.title).isEqualTo("Resource Not Found")
        assertThat(body.detail).isEqualTo("repository not found: 7f9c…")
    }

    @Test
    fun `handleNoSuchElement falls back to default detail when message is null`() {
        val ex = NoSuchElementException()

        val response = handler.handleNoSuchElement(ex, null)

        assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(response.body!!.detail).isEqualTo("The referenced resource does not exist")
    }

    @Test
    fun `handleEmptyResultDataAccess returns 404`() {
        val ex = EmptyResultDataAccessException(1)

        val response = handler.handleEmptyResultDataAccess(ex, webRequest())

        assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(response.body!!.title).isEqualTo("Resource Not Found")
    }

    @Test
    fun `handleDomain returns 400 with code-based type and title`() {
        val ex = DomainException("Email already taken", "EMAIL_TAKEN")

        val response = handler.handleDomain(ex, webRequest())

        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        val body = response.body!!
        assertThat(body.status).isEqualTo(400)
        assertThat(body.title).isEqualTo("Email taken")
        assertThat(body.detail).isEqualTo("Email already taken")
        assertThat(body.type).isEqualTo(URI.create("urn:problem-type:email-taken"))
    }

    @Test
    fun `handleDomain handles single word code`() {
        val ex = DomainException("Something failed", "FORBIDDEN")

        val response = handler.handleDomain(ex, webRequest())

        val body = response.body!!
        assertThat(body.title).isEqualTo("Forbidden")
        assertThat(body.type).isEqualTo(URI.create("urn:problem-type:forbidden"))
    }

    @Test
    fun `handleIllegalArgument returns 400 with the message`() {
        val ex = IllegalArgumentException("workspaceId must be set")

        val response = handler.handleIllegalArgument(ex, webRequest())

        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        val body = response.body!!
        assertThat(body.status).isEqualTo(400)
        assertThat(body.title).isEqualTo("Bad Request")
        assertThat(body.detail).isEqualTo("workspaceId must be set")
    }

    @Test
    fun `handleIllegalArgument falls back when message is null`() {
        val response = handler.handleIllegalArgument(IllegalArgumentException(), null)

        assertThat(response.body!!.detail).isEqualTo("Invalid request")
    }

    @Test
    fun `handleIllegalState returns 409 with the message`() {
        val ex = IllegalStateException("Vault is not configured")

        val response = handler.handleIllegalState(ex, webRequest())

        assertThat(response.statusCode).isEqualTo(HttpStatus.CONFLICT)
        val body = response.body!!
        assertThat(body.status).isEqualTo(409)
        assertThat(body.title).isEqualTo("Conflict")
        assertThat(body.detail).isEqualTo("Vault is not configured")
    }

    @Test
    fun `handleIllegalState falls back when message is null`() {
        val response = handler.handleIllegalState(IllegalStateException(), null)

        assertThat(response.body!!.detail).isEqualTo("Request conflicts with current state")
    }

    @Test
    fun `handleValidation returns 422 with field errors and rejectedValue`() {
        val fieldError1 = SpringFieldError("obj", "email", "bad", false, null, null, "must not be blank")
        val fieldError2 = SpringFieldError("obj", "name", null, false, null, null, null)

        val bindingResult = mockk<BindingResult>()
        every { bindingResult.fieldErrors } returns listOf(fieldError1, fieldError2)

        val ex = mockk<MethodArgumentNotValidException>()
        every { ex.bindingResult } returns bindingResult

        val response = handler.handleValidation(ex, webRequest())

        assertThat(response.statusCode).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY)
        val body = response.body!!
        assertThat(body.status).isEqualTo(422)
        assertThat(body.title).isEqualTo("Validation Error")
        assertThat(body.detail).isEqualTo("One or more fields failed validation")
        assertThat(body.errors).hasSize(2)
        assertThat(body.errors[0].field).isEqualTo("email")
        assertThat(body.errors[0].message).isEqualTo("must not be blank")
        assertThat(body.errors[0].rejectedValue).isEqualTo("bad")
        assertThat(body.errors[1].field).isEqualTo("name")
        assertThat(body.errors[1].message).isEqualTo("Invalid value")
        assertThat(body.errors[1].rejectedValue).isNull()
    }

    @Test
    fun `handleHandlerMethodValidation returns a 422 validation problem`() {
        val ex = mockk<HandlerMethodValidationException>(relaxed = true)

        val response = handler.handleHandlerMethodValidation(ex, webRequest())

        assertThat(response.statusCode).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY)
        val body = response.body!!
        assertThat(body.status).isEqualTo(422)
        assertThat(body.title).isEqualTo("Validation Error")
        assertThat(body.detail).isEqualTo("One or more fields failed validation")
        assertThat(body.type).isEqualTo(URI.create("urn:problem-type:validation-error"))
        assertThat(body.errors).isEmpty()
    }

    @Test
    fun `handleConstraintViolation returns 422 with property path errors`() {
        val path = mockk<Path>()
        every { path.toString() } returns "create.input.name"
        val violation = mockk<ConstraintViolation<Any>>()
        every { violation.propertyPath } returns path
        every { violation.message } returns "must not be blank"
        every { violation.invalidValue } returns ""

        val ex = ConstraintViolationException("nope", setOf(violation))

        val response = handler.handleConstraintViolation(ex, webRequest())

        assertThat(response.statusCode).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY)
        val body = response.body!!
        assertThat(body.errors).hasSize(1)
        assertThat(body.errors[0].field).isEqualTo("create.input.name")
        assertThat(body.errors[0].message).isEqualTo("must not be blank")
        assertThat(body.errors[0].rejectedValue).isEqualTo("")
    }

    @Test
    fun `handleUnexpected returns 500 with exception class and message in detail`() {
        val ex = RuntimeException("boom")

        val response = handler.handleUnexpected(ex, webRequest("/api/v1/foo"))

        assertThat(response.statusCode).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR)
        val body = response.body!!
        assertThat(body.status).isEqualTo(500)
        assertThat(body.title).isEqualTo("Internal Server Error")
        assertThat(body.detail).isEqualTo("RuntimeException: boom")
        assertThat(body.exception).isEqualTo("java.lang.RuntimeException")
        assertThat(body.instance).isEqualTo(URI.create("/api/v1/foo"))
    }

    @Test
    fun `handleUnexpected truncates long messages`() {
        val longMessage = "x".repeat(600)
        val response = handler.handleUnexpected(RuntimeException(longMessage), null)

        val detail = response.body!!.detail!!
        assertThat(detail).endsWith("…")
        // "RuntimeException: " prefix (18) + 500 truncated chars + ellipsis
        assertThat(detail.length).isLessThanOrEqualTo(550)
    }

    @Test
    fun `handleUnexpected falls back to cause message when ex has none`() {
        val cause = IllegalStateException("from-cause")
        val ex = RuntimeException(null, cause)

        val response = handler.handleUnexpected(ex, null)

        assertThat(response.body!!.detail).contains("from-cause")
    }

    @Test
    fun `handleUnexpected uses first non-blank line of multi-line message`() {
        val ex = RuntimeException("\n  \nfirst real line\nsecond line")

        val response = handler.handleUnexpected(ex, null)

        assertThat(response.body!!.detail).isEqualTo("RuntimeException: first real line")
    }

    @Test
    fun `handleUnexpected propagates traceId from MDC`() {
        MDC.put("traceId", "abc123")
        try {
            val response = handler.handleUnexpected(RuntimeException("x"), null)
            assertThat(response.body!!.traceId).isEqualTo("abc123")
        } finally {
            MDC.clear()
        }
    }

    @Test
    fun `traceId falls back to trace_id MDC key when traceId is absent`() {
        MDC.put("trace_id", "snake-case")
        try {
            val response = handler.handleUnexpected(RuntimeException("x"), null)
            assertThat(response.body!!.traceId).isEqualTo("snake-case")
        } finally {
            MDC.clear()
        }
    }

    @Test
    fun `instance URI is null when request description fails`() {
        val req = mockk<WebRequest>()
        every { req.getDescription(false) } throws RuntimeException("boom")

        val response = handler.handleIllegalArgument(IllegalArgumentException("x"), req)

        assertThat(response.body!!.instance).isNull()
    }

    @Test
    fun `instance URI is null when description is blank`() {
        val req = mockk<WebRequest>()
        every { req.getDescription(false) } returns "uri="

        val response = handler.handleIllegalArgument(IllegalArgumentException("x"), req)

        assertThat(response.body!!.instance).isNull()
    }

    @Test
    fun `handleDataIntegrity maps an FK violation to 422 with constraint+column+referencedTable`() {
        // Regression for the production workspace 500: the upsert hit
        // `workspaces_github_link_id_fkey` because the legacy mirror
        // pointed at a non-existent github_links row. The handler
        // must surface the FK so the UI can render the error next to
        // the field that referenced the missing row.
        val cause =
            FakePsqlException(
                serverErrorMessage =
                    FakeServerErrorMessage(
                        constraint = "workspaces_github_link_id_fkey",
                        detail = "Key (github_link_id)=(a1b2c3d4-e5f6-…) is not present in table \"github_links\".",
                    ),
                sqlState = "23503",
                message = "ERROR: insert or update on table \"workspaces\" violates foreign key constraint",
            )
        val ex = DataIntegrityViolationException("FK violation", cause)

        val response = handler.handleDataIntegrity(ex, webRequest("/api/v1/workspaces"))

        assertThat(response.statusCode).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY)
        val body = response.body!!
        assertThat(body.status).isEqualTo(422)
        assertThat(body.title).isEqualTo("Constraint violation")
        assertThat(body.constraint).isEqualTo("workspaces_github_link_id_fkey")
        assertThat(body.column).isEqualTo("github_link_id")
        assertThat(body.referencedTable).isEqualTo("github_links")
        assertThat(body.detail).contains("github_link_id")
        assertThat(body.detail).contains("github_links")
        assertThat(body.errors).hasSize(1)
        assertThat(body.errors[0].field).isEqualTo("github_link_id")
        assertThat(body.type).isEqualTo(URI.create("urn:problem-type:constraint-violation"))
    }

    @Test
    fun `handleDataIntegrity maps a UNIQUE violation to 422 with constraint+column`() {
        val cause =
            FakePsqlException(
                serverErrorMessage =
                    FakeServerErrorMessage(
                        constraint = "repositories_name_key",
                        detail = "Key (name)=(example-service) already exists.",
                    ),
                sqlState = "23505",
            )
        val ex = DataIntegrityViolationException("unique violation", cause)

        val response = handler.handleDataIntegrity(ex, webRequest("/api/v1/repositories"))

        assertThat(response.statusCode).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY)
        val body = response.body!!
        assertThat(body.constraint).isEqualTo("repositories_name_key")
        assertThat(body.column).isEqualTo("name")
        assertThat(body.referencedTable).isNull()
        assertThat(body.detail).contains("already in use")
        assertThat(body.errors).hasSize(1)
        assertThat(body.errors[0].field).isEqualTo("name")
    }

    @Test
    fun `handleDataIntegrity falls back to the bare cause message when no PSQLException is in the chain`() {
        // E.g. an H2-driven test, or a synthetic DataIntegrityViolation
        // thrown by jOOQ itself without a Postgres-shaped cause.
        val ex = DataIntegrityViolationException("Something failed without a structured cause")

        val response = handler.handleDataIntegrity(ex, webRequest("/api/v1/whatever"))

        assertThat(response.statusCode).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY)
        val body = response.body!!
        assertThat(body.constraint).isNull()
        assertThat(body.column).isNull()
        assertThat(body.detail).contains("Something failed without a structured cause")
        assertThat(body.errors).isEmpty()
    }

    /**
     * Fake stand-in for `org.postgresql.util.PSQLException`. The parser
     * uses structural typing (`getServerErrorMessage`-shaped duck) so
     * this fake is sufficient and the test stays driver-free.
     */
    @Suppress("unused", "MemberVisibilityCanBePrivate")
    private class FakePsqlException(
        message: String? = "fake psql exception",
        private val serverErrorMessage: FakeServerErrorMessage? = null,
        private val sqlState: String? = null,
    ) : RuntimeException(message) {
        fun getServerErrorMessage(): FakeServerErrorMessage? = serverErrorMessage

        fun getSQLState(): String? = sqlState
    }

    @Suppress("unused", "MemberVisibilityCanBePrivate")
    private class FakeServerErrorMessage(
        private val constraint: String? = null,
        private val detail: String? = null,
    ) {
        fun getConstraint(): String? = constraint

        fun getDetail(): String? = detail
    }

    @Test
    fun `ProblemDetail data class properties and defaults`() {
        val pd = ProblemDetail(title = "Test", status = 200)
        assertThat(pd.type).isEqualTo(URI.create("about:blank"))
        assertThat(pd.detail).isNull()
        assertThat(pd.instance).isNull()
        assertThat(pd.errors).isEmpty()
        assertThat(pd.traceId).isNull()
        assertThat(pd.exception).isNull()
    }

    @Test
    fun `ProblemDetail with all fields`() {
        val pd =
            ProblemDetail(
                type = URI.create("https://example.com/error"),
                title = "Error",
                status = 400,
                detail = "Something went wrong",
                instance = URI.create("/api/test"),
                errors = listOf(FieldError("f1", "m1", "rv")),
                traceId = "abc",
                exception = "java.lang.Foo",
                kubernetesCode = 403,
                kubernetesReason = "Forbidden",
            )
        assertThat(pd.type).isEqualTo(URI.create("https://example.com/error"))
        assertThat(pd.title).isEqualTo("Error")
        assertThat(pd.status).isEqualTo(400)
        assertThat(pd.detail).isEqualTo("Something went wrong")
        assertThat(pd.instance).isEqualTo(URI.create("/api/test"))
        assertThat(pd.errors).hasSize(1)
        assertThat(pd.errors[0].field).isEqualTo("f1")
        assertThat(pd.errors[0].rejectedValue).isEqualTo("rv")
        assertThat(pd.traceId).isEqualTo("abc")
        assertThat(pd.exception).isEqualTo("java.lang.Foo")
        assertThat(pd.kubernetesCode).isEqualTo(403)
        assertThat(pd.kubernetesReason).isEqualTo("Forbidden")
    }

    @Test
    fun `ProblemDetail equals and hashCode`() {
        val pd1 = ProblemDetail(title = "T", status = 200)
        val pd2 = ProblemDetail(title = "T", status = 200)
        assertThat(pd1).isEqualTo(pd2)
        assertThat(pd1.hashCode()).isEqualTo(pd2.hashCode())
    }

    @Test
    fun `handleMessageNotReadable returns 400`() {
        val ex =
            org.springframework.http.converter.HttpMessageNotReadableException(
                "bad JSON",
                mockk<org.springframework.http.HttpInputMessage>(),
            )

        val response = handler.handleMessageNotReadable(ex, webRequest())

        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        val body = response.body!!
        assertThat(body.status).isEqualTo(400)
        assertThat(body.title).isEqualTo("Bad Request")
        assertThat(body.detail).isEqualTo("Malformed or unreadable request body")
    }

    @Test
    fun `handleMediaTypeNotSupported returns 415`() {
        val ex = org.springframework.web.HttpMediaTypeNotSupportedException("text/csv")

        val response = handler.handleMediaTypeNotSupported(ex, webRequest())

        assertThat(response.statusCode).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
        assertThat(response.body!!.title).isEqualTo("Unsupported Media Type")
    }

    @Test
    fun `FieldError data class`() {
        val fe = FieldError(field = "email", message = "required", rejectedValue = null)
        assertThat(fe.field).isEqualTo("email")
        assertThat(fe.message).isEqualTo("required")
        val fe2 = fe.copy(message = "invalid", rejectedValue = "x")
        assertThat(fe2.message).isEqualTo("invalid")
        assertThat(fe2.rejectedValue).isEqualTo("x")
    }
}
