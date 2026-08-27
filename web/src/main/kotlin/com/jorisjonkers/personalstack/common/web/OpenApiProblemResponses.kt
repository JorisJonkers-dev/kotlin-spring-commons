package com.jorisjonkers.personalstack.common.web

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.Operation
import io.swagger.v3.oas.models.media.Content
import io.swagger.v3.oas.models.media.MediaType
import io.swagger.v3.oas.models.media.Schema
import io.swagger.v3.oas.models.responses.ApiResponse
import io.swagger.v3.oas.models.responses.ApiResponses
import org.springdoc.core.customizers.OpenApiCustomizer
import org.springframework.http.HttpStatus

/**
 * Attaches the estate's RFC 9457 error responses to every operation.
 *
 * [OpenApiErrorSchemas] registers the problem schemas; on its own that
 * leaves them orphaned in `components`, documented nowhere and referenced
 * by nothing. This customizer is what makes a generated spec admit the
 * failures the services already return, so that generated clients carry an
 * error type, mock servers can serve a failure, and conformance checking
 * has something to check against.
 *
 * A response the service declared itself always wins — this only fills
 * gaps, so a hand-annotated 404 or 409 is never clobbered by the baseline.
 */
class OpenApiProblemResponses(
    private val properties: WebUtilitiesProperties.ProblemDetailsProperties =
        WebUtilitiesProperties
            .ProblemDetailsProperties(),
) : OpenApiCustomizer {
    override fun customise(openApi: OpenAPI) {
        val paths = openApi.paths ?: return
        paths.values
            .flatMap { it.readOperations() }
            .forEach(::decorate)
    }

    private fun decorate(operation: Operation) {
        val responses = operation.responses ?: ApiResponses().also { operation.responses = it }
        val statuses =
            properties.openApiUniversalErrorStatuses +
                if (operation.requestBody != null) properties.openApiRequestBodyErrorStatuses else emptyList()
        statuses.distinct().forEach { status ->
            val key = status.toString()
            if (!responses.containsKey(key)) {
                responses.addApiResponse(key, problemResponse(status))
            }
        }
    }

    private fun problemResponse(status: Int): ApiResponse =
        ApiResponse()
            .description(reasonPhrase(status))
            .content(
                Content().addMediaType(
                    PROBLEM_JSON_MEDIA_TYPE,
                    MediaType().schema(
                        Schema<Any>().`$ref`("#/components/schemas/${properties.apiErrorSchemaName}"),
                    ),
                ),
            )

    private fun reasonPhrase(status: Int): String = HttpStatus.resolve(status)?.reasonPhrase ?: "Error"

    companion object {
        const val PROBLEM_JSON_MEDIA_TYPE: String = "application/problem+json"
    }
}
