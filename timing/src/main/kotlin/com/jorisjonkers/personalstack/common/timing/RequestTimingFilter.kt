package com.jorisjonkers.personalstack.common.timing

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Logs one line per HTTP request with method, path, status, total
 * duration, plus per-request `queries=N` + `query_ms=X` accumulated by
 * `JooqQueryTimingListener`. Brackets the entire chain (Spring Security,
 * session lookup, handler, serialization), so subtracting the
 * `[handler]` log's duration tells you how much time the framework
 * filters cost vs the handler + response write.
 *
 * Reads request attributes set by the jOOQ listener — `null` accumulators
 * mean the request issued no DB calls, which is itself useful diagnostic
 * info ("`queries=0 query_ms=0`" with a 12 s total → time is in
 * filters / Redis / serialization, not the database).
 */
class RequestTimingFilter : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val startNanos = System.nanoTime()
        try {
            filterChain.doFilter(request, response)
        } finally {
            val durationMs = (System.nanoTime() - startNanos) / RequestTimingAttributes.NANOS_PER_MILLI
            val query = request.queryString
            val path = if (query.isNullOrEmpty()) request.requestURI else "${request.requestURI}?$query"
            val queryCount = request.getAttribute(RequestTimingAttributes.QUERY_COUNT) as? Int ?: 0
            val queryNanos = request.getAttribute(RequestTimingAttributes.TOTAL_QUERY_NANOS) as? Long ?: 0L
            val queryMs = queryNanos / RequestTimingAttributes.NANOS_PER_MILLI
            timingLogger.info(
                "[request] {} {} status={} duration_ms={} queries={} query_ms={}",
                request.method,
                path,
                response.status,
                durationMs,
                queryCount,
                queryMs,
            )
        }
    }

    companion object {
        private val timingLogger = LoggerFactory.getLogger("com.jorisjonkers.personalstack.common.timing.request")
    }
}
