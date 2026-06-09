package com.jorisjonkers.personalstack.common.test.system

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class TotpTestCodesTest {
    @Test
    fun `generates RFC 6238 test vector code`() {
        val secret = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"

        val code = TotpTestCodes.generate(secret, timeMillis = 59_000, digits = 8)

        assertThat(code).isEqualTo("94287082")
    }

    @Test
    fun `generates deterministic codes for different time steps and digit lengths`() {
        val secret = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"

        assertThat(TotpTestCodes.generate(secret, timeMillis = 1_111_111_109_000, digits = 8))
            .isEqualTo("07081804")
        assertThat(TotpTestCodes.generate(secret, timeMillis = 1_111_111_111_000, digits = 8))
            .isEqualTo("14050471")
        assertThat(TotpTestCodes.generate(secret, timeMillis = 59_000, digits = 6))
            .isEqualTo("287082")
        assertThat(TotpTestCodes.generate(secret, timeMillis = 59_000, digits = 7))
            .isEqualTo("4287082")
    }

    @Test
    fun `generate fresh returns a six digit code when current step has enough validity`() {
        val secret = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"

        val code = TotpTestCodes.generateFresh(secret, nowMillis = 1, minValiditySeconds = 0)

        assertThat(code).matches("\\d{6}")
    }

    @Test
    fun `decodes base32 secrets without padding`() {
        assertThat(TotpTestCodes.decodeBase32("MY").decodeToString()).isEqualTo("f")
    }

    @Test
    fun `decodes lowercase base32 secrets with padding and whitespace`() {
        assertThat(TotpTestCodes.decodeBase32("mzxw6===\n").decodeToString()).isEqualTo("foo")
    }

    @Test
    fun `rejects unsupported digit lengths`() {
        assertThatThrownBy { TotpTestCodes.generate("MY", timeMillis = 0, digits = 5) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("digits must be between 6 and 8")
        assertThatThrownBy { TotpTestCodes.generate("MY", timeMillis = 0, digits = 9) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("digits must be between 6 and 8")
    }

    @Test
    fun `rejects invalid base32 characters`() {
        assertThatThrownBy { TotpTestCodes.decodeBase32("not-valid!") }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("Invalid Base32")
    }
}
