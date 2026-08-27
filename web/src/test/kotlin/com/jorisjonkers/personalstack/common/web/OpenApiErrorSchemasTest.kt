package com.jorisjonkers.personalstack.common.web

import io.swagger.v3.oas.models.OpenAPI
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class OpenApiErrorSchemasTest {
    @Test
    fun `registers default problem detail schemas under the estate canonical names`() {
        val openApi = OpenAPI()

        OpenApiErrorSchemas().customise(openApi)

        assertThat(openApi.components.schemas).containsKeys("ProblemDetail", "FieldError")
        assertThat(openApi.components.schemas["ProblemDetail"]?.properties)
            .containsKeys("type", "title", "status", "detail", "instance", "errors", "traceId")
    }

    @Test
    fun `registers configurable problem detail schemas`() {
        val openApi = OpenAPI()
        OpenApiErrorSchemas(
            WebUtilitiesProperties.ProblemDetailsProperties(
                apiErrorSchemaName = "Problem",
                fieldErrorSchemaName = "ProblemField",
            ),
        ).customise(openApi)

        assertThat(openApi.components.schemas).containsKeys("Problem", "ProblemField")
        assertThat(openApi.components.schemas["Problem"]?.properties).containsKeys("type", "errors", "traceId")
    }
}
