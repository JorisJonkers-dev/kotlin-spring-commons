package com.jorisjonkers.personalstack.common.test.openapi

import com.fasterxml.jackson.databind.ObjectMapper
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.web.server.WebServer
import org.springframework.boot.web.server.context.WebServerApplicationContext
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.nio.file.Files
import java.nio.file.Path

@WebMvcTest(
    controllers = [SyntheticOpenApiController::class],
    properties = [
        "springdoc.api-docs.path=/api/v1/api-docs",
        "springdoc.writer-with-order-by-keys=true",
        "springdoc.writer-with-default-pretty-printer=true",
    ],
)
@AutoConfigureMockMvc(addFilters = false)
@ContextConfiguration(
    classes = [
        SyntheticOpenApiTestApplication::class,
        SyntheticOpenApiController::class,
        OpenApiWebMvcSliceConfiguration::class,
        SyntheticOpenApiConfiguration::class,
        SyntheticOpenApiControllerAdvice::class,
        SyntheticOpenApiCollaboratorConfiguration::class,
    ],
)
class OpenApiSliceExporterTest
    @Autowired
    constructor(
        private val mockMvc: MockMvc,
        private val applicationContext: ApplicationContext,
    ) {
        private val objectMapper = ObjectMapper()

        @Test
        fun `exports deterministic JSON from springdoc MVC slice`() {
            val json = OpenApiSliceExporter.exportJson(mockMvc, "/api/v1/api-docs")
            val root = objectMapper.readTree(json)

            assertThat(root.path("openapi").asText()).startsWith("3.")
            assertThat(root.at("/info/title").asText()).isEqualTo("Synthetic Commons API")
            assertThat(root.at("/paths/~1widgets~1{id}/get/summary").asText()).isEqualTo("Fetch widget")
            assertThat(root.at("/paths/~1widgets/post/summary").asText()).isEqualTo("Create widget")
            val createWidgetNameType =
                root
                    .at("/components/schemas/CreateWidgetRequest/properties/name/type")
                    .asText()
            assertThat(createWidgetNameType).isEqualTo("string")
            assertThat(json).endsWith("\n")
        }

        @Test
        fun `normalizes docs path without leading slash`() {
            val json = OpenApiSliceExporter.exportJson(mockMvc, "api/v1/api-docs")
            val root = objectMapper.readTree(json)

            assertThat(root.at("/info/title").asText()).isEqualTo("Synthetic Commons API")
        }

        @Test
        fun `exports JSON from the default docs path`() {
            val json = OpenApiSliceExporter.exportJson(mockMvcReturning(VALID_OPENAPI_JSON))
            val root = objectMapper.readTree(json)

            assertThat(root.at("/info/title").asText()).isEqualTo("Stub API")
        }

        @Test
        fun `uses default docs path for JSON and YAML writer helpers`(
            @TempDir tempDir: Path,
        ) {
            val jsonPath = tempDir.resolve("default-openapi.json")
            val yamlPath = tempDir.resolve("default-openapi.yaml")

            OpenApiSliceExporter.writeJson(mockMvcReturning(VALID_OPENAPI_JSON), jsonPath)
            val yaml = OpenApiSliceExporter.exportYaml(mockMvcReturning(VALID_OPENAPI_JSON))
            OpenApiSliceExporter.writeYaml(mockMvcReturning(VALID_OPENAPI_JSON), yamlPath)

            assertThat(Files.readString(jsonPath)).contains("\"title\" : \"Stub API\"")
            assertThat(yaml).contains("Stub API").endsWith("\n")
            assertThat(Files.readString(yamlPath)).contains("Stub API").endsWith("\n")
        }

        @Test
        fun `writes JSON and YAML output files`(
            @TempDir tempDir: Path,
        ) {
            val jsonPath = tempDir.resolve("spec/openapi.json")
            val yamlPath = tempDir.resolve("spec/openapi.yaml")

            OpenApiSliceExporter.writeJson(mockMvc, jsonPath, "/api/v1/api-docs")
            OpenApiSliceExporter.writeYaml(mockMvc, yamlPath, "/api/v1/api-docs")

            assertThat(Files.readString(jsonPath)).contains("\"title\" : \"Synthetic Commons API\"")
            assertThat(Files.readString(yamlPath))
                .contains("openapi:")
                .contains("Synthetic Commons API")
                .endsWith("\n")
        }

        @Test
        fun `rejects blank docs paths`() {
            assertThatThrownBy { OpenApiSliceExporter.exportJson(mockMvc, " ") }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("must not be blank")
        }

        @Test
        fun `rejects empty OpenAPI responses`() {
            assertThatThrownBy { OpenApiSliceExporter.exportJson(mockMvcReturning("")) }
                .isInstanceOf(OpenApiExportException::class.java)
                .hasMessageContaining("empty OpenAPI response")
                .hasNoCause()
        }

        @Test
        fun `rejects invalid OpenAPI JSON responses`() {
            assertThatThrownBy { OpenApiSliceExporter.exportJson(mockMvcReturning("not json")) }
                .isInstanceOf(OpenApiExportException::class.java)
                .hasMessageContaining("not valid OpenAPI JSON")
                .hasCauseInstanceOf(Exception::class.java)
        }

        @Test
        fun `wraps fetch failures with slice setup guidance`() {
            assertThatThrownBy { OpenApiSliceExporter.exportJson(mockMvc, "/missing-api-docs") }
                .isInstanceOf(OpenApiExportException::class.java)
                .hasMessageContaining("Failed to fetch springdoc OpenAPI JSON")
                .hasMessageContaining("OpenApiWebMvcSliceConfiguration")
                .hasCauseInstanceOf(Throwable::class.java)
        }

        @Test
        fun `wraps write failures with the output path`(
            @TempDir tempDir: Path,
        ) {
            val outputDirectory = tempDir.resolve("openapi.json")
            Files.createDirectory(outputDirectory)

            assertThatThrownBy {
                OpenApiSliceExporter.writeJson(mockMvc, outputDirectory, "/api/v1/api-docs")
            }.isInstanceOf(OpenApiExportException::class.java)
                .hasMessageContaining("Failed to write OpenAPI JSON output")
                .hasMessageContaining(outputDirectory.toString())
                .hasCauseInstanceOf(Exception::class.java)
        }

        @Test
        fun `slice does not start an embedded web server`() {
            assertThat(applicationContext).isNotInstanceOf(WebServerApplicationContext::class.java)
            assertThat(applicationContext.getBeansOfType(WebServer::class.java)).isEmpty()
        }

        private fun mockMvcReturning(body: String): MockMvc =
            MockMvcBuilders
                .standaloneSetup(StubOpenApiDocsController(body))
                .build()

        private companion object {
            const val VALID_OPENAPI_JSON =
                """{"openapi":"3.1.0","info":{"title":"Stub API","version":"test"},"paths":{}}"""
        }
    }

