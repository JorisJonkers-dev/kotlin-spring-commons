package com.jorisjonkers.personalstack.common.identity

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import jakarta.servlet.FilterChain
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtException
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

class ForwardAuthIdentityFilterTest {
    private val userId = UUID.fromString("742780c2-6f21-4e2f-971a-29a6e5a3b8bb")

    @Test
    fun `valid edge header assertion populates principal and continues`() {
        val jwtDecoder = mockk<JwtDecoder>()
        val filter = ForwardAuthIdentityFilter(ForwardAuthIdentityProperties(), jwtDecoder)
        val chainCalls = AtomicInteger()
        every { jwtDecoder.decode("edge-token") } returns jwt()

        val response =
            invoke(
                filter = filter,
                path = "/api/v1/widgets",
                chainCalls = chainCalls,
                headers = mapOf("X-Agents-Verified-Jwt" to "edge-token"),
            )

        assertThat(response.status).isEqualTo(200)
        assertThat(chainCalls.get()).isEqualTo(1)

        val principal = lastPrincipal
        assertThat(principal).isNotNull()
        assertThat(principal!!.userId).isEqualTo(userId)
        assertThat(principal.roles).containsExactlyInAnyOrder("USER", "ADMIN")
        assertThat(principal.username).isEqualTo("toast")
        assertThat(principal.credentialSource).isEqualTo(CredentialSource.EDGE_ASSERTION)
    }

    @Test
    fun `invalid assertion on protected path returns unauthorized`() {
        val jwtDecoder = mockk<JwtDecoder>()
        val filter = ForwardAuthIdentityFilter(ForwardAuthIdentityProperties(), jwtDecoder)
        val chainCalls = AtomicInteger()
        every { jwtDecoder.decode("bad-token") } throws JwtException("invalid")

        val response =
            invoke(
                filter = filter,
                path = "/api/v1/widgets",
                chainCalls = chainCalls,
                headers = mapOf("X-Agents-Verified-Jwt" to "bad-token"),
            )

        assertThat(response.status).isEqualTo(401)
        assertThat(response.contentType).startsWith("application/problem+json")
        assertThat(chainCalls.get()).isZero()
    }

    @Test
    fun `expired assertion on protected path returns unauthorized`() {
        val jwtDecoder = mockk<JwtDecoder>()
        val filter = ForwardAuthIdentityFilter(ForwardAuthIdentityProperties(), jwtDecoder)
        val chainCalls = AtomicInteger()
        every { jwtDecoder.decode("expired-token") } throws JwtException("expired")

        val response =
            invoke(
                filter = filter,
                path = "/api/v1/widgets",
                chainCalls = chainCalls,
                headers = mapOf("X-Agents-Verified-Jwt" to "expired-token"),
            )

        assertThat(response.status).isEqualTo(401)
        assertThat(chainCalls.get()).isZero()
    }

