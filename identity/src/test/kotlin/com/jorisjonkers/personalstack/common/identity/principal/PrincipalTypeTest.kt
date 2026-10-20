package com.jorisjonkers.personalstack.common.identity.principal

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class PrincipalTypeTest {
    private val generated =
        Path.of(
            "src/main/kotlin/com/jorisjonkers/personalstack/common/identity/principal/Principal.kt",
        )

    // PRINCIPAL_UPDATE=true ./gradlew :identity:test --tests '*PrincipalTypeTest*'
    @Test
    fun `the Principal type is what the pinned schema generates`() {
        val want = PrincipalTypeGenerator.generate(javaClass.packageName, Contract.text("principal.schema.json"))
        if (System.getenv("PRINCIPAL_UPDATE") == "true") {
            Files.writeString(generated, want)
        }

        assertThat(Files.readString(generated)).isEqualTo(want)
    }

    @Test
    fun `a shape the generator does not know is an error`() {
        val schema = """{"title": "T", "type": "object", "properties": {"n": {"type": "number"}}}"""

        assertThatThrownBy {
            PrincipalTypeGenerator.generate(
                "p",
                schema,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
