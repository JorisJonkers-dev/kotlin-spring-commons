package com.jorisjonkers.personalstack.common.test.system

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SystemTestEnvironmentTest {
    @Test
    fun `reads configured properties with defaults`() {
        val environment =
            SystemTestEnvironment(
                mapOf(
                    "test.api.url" to "https://api.example.test",
                    "test.shard.index" to "2",
                    "test.shard.count" to "4",
                    "blank" to "",
                ),
            )

        assertThat(environment.apiUrl).isEqualTo("https://api.example.test")
        assertThat(environment.frontendUrl).isEqualTo("http://localhost:3000")
        assertThat(environment.shardIndex).isEqualTo(2)
        assertThat(environment.shardCount).isEqualTo(4)
        assertThat(environment.string("blank", "fallback")).isEqualTo("fallback")
    }

    @Test
    fun `parses integers with fallback for missing blank and invalid values`() {
        val environment =
            SystemTestEnvironment(
                mapOf(
                    "valid" to "42",
                    "blank" to "",
                    "invalid" to "forty-two",
                ),
            )

        assertThat(environment.int("valid", 7)).isEqualTo(42)
        assertThat(environment.int("missing", 7)).isEqualTo(7)
        assertThat(environment.int("blank", 7)).isEqualTo(7)
        assertThat(environment.int("invalid", 7)).isEqualTo(7)
        assertThat(environment.optionalInt("valid")).isEqualTo(42)
        assertThat(environment.optionalInt("missing")).isNull()
        assertThat(environment.optionalInt("invalid")).isNull()
    }

    @Test
    fun `ignores shard counts that do not require sharding`() {
        assertThat(SystemTestEnvironment(mapOf("test.shard.count" to "1")).shardCount).isNull()
        assertThat(SystemTestEnvironment(mapOf("test.shard.count" to "0")).shardCount).isNull()
        assertThat(SystemTestEnvironment(mapOf("test.shard.count" to "many")).shardCount).isNull()
    }

    @Test
    fun `uses configured frontend url`() {
        val environment =
            SystemTestEnvironment(
                mapOf("test.frontend.url" to "https://app.example.test"),
            )

        assertThat(environment.frontendUrl).isEqualTo("https://app.example.test")
    }
}