    @Test
    fun `unexpected decoder failure is propagated`() {
        val jwtDecoder = mockk<JwtDecoder>()
        val filter = ForwardAuthIdentityFilter(ForwardAuthIdentityProperties(), jwtDecoder)
        val chainCalls = AtomicInteger()
        every { jwtDecoder.decode("edge-token") } throws IllegalStateException("decoder unavailable")

        assertThatThrownBy {
            invoke(
                filter = filter,
                path = "/api/v1/widgets",
                chainCalls = chainCalls,
                headers = mapOf("X-Agents-Verified-Jwt" to "edge-token"),
            )
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessage("decoder unavailable")

        assertThat(chainCalls.get()).isZero()
    }

    @Test
    fun `missing credential on protected path returns unauthorized`() {
        val jwtDecoder = mockk<JwtDecoder>()
        val filter = ForwardAuthIdentityFilter(ForwardAuthIdentityProperties(), jwtDecoder)
        val chainCalls = AtomicInteger()

        val response =
            invoke(
                filter = filter,
                path = "/api/v1/widgets",
                chainCalls = chainCalls,
            )

        assertThat(response.status).isEqualTo(401)
        assertThat(chainCalls.get()).isZero()
    }

    @Test
    fun `skip path passes without a token`() {
        val jwtDecoder = mockk<JwtDecoder>()
        val filter = ForwardAuthIdentityFilter(ForwardAuthIdentityProperties(), jwtDecoder)
        val chainCalls = AtomicInteger()

        val response =
            invoke(
                filter = filter,
                path = "/api/v1/health/live",
                chainCalls = chainCalls,
            )

        assertThat(response.status).isEqualTo(200)
        assertThat(chainCalls.get()).isEqualTo(1)
        verify(exactly = 0) { jwtDecoder.decode(any()) }
    }

    @Test
    fun `skip path falls back to request uri when servlet path is blank`() {
        val jwtDecoder = mockk<JwtDecoder>()
        val filter = ForwardAuthIdentityFilter(ForwardAuthIdentityProperties(), jwtDecoder)
        val chainCalls = AtomicInteger()

        val response =
            invoke(
                filter = filter,
                path = "/api/v1/health/ready",
                chainCalls = chainCalls,
                servletPath = "",
            )

        assertThat(response.status).isEqualTo(200)
        assertThat(chainCalls.get()).isEqualTo(1)
        verify(exactly = 0) { jwtDecoder.decode(any()) }
    }

    @Test
    fun `authorization bearer fallback works when enabled and edge header is absent`() {
        val jwtDecoder = mockk<JwtDecoder>()
        val filter = ForwardAuthIdentityFilter(ForwardAuthIdentityProperties(), jwtDecoder)
        val chainCalls = AtomicInteger()
        every { jwtDecoder.decode("native-token") } returns jwt(username = null)

        val response =
            invoke(
                filter = filter,
                path = "/api/v1/widgets",
                chainCalls = chainCalls,
                headers = mapOf("Authorization" to "Bearer native-token"),
            )

        assertThat(response.status).isEqualTo(200)
        assertThat(chainCalls.get()).isEqualTo(1)
        assertThat(lastPrincipal?.credentialSource).isEqualTo(CredentialSource.AUTHORIZATION_BEARER)
        assertThat(lastPrincipal?.username).isNull()
    }

    @Test
    fun `edge header is preferred over authorization bearer when both are present`() {
        val jwtDecoder = mockk<JwtDecoder>()
        val filter = ForwardAuthIdentityFilter(ForwardAuthIdentityProperties(), jwtDecoder)
        val chainCalls = AtomicInteger()
        every { jwtDecoder.decode("edge-token") } returns jwt()

        val response =
            invoke(
                filter = filter,
                path = "/api/v1/widgets",
                chainCalls = chainCalls,
                headers =
                    mapOf(
                        "X-Agents-Verified-Jwt" to "edge-token",
                        "Authorization" to "Bearer bearer-token",
                    ),
            )

        assertThat(response.status).isEqualTo(200)
        assertThat(chainCalls.get()).isEqualTo(1)
        assertThat(lastPrincipal?.credentialSource).isEqualTo(CredentialSource.EDGE_ASSERTION)
        verify(exactly = 1) { jwtDecoder.decode("edge-token") }
        verify(exactly = 0) { jwtDecoder.decode("bearer-token") }
    }

    @Test
    fun `authorization bearer is rejected when fallback is disabled`() {
        val jwtDecoder = mockk<JwtDecoder>()
        val filter =
            ForwardAuthIdentityFilter(
                ForwardAuthIdentityProperties(acceptAuthorizationBearer = false),
                jwtDecoder,
            )
        val chainCalls = AtomicInteger()

        val response =
            invoke(
                filter = filter,
                path = "/api/v1/widgets",
                chainCalls = chainCalls,
                headers = mapOf("Authorization" to "Bearer native-token"),
            )

        assertThat(response.status).isEqualTo(401)
        assertThat(chainCalls.get()).isZero()
        verify(exactly = 0) { jwtDecoder.decode(any()) }
    }

    @Test
    fun `roles claim as a single string maps to one role`() {
        val jwtDecoder = mockk<JwtDecoder>()
        val filter = ForwardAuthIdentityFilter(ForwardAuthIdentityProperties(), jwtDecoder)
        val chainCalls = AtomicInteger()
        every { jwtDecoder.decode("edge-token") } returns jwt(roles = "USER")

        val response =
            invoke(
                filter = filter,
                path = "/api/v1/widgets",
                chainCalls = chainCalls,
                headers = mapOf("X-Agents-Verified-Jwt" to "edge-token"),
            )

        assertThat(response.status).isEqualTo(200)
        assertThat(lastPrincipal?.roles).containsExactly("USER")
    }

    @Test
    fun `absent roles claim maps to an empty role set`() {
        val jwtDecoder = mockk<JwtDecoder>()
        val filter = ForwardAuthIdentityFilter(ForwardAuthIdentityProperties(), jwtDecoder)
        val chainCalls = AtomicInteger()
        every { jwtDecoder.decode("edge-token") } returns jwt(includeRoles = false)

        val response =
            invoke(
                filter = filter,
                path = "/api/v1/widgets",
                chainCalls = chainCalls,
                headers = mapOf("X-Agents-Verified-Jwt" to "edge-token"),
            )

        assertThat(response.status).isEqualTo(200)
        assertThat(lastPrincipal?.roles).isEmpty()
    }

    @Test
    fun `assertion without a subject returns unauthorized`() {
        val jwtDecoder = mockk<JwtDecoder>()
        val filter = ForwardAuthIdentityFilter(ForwardAuthIdentityProperties(), jwtDecoder)
        val chainCalls = AtomicInteger()
        every { jwtDecoder.decode("edge-token") } returns jwt(subject = null)

        val response =
            invoke(
                filter = filter,
                path = "/api/v1/widgets",
                chainCalls = chainCalls,
                headers = mapOf("X-Agents-Verified-Jwt" to "edge-token"),
            )

        assertThat(response.status).isEqualTo(401)
        assertThat(chainCalls.get()).isZero()
    }

    private var lastPrincipal: ForwardAuthPrincipal? = null

    private fun invoke(
        filter: ForwardAuthIdentityFilter,
        path: String,
        chainCalls: AtomicInteger,
        headers: Map<String, String> = emptyMap(),
        servletPath: String = path,
    ): MockHttpServletResponse {
        lastPrincipal = null
        val request =
            MockHttpServletRequest("GET", path).apply {
                this.servletPath = servletPath
                headers.forEach { (name, value) -> addHeader(name, value) }
            }
        val response = MockHttpServletResponse()
        val chain =
            FilterChain { servletRequest, _ ->
                chainCalls.incrementAndGet()
                lastPrincipal =
                    servletRequest.getAttribute(ForwardAuthIdentityFilter.ATTRIBUTE_NAME) as? ForwardAuthPrincipal
            }
        filter.doFilter(request, response, chain)
        return response
    }

    private fun jwt(
        subject: String? = userId.toString(),
        username: String? = "toast",
        roles: Any = listOf("USER", "ADMIN"),
        includeRoles: Boolean = true,
    ): Jwt =
        Jwt
            .withTokenValue("token")
            .header("alg", "RS256")
            .apply {
                subject?.let { this.subject(it) }
                if (includeRoles) {
                    claim("roles", roles)
                }
            }.apply {
                username?.let { claim("username", it) }
            }.issuedAt(Instant.parse("2026-06-16T00:00:00Z"))
            .expiresAt(Instant.parse("2026-06-16T00:05:00Z"))
            .build()
}
