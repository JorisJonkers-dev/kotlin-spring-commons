package com.jorisjonkers.personalstack.common.identity

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.core.MethodParameter
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.web.context.request.RequestAttributes
import org.springframework.web.context.request.ServletWebRequest
import java.util.UUID

class CurrentPrincipalArgumentResolverTest {
    private val resolver = CurrentPrincipalArgumentResolver()

    @Test
    fun `supports forward auth principal parameters`() {
        assertThat(resolver.supportsParameter(parameter("principalParameter"))).isTrue()
    }

    @Test
    fun `supports current principal annotated parameters`() {
        assertThat(resolver.supportsParameter(parameter("annotatedParameter"))).isTrue()
    }

    @Test
    fun `does not support unrelated parameters`() {
        assertThat(resolver.supportsParameter(parameter("plainParameter"))).isFalse()
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
                parameter("principalParameter"),
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
                parameter("principalParameter"),
                null,
                ServletWebRequest(MockHttpServletRequest()),
                null,
            )

        assertThat(resolved).isNull()
    }

    private fun parameter(methodName: String): MethodParameter =
        MethodParameter(
            javaClass.declaredMethods.single { method -> method.name == methodName },
            0,
        )

    @Suppress("unused")
    private fun principalParameter(principal: ForwardAuthPrincipal) = principal

    @Suppress("unused")
    private fun annotatedParameter(
        @CurrentPrincipal principal: String,
    ) = principal

    @Suppress("unused")
    private fun plainParameter(principal: String) = principal
}
