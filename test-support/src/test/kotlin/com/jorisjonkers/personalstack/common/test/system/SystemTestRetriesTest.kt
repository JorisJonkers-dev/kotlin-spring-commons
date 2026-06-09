package com.jorisjonkers.personalstack.common.test.system

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.net.ConnectException
import java.util.concurrent.atomic.AtomicInteger

class SystemTestRetriesTest {
    @Test
    fun `creates a request specification for API calls`() {
        assertThat(SystemTestRetries.givenApi()).isNotNull()
    }

    @Test
    fun `retries connection failures and returns eventual result`() {
        val attempts = AtomicInteger()

        val result =
            SystemTestRetries.retryOnConnectionFailure(attempts = 3, delayMillis = 0) {
                if (attempts.incrementAndGet() < 3) {
                    throw RuntimeException(ConnectException("refused"))
                }
                "ok"
            }

        assertThat(result).isEqualTo("ok")
        assertThat(attempts.get()).isEqualTo(3)
    }

    @Test
    fun `does not retry unrelated failures`() {
        val attempts = AtomicInteger()

        assertThatThrownBy {
            SystemTestRetries.retryOnConnectionFailure(attempts = 3, delayMillis = 0) {
                attempts.incrementAndGet()
                error("bad request")
            }
        }.isInstanceOf(IllegalStateException::class.java)
        assertThat(attempts.get()).isEqualTo(1)
    }

    @Test
    fun `throws last connection failure after exhausting attempts`() {
        val attempts = AtomicInteger()
        val failure = ConnectException("refused")

        assertThatThrownBy {
            SystemTestRetries.retryOnConnectionFailure(attempts = 2, delayMillis = 0) {
                attempts.incrementAndGet()
                throw failure
            }
        }.isSameAs(failure)
        assertThat(attempts.get()).isEqualTo(2)
    }

    @Test
    fun `rejects invalid retry settings`() {
        assertThatThrownBy {
            SystemTestRetries.retryOnConnectionFailure(attempts = 0, delayMillis = 0) {
                "unused"
            }
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("attempts must be positive")

        assertThatThrownBy {
            SystemTestRetries.retryOnConnectionFailure(attempts = 1, delayMillis = -1) {
                "unused"
            }
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("delayMillis must not be negative")
    }
}
