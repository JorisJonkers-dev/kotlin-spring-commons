package com.jorisjonkers.personalstack.common.identity

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtException
import org.springframework.util.AntPathMatcher
import org.springframework.web.filter.OncePerRequestFilter
import java.util.UUID

class ForwardAuthIdentityFilter(
    private val properties: ForwardAuthIdentityProperties,
    private val jwtDecoder: JwtDecoder,
) : OncePerRequestFilter() {
    private val pathMatcher = AntPathMatcher()

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val principal = if (isSkipPath(request)) null else resolvePrincipal(request)
        when {
            isSkipPath(request) -> filterChain.doFilter(request, response)
            principal == null -> writeUnauthorized(response)
            else -> {
                request.setAttribute(ATTRIBUTE_NAME, principal)
                filterChain.doFilter(request, response)
            }
        }
    }

    private fun resolvePrincipal(request: HttpServletRequest): ForwardAuthPrincipal? {
        val credential = resolveCredential(request) ?: return null
        return try {
            jwtDecoder.decode(credential.token).toPrincipal(credential.source)
        } catch (ignored: JwtException) {
            null
        } catch (ignored: IllegalArgumentException) {
            null
        }
    }

    private fun isSkipPath(request: HttpServletRequest): Boolean {
        val path = request.servletPath.takeIf { it.isNotBlank() } ?: request.requestURI
        return properties.skipPathPatterns.any { pattern -> pathMatcher.match(pattern, path) }
    }

    private fun resolveCredential(request: HttpServletRequest): Credential? =
        edgeCredential(request) ?: bearerCredential(request)

    private fun edgeCredential(request: HttpServletRequest): Credential? =
        request
            .getHeader(properties.headerName)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { Credential(it, CredentialSource.EDGE_ASSERTION) }

    private fun bearerCredential(request: HttpServletRequest): Credential? =
        request
            .takeIf { properties.acceptAuthorizationBearer }
            ?.getHeader("Authorization")
            ?.trim()
            ?.takeIf { it.regionMatches(0, BEARER_PREFIX, 0, BEARER_PREFIX.length, ignoreCase = true) }
            ?.substring(BEARER_PREFIX.length)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { Credential(it, CredentialSource.AUTHORIZATION_BEARER) }

    private fun Jwt.toPrincipal(source: CredentialSource): ForwardAuthPrincipal {
        val subject =
            subject ?: throw IllegalArgumentException("JWT subject is required")
        return ForwardAuthPrincipal(
            userId = UUID.fromString(subject),
            roles = claimAsStringSet("roles"),
            username = claims["username"] as? String,
            credentialSource = source,
        )
    }

    private fun Jwt.claimAsStringSet(name: String): Set<String> =
        when (val value = claims[name]) {
            is Collection<*> -> value.mapNotNull { it as? String }.toSet()
            is Array<*> -> value.mapNotNull { it as? String }.toSet()
            is String -> setOf(value)
            else -> emptySet()
        }

    private fun writeUnauthorized(response: HttpServletResponse) {
        response.status = HttpStatus.UNAUTHORIZED.value()
        response.contentType = MediaType.APPLICATION_PROBLEM_JSON_VALUE
        response.characterEncoding = Charsets.UTF_8.name()
        response.writer.write(UNAUTHORIZED_PROBLEM_JSON)
    }

    private data class Credential(
        val token: String,
        val source: CredentialSource,
    )

    companion object {
        val ATTRIBUTE_NAME: String = ForwardAuthPrincipal::class.java.name

        private const val BEARER_PREFIX = "Bearer "
        private const val UNAUTHORIZED_PROBLEM_JSON =
            "{\"type\":\"about:blank\",\"title\":\"Unauthorized\",\"status\":401," +
                "\"detail\":\"Missing or invalid caller identity.\"}"
    }
}
