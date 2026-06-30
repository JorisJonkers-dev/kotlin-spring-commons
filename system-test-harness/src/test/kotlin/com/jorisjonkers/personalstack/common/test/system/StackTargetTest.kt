package com.jorisjonkers.personalstack.common.test.system

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.assertj.core.api.Assertions.assertThatIllegalStateException
import org.junit.jupiter.api.Test
import java.net.URI

class StackTargetTest {
    @Test
    fun `parses stack target list`() {
        val target = StackTarget.parse("api=https://api.example.test ui=https://ui.example.test")

        assertThat(target.uriFor("api")).isEqualTo(URI.create("https://api.example.test"))
        assertThat(target.urlFor("ui", "health")).isEqualTo(URI.create("https://ui.example.test/health"))
    }

    @Test
    fun `resolves service URL when path is omitted`() {
        val target = StackTarget(mapOf("api" to URI.create("https://api.example.test")))

        assertThat(target.urlFor("api")).isEqualTo(URI.create("https://api.example.test"))
    }

    @Test
    fun `loads targets from properties and environment`() {
        val target =
            StackTarget.fromEnvironment(
                serviceNames = setOf("api", "worker"),
                properties = mapOf("test.api.url" to "https://api.example.test"),
                environment = mapOf("TEST_WORKER_URL" to "https://worker.example.test"),
            )

        assertThat(target.services)
            .containsEntry("api", URI.create("https://api.example.test"))
            .containsEntry("worker", URI.create("https://worker.example.test"))
    }

    @Test
    fun `loads target list from environment variable`() {
        val target =
            StackTarget.fromEnvironment(
                serviceNames = setOf("api"),
                environment = mapOf("STACK_TARGETS" to "api=https://api.example.test"),
                properties = emptyMap(),
            )

        assertThat(target.uriFor("api")).isEqualTo(URI.create("https://api.example.test"))
    }

    @Test
    fun `loads target list from default system properties`() =
        withSystemProperty("test.targets", "api=https://api.example.test") {
            val target = StackTarget.fromEnvironment()

            assertThat(target.uriFor("api")).isEqualTo(URI.create("https://api.example.test"))
        }

    @Test
    fun `exposes JvmStatic companion functions`() {
        val parsed =
            StackTarget::class.java
                .getMethod("parse", String::class.java)
                .invoke(null, "api=https://api.example.test") as StackTarget
        val fromEnvironment =
            StackTarget::class.java
                .getMethod(
                    "fromEnvironment",
                    Set::class.java,
                    String::class.java,
                    Map::class.java,
                    Map::class.java,
                ).invoke(
                    null,
                    setOf("api"),
                    "STACK_TARGETS",
                    emptyMap<String, String>(),
                    mapOf("STACK_TARGETS" to "api=https://api.example.test"),
                ) as StackTarget

        assertThat(parsed.uriFor("api")).isEqualTo(URI.create("https://api.example.test"))
        assertThat(fromEnvironment.uriFor("api")).isEqualTo(URI.create("https://api.example.test"))
    }

    @Test
    fun `requires requested service targets`() {
        assertThatIllegalArgumentException()
            .isThrownBy {
                StackTarget.fromEnvironment(
                    serviceNames = setOf("api"),
                    properties = emptyMap(),
                    environment = emptyMap(),
                )
            }
    }

    @Test
    fun `rejects invalid target entries`() {
        assertThatIllegalArgumentException()
            .isThrownBy { StackTarget.parse("api") }
    }

    @Test
    fun `throws clear error when service URL is missing`() {
        assertThatIllegalStateException()
            .isThrownBy { StackTarget(emptyMap()).uriFor("api") }
    }

    private fun withSystemProperty(
        name: String,
        value: String,
        block: () -> Unit,
    ) {
        val previous = System.getProperty(name)
        try {
            System.setProperty(name, value)
            block()
        } finally {
            if (previous == null) {
                System.clearProperty(name)
            } else {
                System.setProperty(name, previous)
            }
        }
    }
}
