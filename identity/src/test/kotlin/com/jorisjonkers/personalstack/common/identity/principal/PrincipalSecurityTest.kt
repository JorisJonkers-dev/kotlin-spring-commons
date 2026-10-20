package com.jorisjonkers.personalstack.common.identity.principal

import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.JWSObject
import com.nimbusds.jose.Payload
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import jakarta.servlet.FilterChain
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.WebApplicationContextRunner
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.authorization.AuthorizationDeniedException
import org.springframework.security.authorization.AuthorizationManager
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.core.Authentication
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.access.intercept.RequestAuthorizationContext
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class PrincipalSecurityTest {
    private val identity = FakeIdentity()
    private val now = Instant.parse("2026-10-20T12:00:30Z")

    @AfterEach
    fun stop() {
        identity.stop()
        SecurityContextHolder.clearContext()
    }

    private fun ada(edit: (Principal) -> Principal = { it }): Principal =
        edit(
            Principal(
                subject = "ada",
                kind = Kind.USER,
                roles = listOf("user"),
                via = Via.SESSION,
                twoFactor = false,
                sid = "s",
                ver = "1.1",
            ),
        )

    private fun decision(
        manager: AuthorizationManager<RequestAuthorizationContext>,
        principal: Principal?,
    ) = manager.authorize({
        principal?.let {
            PrincipalAuthentication(it)
        } as Authentication?
    }, RequestAuthorizationContext(MockHttpServletRequest()))

    private fun granted(
        manager: AuthorizationManager<RequestAuthorizationContext>,
        principal: Principal?,
    ): Boolean = decision(manager, principal)?.isGranted == true

    @Test
    fun `a Principals authentication holds its App Roles as authorities`() {
        val auth = PrincipalAuthentication(ada { it.copy(roles = listOf("user", "editor")) })

        assertThat(auth.authorities.map { it.authority }).containsExactly("user", "editor")
        assertThat(auth.isAuthenticated).isTrue()
        assertThat(auth.name).isEqualTo("ada")
        assertThat(auth.credentials).isNull()
        assertThat(auth.principal.sid).isEqualTo("s")
    }

    @Test
    fun `the filter authenticates a request carrying a Principal`() {
        val key = RSAKeyGenerator(2048).keyID("k").generate()
        identity.publish(JWKSet(key.toPublicJWK()).toString())
        val claims =
            mapOf(
                "iss" to "https://auth.example.test",
                "sub" to "ada",
                "aud" to "web",
                "client_id" to "web",
                "exp" to now.plusSeconds(30).epochSecond,
                "ver" to "1.1",
                "kind" to "user",
                "via" to "personal-token",
                "roles" to listOf("user"),
                "two_factor" to false,
            )
        val token =
            JWSObject(
                JWSHeader
                    .Builder(JWSAlgorithm.RS256)
                    .keyID("k")
                    .type(JOSEObjectType.JWT)
                    .build(),
                Payload(claims),
            ).apply { sign(RSASSASigner(key)) }
                .serialize()
        val reader =
            PrincipalReader(
                PrincipalReaderConfig(
                    "https://auth.example.test",
                    "web",
                    keysUri = identity.keys,
                    clock = Clock.fixed(now, ZoneOffset.UTC),
                ),
            )
        val request = MockHttpServletRequest().apply { addHeader("Authorization", "Bearer $token") }
        var seen: Authentication? = null

        PrincipalAuthenticationFilter(reader).doFilter(
            request,
            MockHttpServletResponse(),
            FilterChain { _, _ ->
                seen =
                    SecurityContextHolder.getContext().authentication
            },
        )

        assertThat((seen as PrincipalAuthentication).principal.subject).isEqualTo("ada")
    }

    @Test
    fun `the filter lets a refused request go on unauthenticated`() {
        val reader = PrincipalReader(PrincipalReaderConfig("https://auth.example.test", "web", keysUri = identity.keys))
        var reached = false

        PrincipalAuthenticationFilter(reader).doFilter(
            MockHttpServletRequest(),
            MockHttpServletResponse(),
            FilterChain { _, _ ->
                reached =
                    true
            },
        )

        assertThat(reached).isTrue()
        assertThat(SecurityContextHolder.getContext().authentication).isNull()
    }

    @Test
    fun `the filter answers 503 when identity cannot be asked`() {
        identity.introspectStatus = 502
        val reader =
            PrincipalReader(
                PrincipalReaderConfig(
                    "https://auth.example.test",
                    "web",
                    "s3cret",
                    introspectUri = identity.introspect,
                ),
            )
        val response = MockHttpServletResponse()
        var reached = false

        PrincipalAuthenticationFilter(reader).doFilter(
            MockHttpServletRequest().apply { addHeader("Authorization", "Bearer idapp_tool") },
            response,
            FilterChain { _, _ -> reached = true },
        )

        assertThat(response.status).isEqualTo(503)
        assertThat(reached).isFalse()
    }

    @Test
    fun `requireRole lets through a caller holding the generated role`() {
        assertThat(granted(PrincipalPolicies.requireRole("editor"), ada { it.copy(roles = listOf("editor")) })).isTrue()
    }

    @Test
    fun `requireRole refuses a caller without the role`() {
        assertThat(granted(PrincipalPolicies.requireRole("editor"), ada())).isFalse()
    }

    @Test
    fun `refuseTools refuses a tool acting for a User`() {
        assertThat(
            granted(
                PrincipalPolicies.refuseTools(),
                ada {
                    it.copy(via = Via.TOOL, actor = Actor("mcp_1", "Claude Code"), sid = null)
                },
            ),
        ).isFalse()
    }

    @Test
    fun `refuseTools lets the User themselves through`() {
        assertThat(granted(PrincipalPolicies.refuseTools(), ada())).isTrue()
    }

    @Test
    fun `requireUser refuses a machine and lets a person through`() {
        val machine = ada { it.copy(kind = Kind.MACHINE, via = Via.MACHINE, sid = null) }

        assertThat(granted(PrincipalPolicies.requireUser(), machine)).isFalse()
        assertThat(granted(PrincipalPolicies.requireUser(), ada())).isTrue()
    }

    @Test
    fun `requireMachine refuses a person and lets a machine through`() {
        val machine = ada { it.copy(kind = Kind.MACHINE, via = Via.MACHINE, sid = null) }

        assertThat(granted(PrincipalPolicies.requireMachine(), ada())).isFalse()
        assertThat(granted(PrincipalPolicies.requireMachine(), machine)).isTrue()
    }

    @Test
    fun `every policy refuses a request without a Principal`() {
        for (manager in listOf(PrincipalPolicies.requireRole("user"), PrincipalPolicies.requireRecentTwoFactor(5))) {
            assertThat(granted(manager, null)).isFalse()
        }
    }

    @Test
    fun `requireRecentTwoFactor lets a fresh enough proof through`() {
        val clock = Clock.fixed(now, ZoneOffset.UTC)

        assertThat(
            granted(
                PrincipalPolicies.requireRecentTwoFactor(5, clock),
                ada {
                    it.copy(twoFactor = true, twoFactorAt = now.minusSeconds(240))
                },
            ),
        ).isTrue()
    }

    @Test
    fun `requireRecentTwoFactor asks a stale session to step up`() {
        val clock = Clock.fixed(now, ZoneOffset.UTC)

        val decision =
            decision(
                PrincipalPolicies.requireRecentTwoFactor(5, clock),
                ada {
                    it.copy(twoFactor = true, twoFactorAt = now.minusSeconds(360))
                },
            )

        assertThat((decision as StepUpRequired).minutes).isEqualTo(5)
    }

    @Test
    fun `requireRecentTwoFactor refuses a Personal Token outright`() {
        val decision =
            decision(PrincipalPolicies.requireRecentTwoFactor(5), ada { it.copy(via = Via.PERSONAL_TOKEN, sid = null) })

        assertThat(decision).isNotInstanceOf(StepUpRequired::class.java)
        assertThat(decision?.isGranted).isFalse()
    }

    @Test
    fun `a step-up refusal sends the browser to the Gates step-up and back to the same page`() {
        val request = MockHttpServletRequest("POST", "/pay").apply { queryString = "amount=3" }
        val response = MockHttpServletResponse()

        PrincipalAccessDeniedHandler().handle(
            request,
            response,
            AuthorizationDeniedException("step up", StepUpRequired(5)),
        )

        assertThat(response.status).isEqualTo(303)
        assertThat(response.getHeader("Location")).isEqualTo("/.auth/step-up?minutes=5&rd=%2Fpay%3Famount%3D3")
    }

    @Test
    fun `a step-up refusal on a page without a query comes back to that page`() {
        val response = MockHttpServletResponse()

        PrincipalAccessDeniedHandler().handle(
            MockHttpServletRequest("GET", "/pay"),
            response,
            AuthorizationDeniedException("step up", StepUpRequired(15)),
        )

        assertThat(response.getHeader("Location")).isEqualTo("/.auth/step-up?minutes=15&rd=%2Fpay")
    }

    @Test
    fun `any other refusal is answered 403`() {
        val response = MockHttpServletResponse()

        PrincipalAccessDeniedHandler().handle(MockHttpServletRequest(), response, AccessDeniedException("no"))

        assertThat(response.status).isEqualTo(403)
    }

    open class Pages {
        @PreAuthorize("hasAuthority('editor')")
        open fun edit(): String = "edited"
    }

    @Configuration
    @EnableMethodSecurity
    open class MethodSecurity {
        @Bean
        open fun pages(): Pages = Pages()
    }

    @Test
    fun `Spring's authorization annotations check the generated App Roles`() {
        AnnotationConfigApplicationContext(MethodSecurity::class.java).use { context ->
            val pages = context.getBean(Pages::class.java)
            SecurityContextHolder.getContext().authentication =
                PrincipalAuthentication(ada { it.copy(roles = listOf("editor")) })
            assertThat(pages.edit()).isEqualTo("edited")

            SecurityContextHolder.getContext().authentication = PrincipalAuthentication(ada())
            assertThatThrownBy { pages.edit() }.isInstanceOf(AccessDeniedException::class.java)
        }
    }

    private val runner =
        WebApplicationContextRunner().withConfiguration(
            AutoConfigurations.of(PrincipalAutoConfiguration::class.java),
        )

    @Test
    fun `an app naming its Client gets the reader, the filter kept out of the servlet chain, and the handler`() {
        runner
            .withPropertyValues(
                "jorisjonkers.identity.principal.issuer=https://auth.example.test",
                "jorisjonkers.identity.principal.client-id=web",
            ).run { context ->
                assertThat(context).hasSingleBean(PrincipalReader::class.java)
                assertThat(context).hasSingleBean(PrincipalAccessDeniedHandler::class.java)
                assertThat(context.getBean(FilterRegistrationBean::class.java).isEnabled).isFalse()
            }
    }

    @Test
    fun `an app naming no Client gets none of it`() {
        runner.run { context -> assertThat(context).doesNotHaveBean(PrincipalReader::class.java) }
    }

    @Test
    fun `an app naming its Client but not identity fails to start`() {
        runner.withPropertyValues("jorisjonkers.identity.principal.client-id=web").run { context ->
            assertThat(context).hasFailed()
        }
    }
}
