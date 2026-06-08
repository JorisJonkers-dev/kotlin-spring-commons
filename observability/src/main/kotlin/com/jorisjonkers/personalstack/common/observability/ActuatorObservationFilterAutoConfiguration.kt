package com.jorisjonkers.personalstack.common.observability

import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationPredicate
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.context.annotation.Bean
import org.springframework.http.server.observation.ServerRequestObservationContext

// Drops actuator/health/scrape endpoints from the observation pipeline
// before they ever produce a span or a tracing-side metric. Kubelet
// liveness+readiness, Gatus probes and Prometheus scrapes hammer
// /actuator/* every few seconds per pod, drown out user traces in
// Tempo, and don't tell us anything we can't see in logs/metrics.
//
// The predicate sees `http.server.requests` for every inbound HTTP
// request; returning false silently skips the observation so neither
// Micrometer Tracing nor the OTLP exporter ever sees it.
@AutoConfiguration
@ConditionalOnClass(ObservationPredicate::class, ServerRequestObservationContext::class)
class ActuatorObservationFilterAutoConfiguration {
    @Bean
    fun excludeActuatorTracingPredicate(): ObservationPredicate = ActuatorObservationPredicate
}

internal object ActuatorObservationPredicate : ObservationPredicate {
    override fun test(
        name: String,
        context: Observation.Context,
    ): Boolean {
        if (name != HTTP_SERVER_REQUESTS) return true
        val uri = (context as? ServerRequestObservationContext)?.carrier?.requestURI ?: return true
        return !isExcluded(uri)
    }

    private fun isExcluded(uri: String): Boolean =
        uri.startsWith("/actuator") ||
            uri.startsWith("/api/actuator") ||
            uri == "/api/v1/health"

    private const val HTTP_SERVER_REQUESTS = "http.server.requests"
}
