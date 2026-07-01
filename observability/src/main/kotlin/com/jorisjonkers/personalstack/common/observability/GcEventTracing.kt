package com.jorisjonkers.personalstack.common.observability

import com.sun.management.GarbageCollectionNotificationInfo
import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.api.trace.Tracer
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import java.lang.management.ManagementFactory
import java.time.Instant
import javax.management.Notification
import javax.management.NotificationEmitter
import javax.management.NotificationListener
import javax.management.openmbean.CompositeData

/**
 * Subscribes to `GarbageCollectionNotificationInfo` JMX notifications
 * from every GarbageCollectorMXBean and emits a retroactive `gc.pause`
 * span per GC event whose pause duration exceeds the configured
 * threshold (default 5 ms). The span carries the GC's true start /
 * end timestamps (millisecond resolution from the MX bean) so it
 * appears at the correct point on the Tempo timeline and can be
 * visually correlated with any request spans active during the
 * pause window.
 *
 * Spans are parented to no SERVER span — they live as orphan spans
 * in their own trace. Correlation happens by overlapping time range
 * in Tempo's span view, not by parent-child link. This is intentional:
 * GC notifications fire on a JMX dispatcher thread that has no
 * request context, and an active request thread might be paused by
 * a GC that affects every other request thread simultaneously, so
 * fanning out parent links would be misleading anyway.
 *
 * Filtered to events `> personal-stack.gc-tracing.min-duration-ms`
 * (default 5 ms) so high-frequency ZGC concurrent cycles don't
 * dominate the span volume.
 */
@AutoConfiguration
@ConditionalOnClass(Tracer::class)
@ConditionalOnProperty(
    prefix = "personal-stack.gc-tracing",
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
class GcEventTracingAutoConfiguration {
    @Bean
    fun gcEventSpanEmitter(): GcEventSpanEmitter =
        GcEventSpanEmitter(GlobalOpenTelemetry.getTracer(INSTRUMENTATION_SCOPE))

    @Bean
    fun gcEventNotificationListener(emitter: GcEventSpanEmitter): GcEventNotificationListener =
        GcEventNotificationListener(emitter)

    private companion object {
        const val INSTRUMENTATION_SCOPE = "com.jorisjonkers.personalstack.gc"
    }
}

/**
 * Pure span emission — extracted from the JMX listener so it can
 * be exercised by unit tests without a real GC event.
 */
class GcEventSpanEmitter(
    private val tracer: Tracer,
) {
    fun emit(
        gcName: String,
        gcCause: String,
        gcAction: String,
        startEpochMillis: Long,
        durationMillis: Long,
    ) {
        val span =
            tracer
                .spanBuilder("gc.pause")
                .setStartTimestamp(Instant.ofEpochMilli(startEpochMillis))
                .startSpan()
        span.setAttribute("gc.name", gcName)
        span.setAttribute("gc.cause", gcCause)
        span.setAttribute("gc.action", gcAction)
        span.setAttribute("gc.duration_ms", durationMillis)
        span.end(Instant.ofEpochMilli(startEpochMillis + durationMillis))
    }
}

/**
 * JMX notification glue. `start()` (via `@PostConstruct`) attaches to
 * every GarbageCollectorMXBean; `stop()` (via `@PreDestroy`) detaches
 * during graceful shutdown so the listener doesn't leak on hot-reload.
 */
class GcEventNotificationListener(
    private val emitter: GcEventSpanEmitter,
    private val minDurationMs: Long = DEFAULT_MIN_DURATION_MS,
) : NotificationListener {
    private val jvmStartEpochMillis = ManagementFactory.getRuntimeMXBean().startTime

    @PostConstruct
    fun start() {
        ManagementFactory.getGarbageCollectorMXBeans().forEach { mbean ->
            (mbean as? NotificationEmitter)?.addNotificationListener(this, null, null)
        }
        logger.info("gc-tracing: subscribed to {} GC MX beans", ManagementFactory.getGarbageCollectorMXBeans().size)
    }

    @PreDestroy
    fun stop() {
        ManagementFactory.getGarbageCollectorMXBeans().forEach { mbean ->
            runCatching { (mbean as? NotificationEmitter)?.removeNotificationListener(this) }
        }
    }

    override fun handleNotification(
        notification: Notification,
        handback: Any?,
    ) {
        val info = notification.toGarbageCollectionInfo()
        if (info != null && info.gcInfo.duration >= minDurationMs) {
            emitter.emit(
                gcName = info.gcName,
                gcCause = info.gcCause,
                gcAction = info.gcAction,
                startEpochMillis = jvmStartEpochMillis + info.gcInfo.startTime,
                durationMillis = info.gcInfo.duration,
            )
        }
    }

    private fun Notification.toGarbageCollectionInfo(): GarbageCollectionNotificationInfo? =
        (userData as? CompositeData)
            ?.takeIf { type == GarbageCollectionNotificationInfo.GARBAGE_COLLECTION_NOTIFICATION }
            ?.let { GarbageCollectionNotificationInfo.from(it) }

    private companion object {
        const val DEFAULT_MIN_DURATION_MS = 5L
        private val logger = LoggerFactory.getLogger(GcEventNotificationListener::class.java)
    }
}
