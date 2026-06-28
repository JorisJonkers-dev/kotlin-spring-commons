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
        if (isSkipPath(request)) {
            filterChain.doFilter(request, response)
            return
        }

        val credential = resolveCredential(request)
        if (credential == null) {
            writeUnauthorized(response)
            return
        }

        val principal =
            runCatching {
                jwtDecoder.decode(credential.token).toPrincipal(credential.source)
            }.getOrElse { exception ->
                when (exception) {
                    is JwtException, is IllegalArgumentException -> {
                        writeUnauthorized(response)
                        return
                    }
                    else -> throw exception
                }
            }

        request.setAttribute(ATTRIBUTE_NAME, principal)
        filterChain.doFilter(request, response)
    }

    private fun isSkipPath(request: HttpServletRequest): Boolean {
        val path = request.servletPath.takeIf { it.isNotBlank() } ?: request.requestURI
        return properties.skipPathPatterns.any { pattern -> pathMatcher.match(pattern, path) }
    }

    private fun resolveCredential(request: HttpServletRequest): Credential? {
        request
            .getHeader(properties.headerName)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { return Credential(it, CredentialSource.EDGE_ASSERTION) }

        if (!properties.acceptAuthorizationBearer) {
            return null
        }

        return request
            .getHeader("Authorization")
            ?.trim()
            ?.takeIf { it.regionMatches(0, "Bearer ", 0, "Bearer ".length, ignoreCase = true) }
            ?.substring("Bearer ".length)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { Credential(it, CredentialSource.AUTHORIZATION_BEARER) }
    }

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
        response.writer.write(
            """{"type":"about:blank","title":"Unauthorized","status":401,"detail":"Missing or invalid caller identity."}""",
        )
    }

    private data class Credential(
        val token: String,
        val source: CredentialSource,
    )

    companion object {
        val ATTRIBUTE_NAME: String = ForwardAuthPrincipal::class.java.name
    }
}
