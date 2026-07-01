package com.jorisjonkers.personalstack.common.identity

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.core.MethodParameter
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.web.context.request.RequestAttributes
import org.springframework.web.context.request.ServletWebRequest
import java.util.UUID
import kotlin.reflect.KFunction

class CurrentPrincipalArgumentResolverTest {
    private val resolver = CurrentPrincipalArgumentResolver()

    @Test
    fun `supports forward auth principal parameters`() {
        assertThat(resolver.supportsParameter(parameter(::principalParameter))).isTrue()
    }

    @Test
    fun `supports current principal annotated parameters`() {
        assertThat(resolver.supportsParameter(parameter(::annotatedParameter))).isTrue()
    }

    @Test
    fun `does not support unrelated parameters`() {
        assertThat(resolver.supportsParameter(parameter(::plainParameter))).isFalse()
    }

    @Test
    fun `resolves principal from request attribute`() {
        val principal =
            ForwardAuthPrincipal(
                userId = UUID.fromString("742780c2-6f21-4e2f-971a-29a6e5a3b8bb"),
                roles = setOf("USER"),
                username = "toast",
                credentialSource = CredentialSource.EDGE_ASSERTION,
            )
        val webRequest = ServletWebRequest(MockHttpServletRequest())
        webRequest.setAttribute(
            ForwardAuthIdentityFilter.ATTRIBUTE_NAME,
            principal,
            RequestAttributes.SCOPE_REQUEST,
        )

        val resolved =
            resolver.resolveArgument(
                parameter(::principalParameter),
                null,
                webRequest,
                null,
            )

        assertThat(resolved).isSameAs(principal)
    }

    @Test
    fun `resolves null when request attribute is absent`() {
        val resolved =
            resolver.resolveArgument(
                parameter(::principalParameter),
                null,
                ServletWebRequest(MockHttpServletRequest()),
                null,
            )

        assertThat(resolved).isNull()
    }

    private fun parameter(method: KFunction<*>): MethodParameter =
        MethodParameter(
            javaClass.declaredMethods.single { candidate -> candidate.name == method.name },
            0,
        )

    private fun principalParameter(principal: ForwardAuthPrincipal) {
        check(principal.userId.version() > 0)
    }

    private fun annotatedParameter(
        @CurrentPrincipal principal: String,
    ) {
        check(principal.isNotBlank())
    }

    private fun plainParameter(principal: String) {
        check(principal.isNotBlank())
    }
}
