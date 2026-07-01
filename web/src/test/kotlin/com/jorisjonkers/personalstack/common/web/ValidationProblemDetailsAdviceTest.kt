package com.jorisjonkers.personalstack.common.web

import jakarta.validation.ConstraintViolationException
import jakarta.validation.Validation
import jakarta.validation.constraints.NotBlank
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.slf4j.MDC
import org.springframework.core.MethodParameter
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.validation.BeanPropertyBindingResult
import org.springframework.web.bind.MethodArgumentNotValidException

class ValidationProblemDetailsAdviceTest {
    private val request = MockHttpServletRequest("POST", "/widgets")

    @Test
    fun `method argument validation returns rfc7807 problem with field errors`() {
        val target = CreateWidgetRequest("")
        val bindingResult = BeanPropertyBindingResult(target, "createWidgetRequest")
        bindingResult.rejectValue("name", "NotBlank", "must not be blank")
        bindingResult.reject("WidgetInvalid", "widget cannot be used")
        val method = Handler::class.java.getDeclaredMethod("create", CreateWidgetRequest::class.java)
        val exception = MethodArgumentNotValidException(MethodParameter(method, 0), bindingResult)
        val advice =
            ValidationProblemDetailsAdvice(
                WebUtilitiesProperties.ProblemDetailsProperties(validationStatus = 422),
            )

        val problem = advice.handleMethodArgumentNotValid(exception, request)

        assertThat(problem.status).isEqualTo(422)
        assertThat(problem.instance.toString()).isEqualTo("/widgets")
        val errors = problem.properties?.get("errors") as List<*>
        assertThat(errors).hasSize(2)
        assertThat(errors.toString()).contains("name", "NotBlank")
        assertThat(errors.toString()).contains("createWidgetRequest", "WidgetInvalid", "widget cannot be used")
    }

    @Test
    fun `constraint validation returns rfc7807 problem with violation details and trace id`() {
        MDC.put("traceId", "trace-123")
        try {
            val violations =
                Validation
                    .buildDefaultValidatorFactory()
                    .validator
                    .validate(CreateWidgetRequest(""))
            val exception = ConstraintViolationException(violations)
            val advice =
                ValidationProblemDetailsAdvice(
                    WebUtilitiesProperties.ProblemDetailsProperties(validationStatus = 422),
                )

            val problem = advice.handleConstraintViolation(exception, request)

            assertThat(problem.status).isEqualTo(422)
            assertThat(problem.properties?.get("traceId")).isEqualTo("trace-123")
            val errors = problem.properties?.get("errors") as List<*>
            assertThat(errors).hasSize(1)
            assertThat(errors.toString())
                .contains("CreateWidgetRequest", "name", "NotBlank", "must not be blank")
        } finally {
            MDC.clear()
        }
    }

    @Test
    fun `default properties return bad request validation problem`() {
        val target = CreateWidgetRequest("")
        val bindingResult = BeanPropertyBindingResult(target, "createWidgetRequest")
        val method = Handler::class.java.getDeclaredMethod("create", CreateWidgetRequest::class.java)
        val exception = MethodArgumentNotValidException(MethodParameter(method, 0), bindingResult)

        val problem = ValidationProblemDetailsAdvice().handleMethodArgumentNotValid(exception, request)

        assertThat(problem.status).isEqualTo(400)
        assertThat(problem.detail).isEqualTo("Validation failed for request.")
    }

    private class Handler {
        fun create(request: CreateWidgetRequest) = request
    }

    private data class CreateWidgetRequest(
        @field:NotBlank
        val name: String,
    )
}
