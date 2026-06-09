package com.jorisjonkers.personalstack.common.web

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration

class InMemoryRequestRateLimiterTest {
    @Test
    fun `allows requests up to limit and blocks next request`() {
        val limiter = InMemoryRequestRateLimiter(cleanupInterval = 1)

        repeat(3) {
            assertThat(limiter.tryAcquire("key", 3, Duration.ofSeconds(5)).allowed).isTrue()
        }
        val blocked = limiter.tryAcquire("key", 3, Duration.ofSeconds(5))

        assertThat(blocked.allowed).isFalse()
        assertThat(blocked.retryAfterSeconds).isPositive()
    }

    @Test
    fun `caps tracked buckets to configured maximum`() {
        val limiter = InMemoryRequestRateLimiter(maxBuckets = 3, bucketIdleTtl = Duration.ofHours(1), cleanupInterval = 1)

        repeat(20) { limiter.tryAcquire("key-$it", 1, Duration.ofMinutes(1)) }

        assertThat(limiter.trackedBucketCount()).isLessThanOrEqualTo(3)
    }
}
