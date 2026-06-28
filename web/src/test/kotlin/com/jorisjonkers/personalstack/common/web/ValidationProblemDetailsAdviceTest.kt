package com.jorisjonkers.personalstack.common.web

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
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
        assertThat(errors).hasSize(1)
        assertThat(errors.toString()).contains("name", "NotBlank")
    }

    @Suppress("UNUSED_PARAMETER")
    private class Handler {
        fun create(request: CreateWidgetRequest) = request
    }

    private data class CreateWidgetRequest(
        val name: String,
    )
}
