package com.jorisjonkers.personalstack.common.observability

import com.sun.management.GarbageCollectionNotificationInfo
import com.sun.management.GcInfo
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Instant
import javax.management.Notification
import javax.management.openmbean.CompositeData

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
        unmockkAll()
        provider.shutdown()
    }

    @Test
    fun `auto configuration creates gc tracing beans`() {
        val configuration = GcEventTracingAutoConfiguration()

        assertThat(configuration.gcEventSpanEmitter()).isInstanceOf(GcEventSpanEmitter::class.java)
        assertThat(configuration.gcEventNotificationListener(emitter))
            .isInstanceOf(GcEventNotificationListener::class.java)
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
    fun `listener start and stop manage gc notification subscriptions`() {
        val listener = GcEventNotificationListener(emitter)

        assertThatCode {
            listener.start()
            listener.stop()
        }.doesNotThrowAnyException()
    }

    @Test
    fun `listener ignores unrelated and malformed notifications`() {
        val listener = GcEventNotificationListener(emitter)
        val malformedGcNotification =
            Notification(
                GarbageCollectionNotificationInfo.GARBAGE_COLLECTION_NOTIFICATION,
                this,
                2L,
            ).apply {
                userData = "not composite data"
            }

        listener.handleNotification(Notification("other", this, 1L), null)
        listener.handleNotification(malformedGcNotification, null)

        assertThat(spans.finishedSpanItems).isEmpty()
    }

    @Test
    fun `listener emits spans for valid gc notifications above the minimum duration`() {
        val listener = GcEventNotificationListener(emitter, minDurationMs = 10)
        val data = mockk<CompositeData>()
        val gcInfo = mockk<GcInfo>()
        val notificationInfo = mockk<GarbageCollectionNotificationInfo>()
        mockkStatic(GarbageCollectionNotificationInfo::from)
        every { GarbageCollectionNotificationInfo.from(data) } answers { notificationInfo }
        every { notificationInfo.gcInfo } answers { gcInfo }
        every { notificationInfo.gcName } answers { "G1 Young Generation" }
        every { notificationInfo.gcCause } answers { "G1 Evacuation Pause" }
        every { notificationInfo.gcAction } answers { "end of minor GC" }
        every { gcInfo.duration } answers { 25L }
        every { gcInfo.startTime } answers { 1_234L }

        listener.handleNotification(gcNotification(data), null)

        val span = spans.finishedSpanItems.single()
        assertThat(span.name).isEqualTo("gc.pause")
        assertThat(span.endEpochNanos - span.startEpochNanos).isEqualTo(25L * NANOS_PER_MILLI)
        assertThat(span.attributes.asMap()).containsEntry(AttributeKey.stringKey("gc.name"), "G1 Young Generation")
        assertThat(span.attributes.asMap()).containsEntry(AttributeKey.stringKey("gc.cause"), "G1 Evacuation Pause")
        assertThat(span.attributes.asMap()).containsEntry(AttributeKey.stringKey("gc.action"), "end of minor GC")
        assertThat(span.attributes.asMap()).containsEntry(AttributeKey.longKey("gc.duration_ms"), 25L)
    }

    @Test
    fun `listener ignores valid gc notifications below the minimum duration`() {
        val listener = GcEventNotificationListener(emitter, minDurationMs = 10)
        val data = mockk<CompositeData>()
        val gcInfo = mockk<GcInfo>()
        val notificationInfo = mockk<GarbageCollectionNotificationInfo>()
        mockkStatic(GarbageCollectionNotificationInfo::from)
        every { GarbageCollectionNotificationInfo.from(data) } answers { notificationInfo }
        every { notificationInfo.gcInfo } answers { gcInfo }
        every { gcInfo.duration } answers { 9L }

        listener.handleNotification(gcNotification(data), null)

        assertThat(spans.finishedSpanItems).isEmpty()
        verify(exactly = 0) {
            notificationInfo.gcName
            notificationInfo.gcCause
            notificationInfo.gcAction
            gcInfo.startTime
        }
    }

    private fun gcNotification(data: CompositeData): Notification =
        Notification(
            GarbageCollectionNotificationInfo.GARBAGE_COLLECTION_NOTIFICATION,
            this,
            3L,
        ).apply {
            userData = data
        }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
