package com.jorisjonkers.personalstack.common.timing

import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import jakarta.servlet.FilterChain
import jakarta.servlet.ServletRequest
import jakarta.servlet.ServletResponse
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import java.time.Instant

class RequestPipelineSpanFilterTest {
    private val spans = InMemorySpanExporter.create()
    private val provider =
        SdkTracerProvider
            .builder()
            .addSpanProcessor(SimpleSpanProcessor.create(spans))
            .build()
    private val tracer = provider.get("test")
    private val filter = RequestPipelineSpanFilter(tracer)

    @AfterEach
    fun cleanup() {
        provider.shutdown()
    }

    @Test
    fun `emits five pipeline child spans when all checkpoints including postHandle are populated`() {
        val request = MockHttpServletRequest("GET", "/api/v1/auth/me")
        val response = MockHttpServletResponse()
        val populatingChain =
            FilterChain { req: ServletRequest, _: ServletResponse ->
                val t = Instant.now()
                req.setAttribute(RequestTimingAttributes.SECURITY_CHAIN_END_INSTANT, t.plusNanos(1_000))
                req.setAttribute(RequestTimingAttributes.HANDLER_START_INSTANT, t.plusNanos(2_000))
                req.setAttribute(RequestTimingAttributes.HANDLER_INVOKED_INSTANT, t.plusNanos(3_000))
                req.setAttribute(RequestTimingAttributes.HANDLER_END_INSTANT, t.plusNanos(4_000))
                // Yield enough wall time for the filter's requestEnd
                // capture to be strictly after handler-end.
                Thread.sleep(5)
            }

        filter.doFilter(request, response, populatingChain)

        val names = spans.finishedSpanItems.map { it.name }
        assertThat(names)
            .containsExactlyInAnyOrder(
                "pipeline.security-chain",
                "pipeline.handler-dispatch",
                "pipeline.controller",
                "pipeline.view-render",
                "pipeline.response-finalize",
            )
    }

    @Test
    fun `falls back to combined handler span when postHandle did not fire`() {
        // postHandle is skipped when the controller throws — in that
        // case we want a single handler span rather than losing the
        // segment entirely.
        val request = MockHttpServletRequest("GET", "/api/v1/auth/me")
        val response = MockHttpServletResponse()
        val populatingChain =
            FilterChain { req: ServletRequest, _: ServletResponse ->
                val t = Instant.now()
                req.setAttribute(RequestTimingAttributes.SECURITY_CHAIN_END_INSTANT, t.plusNanos(1_000))
                req.setAttribute(RequestTimingAttributes.HANDLER_START_INSTANT, t.plusNanos(2_000))
                req.setAttribute(RequestTimingAttributes.HANDLER_END_INSTANT, t.plusNanos(4_000))
                Thread.sleep(5)
            }

        filter.doFilter(request, response, populatingChain)

        val names = spans.finishedSpanItems.map { it.name }
        assertThat(names).contains("pipeline.handler")
        assertThat(names).doesNotContain("pipeline.controller", "pipeline.view-render")
    }

    @Test
    fun `emits only security-chain span when request short-circuits before handler`() {
        val request = MockHttpServletRequest("GET", "/api/v1/auth/me")
        val response = MockHttpServletResponse()
        val populatingChain =
            FilterChain { req: ServletRequest, _: ServletResponse ->
                req.setAttribute(RequestTimingAttributes.SECURITY_CHAIN_END_INSTANT, Instant.now())
            }

        filter.doFilter(request, response, populatingChain)

        val names = spans.finishedSpanItems.map { it.name }
        assertThat(names).containsExactly("pipeline.security-chain")
    }

    @Test
    fun `emits no spans when no checkpoints are populated`() {
        val request = MockHttpServletRequest("GET", "/api/v1/auth/me")
        val response = MockHttpServletResponse()

        filter.doFilter(request, response, MockFilterChain())

        // No security-chain end recorded means none of the segments
        // are emittable — this is the 'authz returned 401 before any
        // downstream filter ran' shape.
        assertThat(spans.finishedSpanItems).isEmpty()
    }

    @Test
    fun `every emitted span carries http_method http_route and http_status_code attributes`() {
        val request =
            MockHttpServletRequest("GET", "/api/v1/auth/me").apply {
                setAttribute(
                    "org.springframework.web.servlet.HandlerMapping.bestMatchingPattern",
                    "/api/v1/auth/me",
                )
            }
        val response = MockHttpServletResponse().apply { status = 200 }
        val populatingChain =
            FilterChain { req: ServletRequest, _: ServletResponse ->
                val t = Instant.now()
                req.setAttribute(RequestTimingAttributes.SECURITY_CHAIN_END_INSTANT, t.plusNanos(1_000))
                req.setAttribute(RequestTimingAttributes.HANDLER_START_INSTANT, t.plusNanos(2_000))
                req.setAttribute(RequestTimingAttributes.HANDLER_INVOKED_INSTANT, t.plusNanos(3_000))
                req.setAttribute(RequestTimingAttributes.HANDLER_END_INSTANT, t.plusNanos(4_000))
                Thread.sleep(5)
            }

        filter.doFilter(request, response, populatingChain)

        // Tempo's metrics-generator can only slice by attributes present
        // on the child span; if these aren't on every pipeline.* span,
        // span-derived RED metrics per route are impossible.
        assertThat(spans.finishedSpanItems).isNotEmpty()
        val methodKey =
            io.opentelemetry.api.common.AttributeKey
                .stringKey("http.method")
        val routeKey =
            io.opentelemetry.api.common.AttributeKey
                .stringKey("http.route")
        val statusKey =
            io.opentelemetry.api.common.AttributeKey
                .longKey("http.status_code")
        spans.finishedSpanItems.forEach { span ->
            assertThat(span.attributes.asMap()).containsEntry(methodKey, "GET")
            assertThat(span.attributes.asMap()).containsEntry(routeKey, "/api/v1/auth/me")
            assertThat(span.attributes.asMap()).containsEntry(statusKey, 200L)
        }
    }

    @Test
    fun `still emits spans when the chain throws`() {
        val request = MockHttpServletRequest("GET", "/api/v1/auth/me")
        val response = MockHttpServletResponse()
        val throwingChain =
            FilterChain { req: ServletRequest, _: ServletResponse ->
                req.setAttribute(RequestTimingAttributes.SECURITY_CHAIN_END_INSTANT, Instant.now())
                error("boom")
            }

        runCatching { filter.doFilter(request, response, throwingChain) }

        val names = spans.finishedSpanItems.map { it.name }
        assertThat(names).contains("pipeline.security-chain")
    }
}
