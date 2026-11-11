package dev.ledgerforge.common.web;

import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-process token-bucket rate limiter. Each key (e.g. {@code auth:203.0.113.4}) owns a bucket
 * holding up to {@code capacity} tokens, refilled continuously at {@code capacity} tokens per
 * minute.
 *
 * <p>Limitation: state is per-instance. A horizontally scaled deployment should back this with a
 * shared store (e.g. Redis); the interface is intentionally small to make that swap easy.
 */
public class TokenBucketRateLimiter {

    private static final int MAX_KEYS = 100_000;

    private final int capacity;
    private final double refillPerMilli;
    private final Clock clock;
    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    public TokenBucketRateLimiter(int capacityPerMinute, Clock clock) {
        this.capacity = capacityPerMinute;
        this.refillPerMilli = capacityPerMinute / 60_000.0;
        this.clock = clock;
    }

    /** Returns 0 when the request is allowed, otherwise seconds until a token is available. */
    public long tryAcquire(String key) {
        if (buckets.size() > MAX_KEYS) {
            evictFull();
        }
        long now = clock.millis();
        Bucket bucket = buckets.computeIfAbsent(key, k -> new Bucket(capacity, now));
        synchronized (bucket) {
            double elapsed = Math.max(0, now - bucket.updatedAt);
            bucket.tokens = Math.min(capacity, bucket.tokens + elapsed * refillPerMilli);
            bucket.updatedAt = now;
            if (bucket.tokens >= 1) {
                bucket.tokens -= 1;
                return 0;
            }
            double missing = 1 - bucket.tokens;
            return Math.max(1, (long) Math.ceil(missing / refillPerMilli / 1000.0));
        }
    }

    private void evictFull() {
        long now = clock.millis();
        buckets.entrySet().removeIf(e -> now - e.getValue().updatedAt > 120_000);
    }

    private static final class Bucket {
        double tokens;
        long updatedAt;

        Bucket(double tokens, long updatedAt) {
            this.tokens = tokens;
            this.updatedAt = updatedAt;
        }
    }
}
