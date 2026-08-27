package com.jorisjonkers.personalstack.common.web

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.Operation
import io.swagger.v3.oas.models.PathItem
import io.swagger.v3.oas.models.Paths
import io.swagger.v3.oas.models.media.Content
import io.swagger.v3.oas.models.media.MediaType
import io.swagger.v3.oas.models.media.ObjectSchema
import io.swagger.v3.oas.models.parameters.RequestBody
import io.swagger.v3.oas.models.responses.ApiResponse
import io.swagger.v3.oas.models.responses.ApiResponses
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

private const val PROBLEM_JSON = "application/problem+json"

class OpenApiProblemResponsesTest {
    private fun openApi(vararg operations: Pair<String, Operation>): OpenAPI =
        OpenAPI().paths(
            Paths().apply {
                operations.forEach { (path, operation) -> addPathItem(path, PathItem().get(operation)) }
            },
        )

    private fun operation(): Operation =
        Operation().responses(
            ApiResponses().addApiResponse("200", ApiResponse().description("OK")),
        )

    private fun withBody(): Operation =
        operation().requestBody(
            RequestBody().content(Content().addMediaType("application/json", MediaType().schema(ObjectSchema()))),
        )

    @Test
    fun `attaches the universal error responses to every operation`() {
        val api = openApi("/things" to operation())

        OpenApiProblemResponses().customise(api)

        val responses = api.paths["/things"]!!.get.responses
        assertThat(responses.keys).contains("401", "403", "500")
        assertThat(responses["500"]!!.content[PROBLEM_JSON]!!.schema.`$ref`)
            .isEqualTo("#/components/schemas/ProblemDetail")
    }

    @Test
    fun `attaches request validation responses only to operations that accept a body`() {
        val api = openApi("/read" to operation(), "/write" to withBody())

        OpenApiProblemResponses().customise(api)

        assertThat(
            api.paths["/read"]!!
                .get.responses.keys,
        ).doesNotContain("400", "422")
        assertThat(
            api.paths["/write"]!!
                .get.responses.keys,
        ).contains("400", "422")
    }

    @Test
    fun `never overwrites a response the service declared itself`() {
        val declared = ApiResponse().description("Service owns this one")
        val api = openApi("/things" to operation().apply { responses.addApiResponse("500", declared) })

        OpenApiProblemResponses().customise(api)

        assertThat(api.paths["/things"]!!.get.responses["500"]).isSameAs(declared)
    }

    @Test
    fun `honours configured statuses and schema name`() {
        val api = openApi("/things" to withBody())

        OpenApiProblemResponses(
            WebUtilitiesProperties.ProblemDetailsProperties(
                apiErrorSchemaName = "Problem",
                openApiUniversalErrorStatuses = listOf(503),
                openApiRequestBodyErrorStatuses = listOf(409),
            ),
        ).customise(api)

        val responses = api.paths["/things"]!!.get.responses
        assertThat(responses.keys).contains("503", "409").doesNotContain("401", "500")
        assertThat(responses["503"]!!.content[PROBLEM_JSON]!!.schema.`$ref`)
            .isEqualTo("#/components/schemas/Problem")
    }

    @Test
    fun `tolerates a spec with no paths and an operation with no responses`() {
        val empty = OpenAPI()
        val bare = OpenAPI().paths(Paths().apply { addPathItem("/bare", PathItem().get(Operation())) })

        OpenApiProblemResponses().customise(empty)
        OpenApiProblemResponses().customise(bare)

        assertThat(empty.paths).isNull()
        assertThat(
            bare.paths["/bare"]!!
                .get.responses.keys,
        ).contains("401", "403", "500")
    }

    @Test
    fun `falls back to a generic description for a status http does not name`() {
        val api = openApi("/things" to operation())

        OpenApiProblemResponses(
            WebUtilitiesProperties.ProblemDetailsProperties(openApiUniversalErrorStatuses = listOf(599)),
        ).customise(api)

        assertThat(
            api.paths["/things"]!!
                .get.responses["599"]!!
                .description,
        ).isEqualTo("Error")
    }

    @Test
    fun `deduplicates a status listed as both universal and body-specific`() {
        val api = openApi("/things" to withBody())

        OpenApiProblemResponses(
            WebUtilitiesProperties.ProblemDetailsProperties(
                openApiUniversalErrorStatuses = listOf(400),
                openApiRequestBodyErrorStatuses = listOf(400),
            ),
        ).customise(api)

        assertThat(
            api.paths["/things"]!!
                .get.responses["400"]!!
                .description,
        ).isEqualTo("Bad Request")
    }
}
