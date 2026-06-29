package com.jorisjonkers.personalstack.common.test.system

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.assertj.core.api.Assertions.assertThatIllegalStateException
import org.junit.jupiter.api.Test

class ImageTagsTest {
    @Test
    fun `parses explicit service tags`() {
        val tags = ImageTags.parse("api=v1.2.3 worker=2026.06.29")

        assertThat(tags.tagFor("api")).isEqualTo("v1.2.3")
        assertThat(tags.tagFor("worker")).isEqualTo("2026.06.29")
    }

    @Test
    fun `parses exact image refs and ignores unsupported third party refs`() {
        val tags =
            ImageTags.parse(
                """
                ghcr.io/example/api:v1.2.3
                ghcr.io/example/worker@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
                docker.io/library/postgres:17.2
                """.trimIndent(),
                allowedServices = setOf("api", "worker"),
            )

        assertThat(tags.tagFor("api")).isEqualTo("ghcr.io/example/api:v1.2.3")
        assertThat(tags.tagFor("worker"))
            .isEqualTo("ghcr.io/example/worker@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
        assertThat(tags.tagFor("postgres")).isNull()
    }

    @Test
    fun `applies image-name aliases`() {
        val tags =
            ImageTags.parse(
                "ghcr.io/example/service:v1.0.0",
                allowedServices = setOf("api"),
                serviceAliases = mapOf("service" to "api"),
            )

        assertThat(tags.requireTagFor("api")).isEqualTo("ghcr.io/example/service:v1.0.0")
    }

    @Test
    fun `rejects latest explicit tags and image refs`() {
        assertThatIllegalArgumentException()
            .isThrownBy { ImageTags.parse("api=latest") }

        assertThatIllegalArgumentException()
            .isThrownBy { ImageTags.parse("ghcr.io/example/api:latest") }
    }

    @Test
    fun `rejects unsupported explicit service names`() {
        assertThatIllegalArgumentException()
            .isThrownBy {
                ImageTags.parse(
                    "unknown=v1.0.0",
                    allowedServices = setOf("api"),
                )
            }
    }

    @Test
    fun `rejects duplicate service entries`() {
        assertThatIllegalArgumentException()
            .isThrownBy { ImageTags.parse("api=v1.0.0 api=v1.0.1") }
    }

    @Test
    fun `loads raw tags from supplied environment`() {
        val tags =
            ImageTags.fromEnvironment(
                variableName = "CUSTOM_IMAGE_TAGS",
                environment = mapOf("CUSTOM_IMAGE_TAGS" to "api=v1.0.0"),
                properties = emptyMap(),
            )

        assertThat(tags.requireTagFor("api")).isEqualTo("v1.0.0")
    }

    @Test
    fun `requires requested services`() {
        assertThatIllegalArgumentException()
            .isThrownBy { ImageTags.parse("api=v1.0.0").requireAll(setOf("api", "worker")) }
    }

    @Test
    fun `throws clear error when required tag is missing`() {
        assertThatIllegalStateException()
            .isThrownBy { ImageTags.parse("").requireTagFor("api") }
    }
}
