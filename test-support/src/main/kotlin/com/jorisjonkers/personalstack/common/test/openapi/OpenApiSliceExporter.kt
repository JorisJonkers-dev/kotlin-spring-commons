package com.jorisjonkers.personalstack.common.test.openapi

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import io.swagger.v3.core.util.Yaml
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

object OpenApiSliceExporter {
    const val DEFAULT_API_DOCS_PATH = "/v3/api-docs"

    private val jsonMapper =
        ObjectMapper().apply {
            enable(SerializationFeature.INDENT_OUTPUT)
            enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
        }

    private val yamlMapper =
        Yaml.mapper().copy().apply {
            enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
        }

    @JvmStatic
    fun exportJson(
        mockMvc: MockMvc,
        docsPath: String = DEFAULT_API_DOCS_PATH,
    ): String = normalizeJson(fetchJson(mockMvc, docsPath), docsPath)

    @JvmStatic
    fun writeJson(
        mockMvc: MockMvc,
        outputPath: Path,
        docsPath: String = DEFAULT_API_DOCS_PATH,
    ): Path = write(outputPath, exportJson(mockMvc, docsPath), "JSON")

    @JvmStatic
    fun exportYaml(
        mockMvc: MockMvc,
        docsPath: String = DEFAULT_API_DOCS_PATH,
    ): String {
        val tree = readJson(fetchJson(mockMvc, docsPath), docsPath)
        return withTrailingNewline(yamlMapper.writeValueAsString(tree))
    }

    @JvmStatic
    fun writeYaml(
        mockMvc: MockMvc,
        outputPath: Path,
        docsPath: String = DEFAULT_API_DOCS_PATH,
    ): Path = write(outputPath, exportYaml(mockMvc, docsPath), "YAML")

    private fun fetchJson(
        mockMvc: MockMvc,
        docsPath: String,
    ): String {
        val normalizedPath = normalizeDocsPath(docsPath)
        return try {
            val result =
                mockMvc
                    .perform(get(normalizedPath).accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk)
                    .andReturn()
            val content = result.response.contentAsString
            if (content.isBlank()) {
                throw OpenApiExportException("springdoc returned an empty OpenAPI response from '$normalizedPath'")
            }
            content
        } catch (ex: OpenApiExportException) {
            throw ex
        } catch (ex: Throwable) {
            throw OpenApiExportException(
                "Failed to fetch springdoc OpenAPI JSON from '$normalizedPath'. " +
                    "Ensure the test slice imports OpenApiWebMvcSliceConfiguration, " +
                    "sets springdoc.api-docs.path when using a custom path, and provides all controller collaborators.",
                ex,
            )
        }
    }

    private fun normalizeJson(
        raw: String,
        docsPath: String,
    ): String = withTrailingNewline(jsonMapper.writeValueAsString(readJson(raw, docsPath)))

    private fun readJson(
        raw: String,
        docsPath: String,
    ): JsonNode =
        try {
            jsonMapper.readTree(raw)
        } catch (ex: IOException) {
            throw OpenApiExportException("springdoc response from '$docsPath' was not valid OpenAPI JSON", ex)
        }

    private fun write(
        outputPath: Path,
        content: String,
        format: String,
    ): Path =
        try {
            outputPath.parent?.let(Files::createDirectories)
            Files.writeString(outputPath, content)
            outputPath
        } catch (ex: IOException) {
            throw OpenApiExportException("Failed to write OpenAPI $format output to '$outputPath'", ex)
        }

    private fun normalizeDocsPath(docsPath: String): String {
        val trimmed = docsPath.trim()
        require(trimmed.isNotEmpty()) { "OpenAPI docs path must not be blank" }
        return if (trimmed.startsWith("/")) trimmed else "/$trimmed"
    }

    private fun withTrailingNewline(content: String): String = content.trimEnd() + "\n"
}
