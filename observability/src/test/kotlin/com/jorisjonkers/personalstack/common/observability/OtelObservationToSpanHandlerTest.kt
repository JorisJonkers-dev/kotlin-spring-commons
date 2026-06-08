package com.jorisjonkers.personalstack.common.observability

import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationRegistry
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

class OtelObservationToSpanHandlerTest {
    private val spans = InMemorySpanExporter.create()
    private val provider =
        SdkTracerProvider
            .builder()
            .addSpanProcessor(SimpleSpanProcessor.create(spans))
            .build()
    private val tracer = provider.get("test")
    private val handler = OtelObservationToSpanHandler(tracer)
    private val registry =
        ObservationRegistry.create().apply { observationConfig().observationHandler(handler) }

    @AfterEach
    fun cleanup() {
        provider.shutdown()
    }

    @Test
    fun `auto configuration creates the observation span handler`() {
        assertThat(OtelObservationToSpanHandlerAutoConfiguration().otelObservationToSpanHandler())
            .isInstanceOf(OtelObservationToSpanHandler::class.java)
    }

    @Test
    fun `emits a span named after the observation`() {
        Observation
            .start("cache.gets", registry)
            .lowCardinalityKeyValue("cache", "users.byId")
            .lowCardinalityKeyValue("result", "hit")
            .stop()

        val span = spans.finishedSpanItems.single()
        assertThat(span.name).isEqualTo("cache.gets")
        assertThat(span.attributes.asMap())
            .containsEntry(AttributeKey.stringKey("cache"), "users.byId")
            .containsEntry(AttributeKey.stringKey("result"), "hit")
        assertThat(span.status.statusCode).isEqualTo(StatusCode.UNSET)
    }

    @Test
    fun `marks the span as ERROR when the observation carries an error`() {
        val ex = IllegalStateException("nope")
        val obs = Observation.start("redis.command", registry)
        obs.error(ex)
        obs.stop()

        val span = spans.finishedSpanItems.single()
        assertThat(span.status.statusCode).isEqualTo(StatusCode.ERROR)
        assertThat(span.events).anySatisfy { e -> assertThat(e.name).isEqualTo("exception") }
    }

    @Test
    fun `nested observations parent correctly under the outer span`() {
        val outer = Observation.start("outer", registry)
        outer.scoped {
            Observation
                .start("inner", registry)
                .stop()
        }
        outer.stop()

        val all = spans.finishedSpanItems.sortedBy { it.name }
        assertThat(all).extracting<String> { it.name }.containsExactly("inner", "outer")
        val outerSpan = all.first { it.name == "outer" }
        val innerSpan = all.first { it.name == "inner" }
        assertThat(innerSpan.parentSpanId).isEqualTo(outerSpan.spanId)
    }
}
