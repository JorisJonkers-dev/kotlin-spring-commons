package com.jorisjonkers.personalstack.common.web

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.media.ArraySchema
import io.swagger.v3.oas.models.media.IntegerSchema
import io.swagger.v3.oas.models.media.ObjectSchema
import io.swagger.v3.oas.models.media.Schema
import io.swagger.v3.oas.models.media.StringSchema
import org.springdoc.core.customizers.OpenApiCustomizer

class OpenApiErrorSchemas(
    private val properties: WebUtilitiesProperties.ProblemDetailsProperties = WebUtilitiesProperties.ProblemDetailsProperties(),
) : OpenApiCustomizer {
    override fun customise(openApi: OpenAPI) {
        val components = openApi.components ?: io.swagger.v3.oas.models.Components().also { openApi.components = it }
        components.addSchemas(properties.fieldErrorSchemaName, fieldErrorSchema())
        components.addSchemas(properties.apiErrorSchemaName, apiErrorSchema(properties.fieldErrorSchemaName))
    }

    private fun apiErrorSchema(fieldErrorSchemaName: String): Schema<Any> =
        ObjectSchema().apply {
            description("RFC 7807 problem document with optional validation errors.")
            addProperty("type", StringSchema().example("about:blank"))
            addProperty("title", StringSchema().example("Bad Request"))
            addProperty("status", IntegerSchema().example(400))
            addProperty("detail", StringSchema().example("Validation failed for request."))
            addProperty("instance", StringSchema().example("/api/resource"))
            addProperty("errors", ArraySchema().items(Schema<Any>().`$ref`("#/components/schemas/$fieldErrorSchemaName")))
            addProperty("traceId", StringSchema().example("a8c0c4e5f1c24a7e"))
        } as Schema<Any>

    private fun fieldErrorSchema(): Schema<Any> =
        ObjectSchema().apply {
            description("Details about a single validation error.")
            addProperty("objectName", StringSchema().example("request"))
            addProperty("field", StringSchema().example("email"))
            addProperty("message", StringSchema().example("must be a well-formed email address"))
            addProperty("code", StringSchema().example("Email"))
            addProperty("rejectedValue", Schema<Any>().nullable(true))
        } as Schema<Any>
}
