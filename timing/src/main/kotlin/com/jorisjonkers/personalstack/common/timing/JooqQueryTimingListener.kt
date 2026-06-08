package com.jorisjonkers.personalstack.common.timing

import org.jooq.ExecuteContext
import org.jooq.ExecuteListener
import org.slf4j.LoggerFactory
import org.springframework.web.context.request.RequestAttributes
import org.springframework.web.context.request.RequestContextHolder

/**
 * Logs one line per jOOQ statement with type, duration_ms, and the
 * first 200 chars of the rendered SQL — and, when running inside an
 * HTTP request, accumulates per-request `query_count` + total query
 * nanos onto the current `HttpServletRequest`'s attributes so the
 * `RequestTimingFilter` can summarise them in the request log
 * (catching N+1 patterns like `queries=42 query_ms=950`).
 *
 * Wired into Spring Boot's auto-configured `DSLContext` via a
 * `@Bean DefaultExecuteListenerProvider` from `TimingAutoConfiguration`.
 */
class JooqQueryTimingListener : ExecuteListener {
    private val logger = LoggerFactory.getLogger("com.jorisjonkers.personalstack.common.timing.jooq")

    override fun executeStart(ctx: ExecuteContext) {
        ctx.data(START_NANOS_KEY, System.nanoTime())
    }

    override fun executeEnd(ctx: ExecuteContext) {
        val start = ctx.data(START_NANOS_KEY) as? Long ?: return
        val durationNanos = System.nanoTime() - start
        val durationMs = durationNanos / RequestTimingAttributes.NANOS_PER_MILLI
        val sql = ctx.sql()?.take(SQL_LOG_LIMIT) ?: "<no sql>"
        logger.info(
            "[jooq] type={} duration_ms={} sql={}",
            ctx.type(),
            durationMs,
            sql,
        )
        accumulateOnRequest(durationNanos)
    }

    private fun accumulateOnRequest(durationNanos: Long) {
        // No-op when the listener fires outside an HTTP request scope
        // (Flyway migrations, scheduled jobs, startup wiring, tests).
        val attrs = RequestContextHolder.getRequestAttributes() ?: return
        val scope = RequestAttributes.SCOPE_REQUEST
        val count = (attrs.getAttribute(RequestTimingAttributes.QUERY_COUNT, scope) as? Int ?: 0) + 1
        val total =
            (attrs.getAttribute(RequestTimingAttributes.TOTAL_QUERY_NANOS, scope) as? Long ?: 0L) + durationNanos
        attrs.setAttribute(RequestTimingAttributes.QUERY_COUNT, count, scope)
        attrs.setAttribute(RequestTimingAttributes.TOTAL_QUERY_NANOS, total, scope)
    }

    companion object {
        private const val START_NANOS_KEY = "personal-stack.timing.start_nanos"
        private const val SQL_LOG_LIMIT = 200
    }
}
