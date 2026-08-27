package com.jorisjonkers.personalstack.common.web

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.jorisjonkers.personalstack.common.test.openapi.OpenApiSliceExporter
import com.jorisjonkers.personalstack.common.test.openapi.OpenApiWebMvcSliceConfiguration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.web.servlet.MockMvc
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

private val PROBLEM_JSON = MediaType.APPLICATION_PROBLEM_JSON_VALUE
private const val PROBLEM_REF = "#/components/schemas/ProblemDetail"

data class FixturePayload(
    val name: String,
)

@RestController
@RequestMapping("/fixture")
class ProblemDetailFixtureController {
    @GetMapping("/items")
    fun list(): List<String> = listOf("one")

    @PostMapping("/items")
    fun create(
        @RequestBody payload: FixturePayload,
    ): String = payload.name
}

@SpringBootConfiguration
class ProblemDetailFixtureApplication

/**
 * The customizers are only worth anything if springdoc actually applies
 * them, so this asserts the generated document rather than an OpenAPI
 * object built by hand. It is the difference between "the customizer
 * works" and "a service that depends on this module ships a spec that
 * documents its errors".
 */
@WebMvcTest(controllers = [ProblemDetailFixtureController::class])
@AutoConfigureMockMvc(addFilters = false)
@ContextConfiguration(
    classes = [
        ProblemDetailFixtureApplication::class,
        ProblemDetailFixtureController::class,
        OpenApiWebMvcSliceConfiguration::class,
    ],
)
@Import(WebUtilitiesAutoConfiguration::class)
class ProblemDetailOpenApiContractTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    private fun spec(): JsonNode = ObjectMapper().readTree(OpenApiSliceExporter.exportJson(mockMvc))

    private fun responses(method: String): JsonNode =
        spec()
            .path("paths")
            .path("/fixture/items")
            .path(method)
            .path("responses")

    /** Navigated rather than JSON-pointered: the media type contains a `/`, which a pointer would have to escape. */
    private fun problemRef(
        responses: JsonNode,
        status: String,
    ): String =
        responses
            .path(status)
            .path("content")
            .path(PROBLEM_JSON)
            .path("schema")
            .path("\$ref")
            .asText()

    @Test
    fun `the generated spec declares the problem schemas under their canonical names`() {
        val schemas = spec().at("/components/schemas")

        assertThat(schemas.has("ProblemDetail")).isTrue()
        assertThat(schemas.has("FieldError")).isTrue()
    }

    @Test
    fun `every operation declares the universal error responses as problem json`() {
        val get = responses("get")

        listOf("401", "403", "500").forEach { status ->
            assertThat(problemRef(get, status))
                .describedAs("status %s on a read operation", status)
                .isEqualTo(PROBLEM_REF)
        }
    }

    @Test
    fun `an operation accepting a body also declares the request validation responses`() {
        val post = responses("post")
        val get = responses("get")

        listOf("400", "422").forEach { status ->
            assertThat(problemRef(post, status))
                .describedAs("status %s on a write operation", status)
                .isEqualTo(PROBLEM_REF)
            assertThat(get.has(status))
                .describedAs("status %s must not be promised by a bodyless read", status)
                .isFalse()
        }
    }

    @Test
    fun `the error schemas are referenced rather than orphaned in components`() {
        val document = spec().toString()

        assertThat(document).contains(PROBLEM_REF)
    }
}
