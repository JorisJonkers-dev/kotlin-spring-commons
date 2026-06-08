package com.jorisjonkers.personalstack.common.timing

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.servlet.ModelAndView
import java.time.Instant

/**
 * Spring MVC interceptor that times the handler invocation and the
 * response write — `preHandle` records nanoTime before the controller
 * method runs, `afterCompletion` fires after the response has been
 * fully written (Jackson serialization included). Subtracting this
 * `[handler]` duration from the `[request]` total gives the time spent
 * in the upstream filter chain (Spring Security, session lookup,
 * CORS, CSRF, etc.).
 *
 * Also captures `Instant` checkpoints in request attributes so the
 * `RequestPipelineSpanFilter` can emit retroactive `handler-dispatch`
 * and `handler` child spans parented to the OTel agent's SERVER span.
 */
class HandlerTimingInterceptor : HandlerInterceptor {
    override fun preHandle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any,
    ): Boolean {
        request.setAttribute(RequestTimingAttributes.HANDLER_START_NANOS, System.nanoTime())
        request.setAttribute(RequestTimingAttributes.HANDLER_START_INSTANT, Instant.now())
        return true
    }

    override fun postHandle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any,
        modelAndView: ModelAndView?,
    ) {
        // Fires after the controller method returns the response object
        // but before Spring MVC dispatches to a view / message converter.
        request.setAttribute(RequestTimingAttributes.HANDLER_INVOKED_INSTANT, Instant.now())
    }

    override fun afterCompletion(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any,
        ex: Exception?,
    ) {
        request.setAttribute(RequestTimingAttributes.HANDLER_END_INSTANT, Instant.now())
        val start = request.getAttribute(RequestTimingAttributes.HANDLER_START_NANOS) as? Long ?: return
        val durationMs = (System.nanoTime() - start) / RequestTimingAttributes.NANOS_PER_MILLI
        logger.info(
            "[handler] {} {} duration_ms={}",
            request.method,
            request.requestURI,
            durationMs,
        )
    }

    companion object {
        private val logger = LoggerFactory.getLogger("com.jorisjonkers.personalstack.common.timing.handler")
    }
}
