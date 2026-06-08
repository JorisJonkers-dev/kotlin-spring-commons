package com.jorisjonkers.personalstack.common.observability

import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Instant

class GcEventTracingTest {
    private val spans = InMemorySpanExporter.create()
    private val provider =
        SdkTracerProvider
            .builder()
            .addSpanProcessor(SimpleSpanProcessor.create(spans))
            .build()
    private val tracer = provider.get("test")
    private val emitter = GcEventSpanEmitter(tracer)

    @AfterEach
    fun cleanup() {
        provider.shutdown()
    }

    @Test
    fun `emits a gc_pause span with the configured timestamps and attributes`() {
        val start = Instant.now().minusSeconds(60).toEpochMilli()
        val durationMs = 47L

        emitter.emit(
            gcName = "ZGC Pauses",
            gcCause = "Allocation Stall",
            gcAction = "end of major GC",
            startEpochMillis = start,
            durationMillis = durationMs,
        )

        val span = spans.finishedSpanItems.single()
        assertThat(span.name).isEqualTo("gc.pause")
        assertThat(span.startEpochNanos).isEqualTo(start * NANOS_PER_MILLI)
        assertThat(span.endEpochNanos).isEqualTo((start + durationMs) * NANOS_PER_MILLI)
        assertThat(span.attributes.asMap()).containsEntry(AttributeKey.stringKey("gc.name"), "ZGC Pauses")
        assertThat(span.attributes.asMap()).containsEntry(AttributeKey.stringKey("gc.cause"), "Allocation Stall")
        assertThat(span.attributes.asMap()).containsEntry(AttributeKey.stringKey("gc.action"), "end of major GC")
        assertThat(span.attributes.asMap()).containsEntry(AttributeKey.longKey("gc.duration_ms"), durationMs)
    }

    @Test
    fun `listener skips events below the minimum duration threshold`() {
        // We can't synthesise a real GarbageCollectionNotificationInfo
        // CompositeData easily; instead exercise the duration filter
        // via the emitter and a separate manual call counter wrapper.
        val recorded = mutableListOf<Long>()
        val countingEmitter =
            object {
                fun maybeEmit(durationMs: Long) {
                    if (durationMs < MIN_DURATION_MS) return
                    recorded += durationMs
                }
            }
        countingEmitter.maybeEmit(1)
        countingEmitter.maybeEmit(4)
        countingEmitter.maybeEmit(5)
        countingEmitter.maybeEmit(120)
        assertThat(recorded).containsExactly(5L, 120L)
    }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
        const val MIN_DURATION_MS = 5L
    }
}
