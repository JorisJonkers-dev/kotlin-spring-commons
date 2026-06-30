package com.jorisjonkers.personalstack.common.web

import java.time.Duration
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.ceil

private const val DEFAULT_MAX_BUCKETS = 10_000
private const val DEFAULT_BUCKET_IDLE_TTL_MINUTES = 30L
private const val DEFAULT_CLEANUP_INTERVAL = 128
private const val MILLIS_PER_SECOND = 1000.0

class InMemoryRequestRateLimiter(
    private val maxBuckets: Int = DEFAULT_MAX_BUCKETS,
    private val bucketIdleTtl: Duration = Duration.ofMinutes(DEFAULT_BUCKET_IDLE_TTL_MINUTES),
    private val cleanupInterval: Int = DEFAULT_CLEANUP_INTERVAL,
) {
    data class Decision(
        val allowed: Boolean,
        val retryAfterSeconds: Long = 0,
    )

    private data class Bucket(
        val timestamps: ArrayDeque<Long> = ArrayDeque(),
        @Volatile var lastSeenAt: Long = 0,
    )

    private val buckets = ConcurrentHashMap<String, Bucket>()
    private val capacityLock = Any()
    private val attemptCounter = AtomicLong(0)

    init {
        require(maxBuckets > 0) { "maxBuckets must be positive" }
        require(bucketIdleTtl.toMillis() > 0) { "bucketIdleTtl must be positive" }
        require(cleanupInterval > 0) { "cleanupInterval must be positive" }
    }

    fun tryAcquire(
        key: String,
        maxRequests: Int,
        window: Duration,
    ): Decision {
        require(maxRequests > 0) { "maxRequests must be positive" }
        val windowMillis = window.toMillis()
        require(windowMillis > 0) { "window must be positive" }

        val now = System.currentTimeMillis()
        maybeCleanup(now)
        val bucket = getOrCreateBucket(key, now)
        synchronized(bucket) {
            bucket.lastSeenAt = now
            pruneExpiredTimestamps(bucket, now, windowMillis)
            if (bucket.timestamps.size >= maxRequests) {
                val retryAfterMillis = (windowMillis - (now - bucket.timestamps.first())).coerceAtLeast(1)
                return Decision(allowed = false, retryAfterSeconds = retryAfterSeconds(retryAfterMillis))
            }
            bucket.timestamps.addLast(now)
            return Decision(allowed = true)
        }
    }

    internal fun trackedBucketCount(): Int = buckets.size

    private fun retryAfterSeconds(retryAfterMillis: Long): Long = ceil(retryAfterMillis / MILLIS_PER_SECOND).toLong()

    private fun getOrCreateBucket(
        key: String,
        now: Long,
    ): Bucket =
        buckets[key]
            ?: synchronized(capacityLock) {
                buckets[key]
                    ?: run {
                        enforceCapacity(now, reserve = 1)
                        buckets.computeIfAbsent(key) { Bucket(lastSeenAt = now) }
                    }
            }

    private fun maybeCleanup(now: Long) {
        if (attemptCounter.incrementAndGet() % cleanupInterval.toLong() != 0L) {
            return
        }
        synchronized(capacityLock) {
            enforceCapacity(now, reserve = 0)
        }
    }

    private fun enforceCapacity(
        now: Long,
        reserve: Int,
    ) {
        evictStaleBuckets(now)
        val overflow = buckets.size + reserve - maxBuckets
        if (overflow <= 0) {
            return
        }
        buckets.entries
            .asSequence()
            .map { it.key to it.value }
            .sortedBy { (_, bucket) -> bucket.lastSeenAt }
            .take(overflow)
            .forEach { (key, bucket) -> buckets.remove(key, bucket) }
    }

    private fun evictStaleBuckets(now: Long) {
        val idleMillis = bucketIdleTtl.toMillis()
        buckets.forEach { (key, bucket) ->
            if (now - bucket.lastSeenAt < idleMillis) {
                return@forEach
            }
            synchronized(bucket) {
                pruneExpiredTimestamps(bucket, now, idleMillis)
                if (now - bucket.lastSeenAt >= idleMillis && bucket.timestamps.isEmpty()) {
                    buckets.remove(key, bucket)
                }
            }
        }
    }

    private fun pruneExpiredTimestamps(
        bucket: Bucket,
        now: Long,
        windowMillis: Long,
    ) {
        while (bucket.timestamps.isNotEmpty() && now - bucket.timestamps.first() >= windowMillis) {
            bucket.timestamps.removeFirst()
        }
    }
}
