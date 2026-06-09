package com.jorisjonkers.personalstack.common.test.system

import com.microsoft.playwright.PlaywrightException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

class PlaywrightNavigationRetriesTest {
    @Test
    fun `retries connection refused navigation and returns after success`() {
        val attempts = AtomicInteger()

        PlaywrightNavigationRetries.retryOnConnectionRefused(attempts = 3, delayMillis = 0) {
            if (attempts.incrementAndGet() < 3) {
                throw PlaywrightException("page.navigate: net::ERR_CONNECTION_REFUSED at http://localhost:3000")
            }
        }

        assertThat(attempts.get()).isEqualTo(3)
    }

    @Test
    fun `throws last connection refused error after exhausting attempts`() {
        val attempts = AtomicInteger()
        val failure = PlaywrightException("page.navigate: net::ERR_CONNECTION_REFUSED at http://localhost:3000")

        assertThatThrownBy {
            PlaywrightNavigationRetries.retryOnConnectionRefused(attempts = 2, delayMillis = 0) {
                attempts.incrementAndGet()
                throw failure
            }
        }.isSameAs(failure)
        assertThat(attempts.get()).isEqualTo(2)
    }

    @Test
    fun `does not retry other playwright failures`() {
        val attempts = AtomicInteger()
        val failure = PlaywrightException("page.navigate: Timeout 30000ms exceeded")

        assertThatThrownBy {
            PlaywrightNavigationRetries.retryOnConnectionRefused(attempts = 3, delayMillis = 0) {
                attempts.incrementAndGet()
                throw failure
            }
        }.isSameAs(failure)
        assertThat(attempts.get()).isEqualTo(1)
    }

    @Test
    fun `throws fallback error when no attempt runs`() {
        assertThatThrownBy {
            PlaywrightNavigationRetries.retryOnConnectionRefused(attempts = 0, delayMillis = 0) {
                error("should not run")
            }
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("navigation failed without an exception")
    }
}
