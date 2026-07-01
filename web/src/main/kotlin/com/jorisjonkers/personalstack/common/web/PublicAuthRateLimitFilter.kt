package com.jorisjonkers.personalstack.common.web

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.web.util.matcher.IpAddressMatcher
import org.springframework.util.AntPathMatcher
import org.springframework.web.filter.OncePerRequestFilter

private val IP_LITERAL_PATTERN = Regex("^[0-9A-Fa-f:.]+$")
private const val MAX_FORWARDED_IP_HEADER_LENGTH = 64
private const val MAX_IP_LITERAL_LENGTH = 45
private const val TOO_MANY_REQUESTS_PROBLEM_JSON =
    """{"type":"about:blank","title":"Too Many Requests","status":429,"detail":"Too many requests. Please try again later."}"""

class PublicAuthRateLimitFilter(
    private val limiter: InMemoryRequestRateLimiter,
    private val rules: List<WebUtilitiesProperties.RouteRateLimitRule>,
    trustedProxyCidrs: List<String> = WebUtilitiesProperties.RateLimitProperties().trustedProxyCidrs,
) : OncePerRequestFilter() {
    private val log = LoggerFactory.getLogger(javaClass)
    private val pathMatcher = AntPathMatcher()
    private val trustedProxyMatchers =
        trustedProxyCidrs.mapNotNull { raw ->
            val cidr = raw.trim()
            if (cidr.isBlank()) {
                null
            } else {
                runCatching { IpAddressMatcher(cidr) }
                    .onFailure { log.warn("Ignoring invalid trusted proxy CIDR '{}'", cidr) }
                    .getOrNull()
            }
        }

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val rule =
            rules.firstOrNull {
                it.method.equals(request.method, ignoreCase = true) &&
                    pathMatcher.match(it.pathPattern, request.servletPath)
            }

        if (rule == null) {
            filterChain.doFilter(request, response)
            return
        }

        val decision =
            limiter.tryAcquire(
                "${rule.method.uppercase()}|${rule.pathPattern}|${resolveClientIp(request)}",
                rule.maxRequests,
                rule.window,
            )
        if (decision.allowed) {
            filterChain.doFilter(request, response)
            return
        }

        response.status = HttpStatus.TOO_MANY_REQUESTS.value()
        response.contentType = MediaType.APPLICATION_PROBLEM_JSON_VALUE
        response.characterEncoding = Charsets.UTF_8.name()
        response.setHeader("Retry-After", decision.retryAfterSeconds.toString())
        response.writer.write(TOO_MANY_REQUESTS_PROBLEM_JSON)
    }

    fun resolveClientIp(request: HttpServletRequest): String {
        val remoteAddr = normalizeIpLiteral(request.remoteAddr) ?: "unknown"
        if (!isTrustedProxy(remoteAddr)) {
            return remoteAddr
        }
        return forwardedClientIp(request) ?: remoteAddr
    }

    private fun forwardedClientIp(request: HttpServletRequest): String? =
        normalizeIpLiteral(request.getHeader("X-Real-IP"))
            ?: request
                .getHeader("X-Forwarded-For")
                ?.split(",")
                ?.asSequence()
                ?.mapNotNull { normalizeIpLiteral(it) }
                ?.firstOrNull()

    private fun isTrustedProxy(remoteAddr: String): Boolean =
        remoteAddr != "unknown" &&
            trustedProxyMatchers.any { matcher ->
                runCatching { matcher.matches(remoteAddr) }.getOrDefault(false)
            }

    private fun normalizeIpLiteral(raw: String?): String? {
        val value = raw?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return value
            .takeIf { it.length <= MAX_FORWARDED_IP_HEADER_LENGTH }
            ?.let(::stripIpv6Brackets)
            ?.let(::stripIpv4Port)
            ?.trim()
            ?.takeIf { it.isNotBlank() && it.length <= MAX_IP_LITERAL_LENGTH }
            ?.takeIf(IP_LITERAL_PATTERN::matches)
            ?.lowercase()
    }

    private fun stripIpv6Brackets(value: String): String =
        if (value.startsWith("[") && value.contains("]")) {
            value.substringAfter('[').substringBefore(']')
        } else {
            value
        }

    private fun stripIpv4Port(value: String): String =
        if (value.contains('.') && value.count { it == ':' } == 1) {
            value.substringBefore(':')
        } else {
            value
        }
}
