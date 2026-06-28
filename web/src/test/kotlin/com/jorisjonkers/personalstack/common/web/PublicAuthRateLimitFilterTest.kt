package com.jorisjonkers.personalstack.common.web

import jakarta.servlet.FilterChain
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

class PublicAuthRateLimitFilterTest {
    private val rules =
        listOf(
            WebUtilitiesProperties.RouteRateLimitRule(
                method = "POST",
                pathPattern = "/auth/**",
                maxRequests = 2,
                window = Duration.ofMinutes(1),
            ),
        )

    @Test
    fun `rate limits configured route only`() {
        val filter = PublicAuthRateLimitFilter(InMemoryRequestRateLimiter(cleanupInterval = 1), rules)
        val chainCalls = AtomicInteger()

        assertThat(invoke(filter, "GET", "/auth/login", chainCalls).status).isEqualTo(200)
        assertThat(invoke(filter, "POST", "/auth/login", chainCalls).status).isEqualTo(200)
        assertThat(invoke(filter, "POST", "/auth/login", chainCalls).status).isEqualTo(200)
        val blocked = invoke(filter, "POST", "/auth/login", chainCalls)

        assertThat(blocked.status).isEqualTo(429)
        assertThat(blocked.getHeader("Retry-After")).isNotBlank()
        assertThat(chainCalls.get()).isEqualTo(3)
    }

    @Test
    fun `does not trust forwarded headers from untrusted remote address`() {
        val filter = PublicAuthRateLimitFilter(InMemoryRequestRateLimiter(cleanupInterval = 1), rules)
        val chainCalls = AtomicInteger()

        repeat(2) {
            assertThat(
                invoke(
                    filter,
                    "POST",
                    "/auth/login",
                    chainCalls,
                    remoteAddr = "198.51.100.10",
                    headers = mapOf("X-Forwarded-For" to "203.0.113.$it"),
                ).status,
            ).isEqualTo(200)
        }
        assertThat(
            invoke(
                filter,
                "POST",
                "/auth/login",
                chainCalls,
                remoteAddr = "198.51.100.10",
                headers = mapOf("X-Forwarded-For" to "203.0.113.99"),
            ).status,
        ).isEqualTo(429)
    }

    @Test
    fun `prefers x real ip from trusted proxy`() {
        val filter = PublicAuthRateLimitFilter(InMemoryRequestRateLimiter(cleanupInterval = 1), rules)
        val request =
            MockHttpServletRequest("POST", "/auth/login").apply {
                servletPath = "/auth/login"
                remoteAddr = "127.0.0.1"
                addHeader("X-Real-IP", "203.0.113.10")
            }

        assertThat(filter.resolveClientIp(request)).isEqualTo("203.0.113.10")
    }

    private fun invoke(
        filter: PublicAuthRateLimitFilter,
        method: String,
        path: String,
        chainCalls: AtomicInteger,
        remoteAddr: String = "127.0.0.1",
        headers: Map<String, String> = emptyMap(),
    ): MockHttpServletResponse {
        val request =
            MockHttpServletRequest(method, path).apply {
                servletPath = path
                this.remoteAddr = remoteAddr
                headers.forEach { (name, value) -> addHeader(name, value) }
            }
        val response = MockHttpServletResponse()
        val chain = FilterChain { _, _ -> chainCalls.incrementAndGet() }
        filter.doFilter(request, response, chain)
        return response
    }
}
