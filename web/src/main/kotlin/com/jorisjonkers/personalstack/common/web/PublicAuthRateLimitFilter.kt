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

        val decision = limiter.tryAcquire("${rule.method.uppercase()}|${rule.pathPattern}|${resolveClientIp(request)}", rule.maxRequests, rule.window)
        if (decision.allowed) {
            filterChain.doFilter(request, response)
            return
        }

        response.status = HttpStatus.TOO_MANY_REQUESTS.value()
        response.contentType = MediaType.APPLICATION_PROBLEM_JSON_VALUE
        response.characterEncoding = Charsets.UTF_8.name()
        response.setHeader("Retry-After", decision.retryAfterSeconds.toString())
        response.writer.write(
            """{"type":"about:blank","title":"Too Many Requests","status":429,"detail":"Too many requests. Please try again later."}""",
        )
    }

    fun resolveClientIp(request: HttpServletRequest): String {
        val remoteAddr = normalizeIpLiteral(request.remoteAddr) ?: "unknown"
        if (!isTrustedProxy(remoteAddr)) {
            return remoteAddr
        }
        normalizeIpLiteral(request.getHeader("X-Real-IP"))?.let { return it }
        request
            .getHeader("X-Forwarded-For")
            ?.split(",")
            ?.asSequence()
            ?.mapNotNull { normalizeIpLiteral(it) }
            ?.firstOrNull()
            ?.let { return it }
        return remoteAddr
    }

    private fun isTrustedProxy(remoteAddr: String): Boolean =
        remoteAddr != "unknown" &&
            trustedProxyMatchers.any { matcher -> runCatching { matcher.matches(remoteAddr) }.getOrDefault(false) }

    private fun normalizeIpLiteral(raw: String?): String? {
        val value = raw?.trim()?.takeIf { it.isNotBlank() } ?: return null
        if (value.length > 64) return null
        val unbracketed =
            if (value.startsWith("[") && value.contains("]")) {
                value.substringAfter('[').substringBefore(']')
            } else {
                value
            }
        val withoutPort =
            if (unbracketed.contains('.') && unbracketed.count { it == ':' } == 1) {
                unbracketed.substringBefore(':')
            } else {
                unbracketed
            }.trim()
        if (withoutPort.isBlank() || withoutPort.length > 45) return null
        if (!IP_LITERAL_PATTERN.matches(withoutPort)) return null
        return withoutPort.lowercase()
    }
}
