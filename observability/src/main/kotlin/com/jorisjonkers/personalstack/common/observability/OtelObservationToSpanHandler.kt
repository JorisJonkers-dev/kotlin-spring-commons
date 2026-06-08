package com.jorisjonkers.personalstack.common.observability

import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationHandler
import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.context.Scope
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.context.annotation.Bean

// Bridges Micrometer Observation events to OTel spans, going straight
// at the agent's `GlobalOpenTelemetry.getTracer(...)` for the same
// reason `ApplicationTracingAspect` does: with the OTel Java agent
// attached, `micrometer-tracing-bridge-otel` silently fails because
// the agent installs its own Tracer independently of Spring Boot's
// `MicrometerTracingAutoConfiguration`, and the bridge handler ends
// up writing to a no-op SDK.
//
// Effect: every Observation in the app — Lettuce per-command timings
// (`db_operation=GET|HGETALL|HMSET|PEXPIREAT|EXISTS`), Spring Cache
// `cache.gets` / `cache.puts`, Spring Security `spring.security.*`
// authorisations — produces a Tempo span that drilldown can show
// nested inside the request SERVER span. Lets the slow-request
// post-mortem be done in Drilldown for Traces alone instead of
// jumping to Prometheus.
@AutoConfiguration
@ConditionalOnClass(ObservationHandler::class, Tracer::class)
class OtelObservationToSpanHandlerAutoConfiguration {
    @Bean
    fun otelObservationToSpanHandler(): OtelObservationToSpanHandler =
        OtelObservationToSpanHandler(GlobalOpenTelemetry.getTracer(INSTRUMENTATION_SCOPE))

    private companion object {
        const val INSTRUMENTATION_SCOPE =
            "com.jorisjonkers.personalstack.observation"
    }
}

class OtelObservationToSpanHandler(
    private val tracer: Tracer,
) : ObservationHandler<Observation.Context> {
    override fun supportsContext(context: Observation.Context): Boolean = true

    override fun onStart(context: Observation.Context) {
        val span =
            tracer
                .spanBuilder(context.name ?: "observation")
                .startSpan()
        val scope: Scope = span.makeCurrent()
        context.put(SPAN_KEY, span)
        context.put(SCOPE_KEY, scope)
    }

    override fun onStop(context: Observation.Context) {
        // Close the scope first so the current-span pointer leaves
        // this span before we end it — otherwise nested spans started
        // by downstream code parent off the wrong span on errors.
        context.remove(SCOPE_KEY)?.let { (it as Scope).close() }
        val span = context.remove(SPAN_KEY) as? Span ?: return

        // Surface every low-cardinality key/value (these are the
        // safe-for-attribute tags Micrometer publishes) as span
        // attributes. High-cardinality values (URIs with IDs, etc.)
        // are skipped — Tempo would happily ingest them but they
        // bloat the per-span attribute payload.
        for (kv in context.lowCardinalityKeyValues) {
            span.setAttribute(AttributeKey.stringKey(kv.key), kv.value)
        }
        context.error?.let { t ->
            span.recordException(t)
            span.setStatus(StatusCode.ERROR, t.message ?: t.javaClass.simpleName)
        }
        span.end()
    }

    private companion object {
        private const val SPAN_KEY = "personal-stack.observation.otel.span"
        private const val SCOPE_KEY = "personal-stack.observation.otel.scope"
    }
}
