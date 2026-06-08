package com.jorisjonkers.personalstack.common.timing

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.annotation.Order
import org.springframework.web.filter.OncePerRequestFilter
import java.time.Instant

// Spring Boot's SecurityProperties.DEFAULT_FILTER_ORDER is -100;
// inlined here so kotlin-common doesn't need a compile-time
// dependency on spring-boot-autoconfigure-security.
private const val SECURITY_CHAIN_BOUNDARY_FILTER_ORDER: Int = -100 + 1

/**
 * Marks the moment Spring Security's `FilterChainProxy` has finished
 * its pre-handler processing. Registered one position after the
 * Spring Security filter chain (Spring Boot's
 * `SecurityProperties.DEFAULT_FILTER_ORDER` is `-100`; using the
 * literal here avoids a hard compile-time dependency on
 * `spring-boot-autoconfigure-security` from kotlin-common) so its
 * `doFilterInternal` body executes only after the security filters
 * have run on the inbound path, and unwinds before they run on the
 * outbound path.
 *
 * Stores `Instant`s into request attributes; the actual span is
 * emitted retroactively by `RequestPipelineSpanFilter`.
 */
@Order(SECURITY_CHAIN_BOUNDARY_FILTER_ORDER)
class SecurityChainBoundaryFilter : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        request.setAttribute(RequestTimingAttributes.SECURITY_CHAIN_END_INSTANT, Instant.now())
        filterChain.doFilter(request, response)
    }
}