@RestController
class StubOpenApiDocsController(
    private val body: String,
) {
    @GetMapping(value = [OpenApiSliceExporter.DEFAULT_API_DOCS_PATH], produces = [MediaType.APPLICATION_JSON_VALUE])
    fun docs(): String = body
}

@SpringBootConfiguration(proxyBeanMethods = false)
class SyntheticOpenApiTestApplication

@RestController
@RequestMapping("/widgets")
class SyntheticOpenApiController(
    private val widgetNames: SyntheticWidgetNames,
) {
    @GetMapping("/{id}")
    @Operation(summary = "Fetch widget")
    fun getWidget(
        @Parameter(description = "Widget identifier")
        @PathVariable id: String,
    ): WidgetResponse = WidgetResponse(id = id, name = widgetNames.nameFor(id), enabled = true)

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create widget")
    fun createWidget(
        @Valid @RequestBody request: CreateWidgetRequest,
    ): WidgetResponse = WidgetResponse(id = "created", name = request.name, enabled = request.enabled)
}

data class CreateWidgetRequest(
    @field:NotBlank
    @field:Schema(description = "Display name")
    val name: String,
    val enabled: Boolean = true,
)

data class WidgetResponse(
    val id: String,
    val name: String,
    val enabled: Boolean,
)

data class ErrorResponse(
    val code: String,
)

interface SyntheticWidgetNames {
    fun nameFor(id: String): String
}

@Configuration(proxyBeanMethods = false)
class SyntheticOpenApiConfiguration {
    @Bean
    fun openAPI(): OpenAPI =
        OpenAPI().info(
            Info()
                .title("Synthetic Commons API")
                .version("test")
                .description("Synthetic API for build-time OpenAPI export tests"),
        )
}

@Configuration(proxyBeanMethods = false)
class SyntheticOpenApiCollaboratorConfiguration {
    @Bean
    fun syntheticWidgetNames(): SyntheticWidgetNames =
        object : SyntheticWidgetNames {
            override fun nameFor(id: String): String = "widget-$id"
        }
}

@RestControllerAdvice
class SyntheticOpenApiControllerAdvice {
    @ExceptionHandler(IllegalArgumentException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun badRequest(): ErrorResponse = ErrorResponse("bad_request")
}
